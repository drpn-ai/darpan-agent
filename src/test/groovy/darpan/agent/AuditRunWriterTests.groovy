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
import org.junit.jupiter.api.Test

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-207. The audit run words: what the model may not slip past the fact sheet, and the paths that send no words. */
class AuditRunWriterTests {
    private static final def MAPPER = ObjectMappers.jsonMapper()
    private static final Closure ENABLED = AuditRunWriter.enabled
    private static final Closure GATEWAY = AuditRunWriter.gatewayFor

    /** A small fact sheet in core's shape (AuditRunFacts.build); the ids are made up. */
    static final Map FACTS = [
            tenant: [id: "TEST_TENANT", name: "Test Tenant"], audit: [id: "month-close", name: "Month close"],
            period: "month", date: "2026-11-09", window: [from: "2026-11-01", thru: "2026-11-09"],
            totals: [open: 1204, newToday: 7, fixedToday: 3],
            rules : [[id: "R1", text: "Every order is in the OMS.", open: 1190, newToday: 5, fixedToday: 3, checksNotRun: []],
                     [id: "R2", text: "Every order is billed.", open: 14, newToday: 2, fixedToday: 0, checksNotRun: ["C9"]]],
            groups: [[rule: "R1", cause: "Stuck in approval", count: 1190, fixText: "HotWax support: approve the order.", ids: []],
                     [rule: "R2", cause: null, count: 14, fixText: null, ids: ["ORD-1001", "ORD-1002"]]]]

    @AfterEach
    void restore() {
        AuditRunWriter.enabled = ENABLED
        AuditRunWriter.gatewayFor = GATEWAY
        darpan.agent.loop.AgentUsageLedger.resetForTests()
        AgentConfigTestSupport.clearSettings()
    }

    /** OC186 (Aditi 2026-11-28 "Record it"): every answer the model sent is spend on the tenant's ledger, a refused or cut one too. */
    @Test
    void "each answer is recorded on the tenant's ledger, a failed call is not"() {
        List<Map> recorded = []
        darpan.agent.loop.AgentUsageLedger.writerRecorder = { def ec, String tenantId, String role, def usage, String model, def cfg ->
            recorded << [tenantId: tenantId, role: role, model: model, input: usage.inputTokens(), output: usage.outputTokens()]; 7L }
        AuditRunWriter.enabled = { def ec, String t -> true }
        List<Duration> timeouts = []
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { message("end_turn", JsonOutput.toJson([headline: "", fixes: []])) } }
        AuditRunWriter.write(null, "TEST_TENANT", FACTS)
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { message("refusal", "") } }
        AuditRunWriter.write(null, "TEST_TENANT", FACTS)
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { throw new IllegalStateException("No Claude API key is configured") } }
        AuditRunWriter.write(null, "TEST_TENANT", FACTS)
        assertEquals([[tenantId: "TEST_TENANT", role: "AUDIT_RUN_WORDS", model: "claude-haiku-5-5", input: 900L, output: 120L]] * 2, recorded)
    }

    @Test
    void "a ledger that cannot be written never costs the words"() {
        darpan.agent.loop.AgentUsageLedger.writerRecorder = { def ec, String t, String r, def u, String m, def c -> throw new IllegalStateException("db down") }
        AuditRunWriter.enabled = { def ec, String t -> true }
        AuditRunWriter.gatewayFor = { def ec -> gateway([]) { message("end_turn", JsonOutput.toJson([headline: "1,204 findings are open.", fixes: []])) } }
        assertEquals("1,204 findings are open.", AuditRunWriter.write(null, "TEST_TENANT", FACTS).headline)
    }

    @Test
    void "words that hold against the facts are kept, keyed as core reads them"() {
        Map words = AuditRunWriter.checked(FACTS, [
                headline: "1,204 findings are open across 2 rules.  Most are 1190 orders stuck in approval.",
                fixes   : [[key: "R1|Stuck in approval", text: "HotWax support: approve these orders."],
                           [key: "R2|", text: "No rule names a cause yet; look at these in Darpan."]]])
        assertEquals("1,204 findings are open across 2 rules. Most are 1190 orders stuck in approval.", words.headline,
                "a grouped thousand is the fact's number; 2 is the count of rules with open findings")
        assertEquals(["R1|Stuck in approval": "HotWax support: approve these orders.",
                      "R2|"                 : "No rule names a cause yet; look at these in Darpan."], words.fixes)
    }

    @Test
    void "a number the facts do not have drops the headline, not the fixes"() {
        Map words = AuditRunWriter.checked(FACTS, [
                headline: "1,250 findings are open.",
                fixes   : [[key: "R1|Stuck in approval", text: "HotWax support: approve these orders."]]])
        assertNull(words.headline)
        assertEquals(1, words.fixes.size())
    }

    @Test
    void "values side by side in the facts' JSON are not read as one number"() {
        // [3,5] in the JSON is 3 and 5, never 35: otherwise an invented 35 would pass.
        Map facts = FACTS + [totals: [open: 3, newToday: 5, fixedToday: 0], rules: [], extra: [3, 5]]
        assertNull(AuditRunWriter.checked(facts, [headline: "35 are open.", fixes: []]).headline)
        assertEquals("3 are open, 5 new.", AuditRunWriter.checked(facts, [headline: "3 are open, 5 new.", fixes: []]).headline)
    }

    @Test
    void "a fix is dropped when it repeats its group's id, holds a placeholder, names no group, or comes twice"() {
        Map words = AuditRunWriter.checked(FACTS, [headline: "", fixes: [
                [key: "R2|", text: "Bill ORD-1001 in NetSuite."],
                [key: "R1|Stuck in approval", text: "Approve {orderId}."],
                [key: "R9|Made up", text: "Do something."],
                [key: "R1|Stuck in approval", text: "HotWax support: approve these orders."],
                [key: "R1|Stuck in approval", text: "A second answer for the same group."],
                "not an entry"]])
        assertNull(words.headline, "blank")
        assertEquals(["R1|Stuck in approval": "HotWax support: approve these orders."], words.fixes)
    }

    @Test
    void "an answer that is not the agreed object is no words"() {
        assertEquals([:], AuditRunWriter.checked(FACTS, null))
        assertEquals([:], AuditRunWriter.checked(FACTS, ["a list"]))
        assertEquals([headline: null, fixes: [:]], AuditRunWriter.checked(FACTS, [headline: 42, fixes: "R1"]))
    }

    @Test
    void "the words use the configured model, prompt, timeout and max tokens"() {
        AuditRunWriter.enabled = { def ec, String t -> true }
        AgentConfigTestSupport.settings(["darpan.agent.auditRunWordsTimeoutMs": "7000", "darpan.agent.auditRunWordsMaxTokens": "1500"])
        List<MessageCreateParams> sent = []
        List<Duration> timeouts = []
        AuditRunWriter.gatewayFor = { def ec -> new ModelGateway() {
            Message send(MessageCreateParams params) { sent << params; return message("end_turn", '{"headline": "", "fixes": []}') }
            Message send(MessageCreateParams params, Duration timeout) { sent << params; timeouts << timeout; return message("end_turn", '{"headline": "", "fixes": []}') }
        } }
        AgentConfig cfg = AgentConfig.defaults().with(models: [AUDIT_RUN_WORDS: "claude-sonnet-5-5"], prompts: ["audit-run-words": "CONFIGURED WORDS PROMPT"])
        AgentConfig.withConfig(cfg) { AuditRunWriter.write(null, "TEST_TENANT", FACTS) }
        assertEquals([Duration.ofMillis(7000)], timeouts)
        assertEquals("claude-sonnet-5-5", sent[0].model().asString())
        assertEquals(1500L, sent[0].maxTokens())
        assertTrue(sent[0].system().get().toString().contains("CONFIGURED WORDS PROMPT"))
    }

    @Test
    void "an unpriced words model sends nothing and gives no words"() {
        AuditRunWriter.enabled = { def ec, String t -> true }
        AuditRunWriter.gatewayFor = { def ec -> fail("the model was asked") }
        AgentConfig cfg = AgentConfig.defaults().with(models: [AUDIT_RUN_WORDS: "claude-unpriced-9"])
        assertEquals([:], AgentConfig.withConfig(cfg) { AuditRunWriter.write(null, "TEST_TENANT", FACTS) })
    }

    @Test
    void "headline and fix caps come from the settings"() {
        AgentConfigTestSupport.settings(["darpan.agent.auditRunWordsHeadlineMaxChars": "60", "darpan.agent.auditRunWordsFixMaxChars": "50"])
        Map words = AuditRunWriter.checked(FACTS, [headline: "1,204 " + ("x" * 60),
                fixes: [[key: "R1|Stuck in approval", text: "y" * 51], [key: "R2|", text: "z" * 50]]])
        assertNull(words.headline, "over the configured headline cap")
        assertEquals(["R2|": "z" * 50], words.fixes, "51 chars is over the configured fix cap, 50 is not")
    }

    @Test
    void "the request is one Haiku call at low effort with the schema and the facts, no thinking config"() {
        Map wire = (Map) new JsonSlurper().parseText(MAPPER.writeValueAsString(AuditRunWriter.params(FACTS, AgentConfig.defaults(), AgentLimits.read())._body()))
        assertEquals("claude-haiku-5-5", wire.model)
        assertEquals("low", wire.output_config.effort)
        assertEquals("json_schema", wire.output_config.format.type)
        assertEquals(false, wire.output_config.format.schema.additionalProperties)
        assertEquals(false, ((Map) wire.output_config.format.schema).get('properties').fixes.items.additionalProperties)
        assertNull(wire.thinking)
        assertNull(wire.tools)
        assertEquals("ephemeral", wire.system[0].cache_control.type, "the prompt is the same on every run")
        assertTrue((wire.messages[0].content as String).contains(JsonOutput.toJson(FACTS)))
    }

    @Test
    void "with the agent off for the tenant the model is never asked"() {
        AuditRunWriter.enabled = { def ec, String t -> false }
        AuditRunWriter.gatewayFor = { def ec -> fail("the model was asked") }
        assertEquals([:], AuditRunWriter.write(null, "TEST_TENANT", FACTS))
    }

    @Test
    void "an answer comes back checked, and a refusal, a cut-off answer or a failure is no words"() {
        AuditRunWriter.enabled = { def ec, String t -> true }
        String json = JsonOutput.toJson([headline: "1,204 findings are open.", fixes: [[key: "R9|x", text: "Nope."]]])
        List<Duration> timeouts = []
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { message("end_turn", json) } }
        assertEquals([headline: "1,204 findings are open.", fixes: [:]], AuditRunWriter.write(null, "TEST_TENANT", FACTS))
        assertEquals([Duration.ofSeconds(60)], timeouts)

        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { message("refusal", "") } }
        assertEquals([:], AuditRunWriter.write(null, "TEST_TENANT", FACTS))
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { message("max_tokens", '{"headline": "1,2') } }
        assertEquals([:], AuditRunWriter.write(null, "TEST_TENANT", FACTS))
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { throw new IllegalStateException("No Claude API key is configured") } }
        assertEquals([:], AuditRunWriter.write(null, "TEST_TENANT", FACTS))
        AuditRunWriter.gatewayFor = { def ec -> gateway(timeouts) { message("end_turn", "not json") } }
        assertEquals([:], AuditRunWriter.write(null, "TEST_TENANT", FACTS))
    }

    @Test
    void "no tenant or no groups is no words, without asking"() {
        AuditRunWriter.enabled = { def ec, String t -> fail("the switch was read") }
        assertEquals([:], AuditRunWriter.write(null, null, FACTS))
        assertEquals([:], AuditRunWriter.write(null, "TEST_TENANT", [:]))
    }

    private static ModelGateway gateway(List<Duration> timeouts, Closure<Message> answer) {
        return new ModelGateway() {
            Message send(MessageCreateParams params) { return answer.call() }
            Message send(MessageCreateParams params, Duration timeout) { timeouts << timeout; return answer.call() }
        }
    }

    private static Message message(String stopReason, String text) {
        return MAPPER.readValue(JsonOutput.toJson([id: "msg_test", type: "message", role: "assistant", model: "claude-haiku-5-5",
                content: text ? [[type: "text", text: text]] : [], stop_reason: stopReason, stop_sequence: null,
                usage: [input_tokens: 900, output_tokens: 120]]), Message)
    }
}
