package darpan.agent

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import darpan.agent.claude.ModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigTestSupport
import darpan.agent.config.AgentLimits
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-320 phase 1b. The read review: a ticket per flag whatever the model says, the model's words only when they hold. */
class AuditReadReviewerTests {
    private static final def MAPPER = ObjectMappers.jsonMapper()
    private static final Closure ENABLED = AuditReadReviewer.enabled
    private static final Closure GATEWAY = AuditReadReviewer.gatewayFor
    private static final Closure OPEN = AuditReadReviewer.openTicket
    private static final Closure INSERT = AuditReadReviewer.insertTicket

    /** A play in core's shape (AuditReadReport.forPlay); ids made up. */
    static final Map DUPLICATE = [flag: "DUPLICATE_READ", endpointEnumId: "SHOPIFY", sourceConfigId: "STORE_US", checks: ["R2", "R3", "R5"],
                                  detail: [reads: 3], text: "SHOPIFY on STORE_US was read 3 times this morning (R2, R3, R5); one read should serve them all."]
    static final Map OVER_SHARE = [flag: "LOOKUP_OVER_SHARE", endpointEnumId: "NETSUITE", sourceConfigId: "NS_MAIN", checks: ["R7"],
                                   detail: [candidates: 412, checkedAgainst: 900, maxSharePercent: 5],
                                   text: "R7: 412 of 900 records were missing on NETSUITE on NS_MAIN, above the 5% a lookup is for; the read or the pairing is wrong."]
    static final Map REPORT = [reportDate: "2026-11-19", period: "2026-11",
                               totals    : [reads: 7, connections: 3, records: 18400, calls: [graphql: 214]],
                               groups    : [[endpointEnumId: "SHOPIFY", sourceConfigId: "STORE_US", reads: 3, records: 9000, checks: ["R2", "R3", "R5"],
                                             calls: [graphql: 180, bulkStarts: 3]]],
                               flags     : [DUPLICATE, OVER_SHARE]]

    List<Map> inserted = []
    Set<String> openHashes = [] as Set

    @BeforeEach
    void seams() {
        AuditReadReviewer.openTicket = { def ec, String t, String hash -> openHashes.contains(hash) ? "OPEN_1" : null }
        AuditReadReviewer.insertTicket = { def ec, Map fields -> inserted << fields; return "T${inserted.size()}".toString() }
    }

    @AfterEach
    void restore() {
        AuditReadReviewer.enabled = ENABLED
        AuditReadReviewer.gatewayFor = GATEWAY
        AuditReadReviewer.openTicket = OPEN
        AuditReadReviewer.insertTicket = INSERT
        darpan.agent.loop.AgentUsageLedger.resetForTests()
        AgentConfigTestSupport.clearSettings()
    }

    /** OC186 (Aditi 2026-11-28 "Record it"): the review's model call is spend on the tenant's ledger. */
    @Test
    void "the review's answer is recorded on the tenant's ledger"() {
        List<String> recorded = []
        darpan.agent.loop.AgentUsageLedger.writerRecorder = { def ec, String tenantId, String role, def usage, String model, def cfg ->
            recorded << "${tenantId}:${role}".toString(); 1L }
        AuditReadReviewer.enabled = { def ec, String t -> true }
        AuditReadReviewer.gatewayFor = { def ec -> gateway { message("end_turn", JsonOutput.toJson([reviews: []])) } }
        AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read())
        assertEquals(["TEST_TENANT:AUDIT_READ_REVIEW"], recorded)
    }

    @Test
    void "with the agent off every flag still becomes a ticket, in code's words with its default owner"() {
        AuditReadReviewer.enabled = { def ec, String t -> false }
        AuditReadReviewer.gatewayFor = { def ec -> fail("the model was asked") }
        Map out = AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read())
        assertEquals(0, out.reviewed)
        assertEquals(["T1", "T2"], out.tickets*.ticketId)
        assertEquals(["READ_ECONOMY_EXTRACT", "READ_ECONOMY_BRAIN"], inserted*.category,
                "a skipped lookup means the pairing or a filter is wrong: a brain rule")
        assertEquals(DUPLICATE.text, inserted[0].verbatimText)
        assertEquals(BrainTicketSupport.sha256("read-economy|DUPLICATE_READ|SHOPIFY|STORE_US"), inserted[0].textHash)
        assertEquals([auditId: "AUD1", reportDate: "2026-11-19", period: "2026-11", checks: ["R2", "R3", "R5"]],
                new JsonSlurper().parseText(inserted[0].refsJson as String))
        assertEquals("RECEIVED", inserted[0].status)
    }

    @Test
    void "a flag with an open ticket is neither sent to the model nor raised again"() {
        openHashes << BrainTicketSupport.sha256(AuditReadReviewer.keyOf(DUPLICATE))
        List<Map> asked = []
        AuditReadReviewer.enabled = { def ec, String t -> true }
        AuditReadReviewer.gatewayFor = { def ec -> gateway { MessageCreateParams p -> asked << p; message("end_turn", '{"reviews": []}') } }
        Map out = AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read())
        assertEquals([[ticketId: "OPEN_1", flag: "DUPLICATE_READ", key: AuditReadReviewer.keyOf(DUPLICATE), duplicate: true],
                      [ticketId: "T1", flag: "LOOKUP_OVER_SHARE", key: AuditReadReviewer.keyOf(OVER_SHARE), duplicate: false]], out.tickets)
        String sentFacts = ((Map) new JsonSlurper().parseText(MAPPER.writeValueAsString(asked[0]._body()))).messages[0].content
        assertFalse(sentFacts.contains("DUPLICATE_READ"), "only the flags without a ticket go to the model")

        openHashes << BrainTicketSupport.sha256(AuditReadReviewer.keyOf(OVER_SHARE))
        AuditReadReviewer.gatewayFor = { def ec -> fail("the model was asked with every flag already ticketed") }
        assertEquals([true, true], AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read()).tickets*.duplicate)
    }

    @Test
    void "a kept review sets the owner and adds its diagnosis and suggestion to the ticket"() {
        AuditReadReviewer.enabled = { def ec, String t -> true }
        String answer = JsonOutput.toJson([reviews: [
                [key: AuditReadReviewer.keyOf(DUPLICATE), owner: "BRAIN_RULE", diagnosis: "R2, R3 and R5 each extract the store's orders.",
                 suggestion: "Make R3 and R5 consume R2's read."]]])
        AuditReadReviewer.gatewayFor = { def ec -> gateway { message("end_turn", answer) } }
        Map out = AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read())
        assertEquals(1, out.reviewed)
        assertEquals("READ_ECONOMY_BRAIN", inserted[0].category, "the review's owner wins over the default")
        assertEquals(DUPLICATE.text + "\n\nDiagnosis: R2, R3 and R5 each extract the store's orders.\n\nSuggestion: Make R3 and R5 consume R2's read.",
                inserted[0].verbatimText)
        assertEquals(true, new JsonSlurper().parseText(inserted[0].evidenceJson as String).reviewedByModel)
        assertEquals(OVER_SHARE.text, inserted[1].verbatimText, "a flag the model skipped keeps code's words")
    }

    @Test
    void "a review is dropped for an unknown key, owner, invented number, placeholder, over-long text, or a repeat"() {
        Map facts = AuditReadReviewer.facts(REPORT, REPORT.flags as List<Map>)
        String dup = AuditReadReviewer.keyOf(DUPLICATE)
        String over = AuditReadReviewer.keyOf(OVER_SHARE)
        Map<String, Map> kept = AuditReadReviewer.checked(facts, [reviews: [
                [key: "read-economy|MADE_UP||", owner: "BRAIN_RULE", diagnosis: "x", suggestion: "y"],
                [key: dup, owner: "SOMEONE", diagnosis: "x", suggestion: "y"],
                [key: dup, owner: "EXTRACT_STRATEGY", diagnosis: "The store was read 4 times.", suggestion: "Share one read."],
                [key: dup, owner: "EXTRACT_STRATEGY", diagnosis: "Read {count} times.", suggestion: "Share one read."],
                [key: dup, owner: "EXTRACT_STRATEGY", diagnosis: "The store was read 3 times by R2, R3 and R5.", suggestion: "Share one read."],
                [key: dup, owner: "BRAIN_RULE", diagnosis: "A second answer.", suggestion: "Ignored."],
                [key: over, owner: "BRAIN_RULE", diagnosis: "z" * 601, suggestion: "Filter both sides."],
                "not a review"]], AgentLimits.read())
        assertEquals([(dup): [owner: "EXTRACT_STRATEGY", diagnosis: "The store was read 3 times by R2, R3 and R5.", suggestion: "Share one read."]], kept)
        assertEquals([:], AuditReadReviewer.checked(facts, null, AgentLimits.read()))
        assertEquals([:], AuditReadReviewer.checked(facts, [reviews: "no"], AgentLimits.read()))
    }

    @Test
    void "the request uses the review role's model, prompt, timeout and token cap"() {
        AuditReadReviewer.enabled = { def ec, String t -> true }
        AgentConfigTestSupport.settings(["darpan.agent.auditReadReviewTimeoutMs": "9000", "darpan.agent.auditReadReviewMaxTokens": "2000"])
        List<MessageCreateParams> sent = []
        List<Duration> timeouts = []
        AuditReadReviewer.gatewayFor = { def ec -> new ModelGateway() {
            Message send(MessageCreateParams params) { sent << params; return message("end_turn", '{"reviews": []}') }
            Message send(MessageCreateParams params, Duration timeout) { sent << params; timeouts << timeout; return message("end_turn", '{"reviews": []}') }
        } }
        AgentConfig cfg = AgentConfig.defaults().with(models: [AUDIT_READ_REVIEW: "claude-sonnet-5-5"], prompts: ["audit-read-review": "CONFIGURED REVIEW PROMPT"])
        AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, cfg, AgentLimits.read())
        assertEquals([Duration.ofMillis(9000)], timeouts)
        assertEquals("claude-sonnet-5-5", sent[0].model().asString())
        assertEquals(2000L, sent[0].maxTokens())
        assertTrue(sent[0].system().get().toString().contains("CONFIGURED REVIEW PROMPT"))
        Map wire = (Map) new JsonSlurper().parseText(MAPPER.writeValueAsString(
                AuditReadReviewer.params([flags: []], AgentConfig.defaults(), AgentLimits.read())._body()))
        assertEquals("claude-haiku-5-5", wire.model, "the shipped default role model")
        assertEquals("json_schema", wire.output_config.format.type)
        assertTrue(AgentConfig.defaults().prompt("audit-read-review").contains("<facts>"), "the shipped prompt is on the class path")
    }

    @Test
    void "no flags, or no tenant, asks nothing and raises nothing, and a failed model call still raises the tickets"() {
        AuditReadReviewer.enabled = { def ec, String t -> fail("the switch was read") }
        assertEquals([tickets: [], reviewed: 0], AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT + [flags: []], AgentConfig.defaults(), AgentLimits.read()))
        assertEquals([tickets: [], reviewed: 0], AuditReadReviewer.review(null, null, "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read()))
        assertTrue(inserted.isEmpty())

        AuditReadReviewer.enabled = { def ec, String t -> true }
        AuditReadReviewer.gatewayFor = { def ec -> gateway { throw new IllegalStateException("No Claude API key is configured") } }
        assertEquals(2, AuditReadReviewer.review(null, "TEST_TENANT", "AUD1", REPORT, AgentConfig.defaults(), AgentLimits.read()).tickets.size())
    }

    private static ModelGateway gateway(Closure<Message> answer) {
        return new ModelGateway() {
            // A closure with no declared parameter still takes one (it), so every answer is called with the request.
            Message send(MessageCreateParams params) { return answer.call(params) }
            Message send(MessageCreateParams params, Duration timeout) { return answer.call(params) }
        }
    }

    private static Message message(String stopReason, String text) {
        return MAPPER.readValue(JsonOutput.toJson([id: "msg_test", type: "message", role: "assistant", model: "claude-haiku-5-5",
                content: text ? [[type: "text", text: text]] : [], stop_reason: stopReason, stop_sequence: null,
                usage: [input_tokens: 900, output_tokens: 120]]), Message)
    }
}
