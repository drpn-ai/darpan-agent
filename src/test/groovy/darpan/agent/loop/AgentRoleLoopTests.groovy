package darpan.agent.loop

import com.anthropic.core.JsonValue
import com.anthropic.core.ObjectMappers
import com.anthropic.core.http.Headers
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Usage
import darpan.agent.AgentCallMarker
import darpan.agent.claude.ModelGateway
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-159 Task 8. The role loop against a scripted gateway that answers real SDK Message objects (read from wire JSON
 * with the SDK's own mapper). Tool calls go through the real facade services as the signed-in user. No test calls the API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentRoleLoopTests {

    private static final String MINE = "ARL_TENANT_A"
    private static final String OTHER = "ARL_OTHER"
    private static final String USERNAME = "test.customer"
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String CONV = "ARL_CONV"
    private static final String PROMPT = "darpan/agent/prompts/test-role.md"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final def MAPPER = ObjectMappers.jsonMapper()

    private ExecutionContext ec

    // ------------------------------------------------------------------ fixtures

    /** Answers each request with the next scripted Message; a Throwable is thrown, a Closure is called first. */
    static class ScriptedGateway implements ModelGateway {
        final List<Object> script
        final List<MessageCreateParams> sent = []
        /** The per-attempt timeout each request was given. */
        final List<java.time.Duration> timeouts = []
        ScriptedGateway(List<Object> script) { this.script = new ArrayList<Object>(script) }
        @Override
        Message send(MessageCreateParams params, java.time.Duration timeout) {
            timeouts << timeout
            return send(params)
        }
        @Override
        Message send(MessageCreateParams params) {
            sent << params
            if (script.isEmpty()) throw new AssertionError("gateway called more often than scripted")
            Object next = script.remove(0)
            if (next instanceof Closure) next = ((Closure) next).call()
            if (next instanceof Throwable) throw (Throwable) next
            return (Message) next
        }
    }

    /** Counts real service invocations and whether each ran inside the agent-call marker. */
    static class CountingExecutor extends AgentToolExecutor {
        final List<String> invoked = []
        final List<Boolean> markerSeen = []
        CountingExecutor(RoleSpec spec, String conversationId) { super(spec, conversationId) }
        @Override
        protected Map invoke(def ec, String serviceName, Map params) {
            invoked << serviceName
            markerSeen << AgentCallMarker.inAgentLoop()
            return super.invoke(ec, serviceName, params)
        }
    }

    static class CountingLoop extends AgentRoleLoop {
        CountingExecutor executor
        /** Retry waits, recorded instead of slept. */
        final List<Long> pauses = []
        /** A fixed clock when set (tests move it); the real one otherwise. */
        Long clock = null
        @Override
        protected AgentToolExecutor executor(RoleSpec spec, String conversationId) {
            executor = new CountingExecutor(spec, conversationId)
            return executor
        }
        @Override
        protected long now() { return clock != null ? clock : super.now() }
        @Override
        protected void pause(long ms) { pauses << ms; if (clock != null) clock += ms }
    }

    static AnthropicServiceExceptionFactory errors = new AnthropicServiceExceptionFactory()

    /** SDK service errors with a status and optional headers, built the SDK's way. */
    static class AnthropicServiceExceptionFactory {
        com.anthropic.errors.AnthropicServiceException status(int code, Map<String, String> headers = [:]) {
            Headers.Builder h = Headers.builder()
            headers.each { String k, String v -> h.put(k, v) }
            if (code == 429) return RateLimitException.builder().headers(h.build()).body(JsonValue.from([:])).build()
            if (code >= 500 && code != 529) return InternalServerException.builder().statusCode(code).headers(h.build()).body(JsonValue.from([:])).build()
            return com.anthropic.errors.UnexpectedStatusCodeException.builder().statusCode(code).headers(h.build()).body(JsonValue.from([:])).build()
        }
    }

    static Message msg(String stopReason, List<Map> content, Map usage = [input_tokens: 100, output_tokens: 20], String model = "claude-sonnet-5-5") {
        String json = JsonOutput.toJson([id: "msg_" + UUID.randomUUID().toString().take(8), type: "message", role: "assistant", model: model,
                content: content, stop_reason: stopReason, stop_sequence: null, usage: usage])
        return MAPPER.readValue(json, Message)
    }

    static Map toolUse(String id, String name, Map input) { [type: "tool_use", id: id, name: name, input: input] }

    static Map text(String t) { [type: "text", text: t] }

    static Map thinking(String signature) { [type: "thinking", thinking: "", signature: signature] }

    static RoleSpec investigator() { RoleSpec.investigator().copy(systemPromptPath: PROMPT) }

    static RoleSpec author() { RoleSpec.author().copy(systemPromptPath: PROMPT) }

    static List<MessageParam> ask(String words) { [MessageParam.ofUser(words)] }

    /** The request as the wire JSON the SDK would send. */
    static Map wire(MessageCreateParams params) { (Map) new JsonSlurper().parseText(MAPPER.writeValueAsString(params._body())) }

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-role-loop")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        [MINE, OTHER].each { String t ->
            upsert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(),
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("moqui.security.UserGroupMember", [userGroupId: t, userId: USER, fromDate: FROM])
            upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: t, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
        }
        upsert("darpan.agent.AgentConversation", [conversationId: CONV, companyUserGroupId: MINE, userId: USER, createdDate: FROM])
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ["darpan.agent.AgentTurn", "darpan.agent.AuditChangeProposal", "darpan.agent.BrainChangeTicket"].each { ec.entity.find(it).deleteAll() }
        actAs(MINE)
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearAll()
    }

    private void upsert(String entityName, Map<String, Object> fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }

    private String turn(RoleSpec spec) { AgentUsageLedger.startTurn(ec, MINE, CONV, spec.role, spec.model, null, null) }

    private RoleResult run(RoleSpec spec, ScriptedGateway gateway, CountingLoop loop = new CountingLoop(), List<MessageParam> messages = ask("Why is F-1 open?")) {
        return loop.runRole(ec, spec, MINE, messages, gateway, turn(spec))
    }

    private long count(String entityName) { ec.entity.find(entityName).useCache(false).count() }

    private def turnRow(String turnId) { ec.entity.find("darpan.agent.AgentTurn").condition("turnId", turnId).useCache(false).one() }

    // ------------------------------------------------------------------ tests

    @Test
    void "tool_use then end_turn returns DONE with one tool call"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [text("Looking it up."), toolUse("tu_1", "search_knowledge", [words: "carrier missing"])]),
                msg("end_turn", [text("F-1 is open because the carrier is missing.")])])
        def loop = new CountingLoop()
        RoleResult r = run(investigator(), gateway, loop)
        assertEquals("DONE", r.outcome)
        assertEquals(1, r.toolCalls.size())
        assertEquals("search_knowledge", r.toolCalls[0].name)
        assertFalse(r.toolCalls[0].isError as boolean, r.toolCalls[0].content as String)
        assertEquals(["facade.AgentReadFacadeServices.search#AgentKnowledge"], loop.executor.invoked)
        assertEquals("F-1 is open because the carrier is missing.", r.text())
        assertEquals(2, gateway.sent.size())
        List<Map> second = (List<Map>) wire(gateway.sent[1]).messages
        assertEquals(["user", "assistant", "user"], second*.role)
        assertEquals([[type: "tool_result", id: "tu_1"]], ((List<Map>) second[2].content).collect { [type: it.type, id: it.tool_use_id] })
    }

    @Test
    void "a tool outside the role's list is answered TOOL_NOT_PERMITTED and never runs"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "propose_fix_text", [savedRunId: "R1", conclusionCode: "C1",
                        fixActionTemplate: "Re-send it.", reasonText: "x", evidence: []])]),
                msg("end_turn", [text("ok")])])
        def loop = new CountingLoop()
        RoleResult r = run(investigator(), gateway, loop)
        assertEquals("DONE", r.outcome)
        assertTrue(r.toolCalls[0].isError as boolean)
        assertEquals("TOOL_NOT_PERMITTED", r.toolCalls[0].content)
        assertEquals([], loop.executor.invoked)
        assertEquals(0L, count("darpan.agent.AuditChangeProposal"))
        Map result = (Map) ((List<Map>) wire(gateway.sent[1]).messages)[2].content[0]
        assertEquals(true, result.is_error)
        assertEquals("TOOL_NOT_PERMITTED", result.content)
    }

    @Test
    void "apply#AuditChange is not callable from any role"() {
        List<String> approving = ["apply#AuditChange", "reject#AuditChange", "revert#AuditChange", "plan#AuditChange"]
        [RoleSpec.router(), RoleSpec.investigator(), RoleSpec.author()].each { RoleSpec spec ->
            spec.tools.each { String tool ->
                String service = AgentToolExecutor.serviceOf(tool)
                assertFalse(approving.any { service?.endsWith(it) }, "${spec.role} ${tool} -> ${service}")
            }
        }
        assertFalse(AgentToolExecutor.TOOLS.values().any { Map t -> approving.any { a -> (t.service as String)?.endsWith(a) } })
        approving.each { String name ->
            assertThrows(IllegalArgumentException) { RoleSpec.author().copy(tools: ["propose_fix_text", name]) }
        }
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "apply#AuditChange", [proposalId: "P1", planHash: "h"])]),
                msg("end_turn", [text("ok")])], )
        def loop = new CountingLoop()
        RoleResult r = run(author(), gateway, loop)
        assertEquals("TOOL_NOT_PERMITTED", r.toolCalls[0].content)
        assertEquals([], loop.executor.invoked)
    }

    @Test
    void "the same call three times ends LOOP"() {
        Map same = [words: "carrier"]
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", same)]),
                msg("tool_use", [toolUse("tu_2", "search_knowledge", same)]),
                msg("tool_use", [toolUse("tu_3", "search_knowledge", [words: "carrier"])])])
        def loop = new CountingLoop()
        RoleResult r = run(investigator(), gateway, loop)
        assertEquals("LOOP", r.outcome)
        assertEquals(2, loop.executor.invoked.size(), "the third identical call never runs")
    }

    @Test
    void "maxToolCalls ends BUDGET"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"]), toolUse("tu_2", "search_knowledge", [words: "b"]),
                                 toolUse("tu_3", "search_knowledge", [words: "c"])])])
        def loop = new CountingLoop()
        RoleResult r = run(investigator().copy(maxToolCalls: 2), gateway, loop)
        assertEquals("BUDGET", r.outcome)
        assertEquals(2, loop.executor.invoked.size())
    }

    @Test
    void "maxRequests ends BUDGET"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])]),
                msg("tool_use", [toolUse("tu_2", "search_knowledge", [words: "b"])])])
        RoleResult r = run(investigator().copy(maxRequests: 2), gateway)
        assertEquals("BUDGET", r.outcome)
        assertEquals(2, gateway.sent.size())
    }

    @Test
    void "the last allowed request tells the model no more tools will run, so it answers from what it read"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])]),
                msg("tool_use", [toolUse("tu_2", "search_knowledge", [words: "b"])]),
                msg("end_turn", [text("Answer from what was read.")])])
        RoleResult r = run(investigator().copy(maxRequests: 3), gateway)
        assertEquals("DONE", r.outcome)
        List<Map> second = (List<Map>) wire(gateway.sent[1]).messages
        assertFalse(second.last().content.any { it.type == "text" }, "a request that is not the last carries tool results only")
        List<Map> last = (List<Map>) wire(gateway.sent[2]).messages.last().content
        assertEquals(["tool_result", "text"], last*.type, "tool results first, then the notice")
        assertEquals(AgentRoleLoop.LAST_REQUEST_NOTICE, last[1].text)
    }

    @Test
    void "pause_turn is resent"() {
        def gateway = new ScriptedGateway([
                msg("pause_turn", [text("Still working.")]),
                msg("end_turn", [text("Done.")])])
        RoleResult r = run(investigator(), gateway)
        assertEquals("DONE", r.outcome)
        assertEquals(2, gateway.sent.size())
        List<Map> second = (List<Map>) wire(gateway.sent[1]).messages
        assertEquals(["user", "assistant"], second*.role, "the paused content goes back as is, with no extra user message")
        assertEquals("Still working.", second[1].content[0].text)
    }

    @Test
    void "max_tokens ends MAX_TOKENS with no tool executed"() {
        def gateway = new ScriptedGateway([msg("max_tokens", [text("Let me"), toolUse("tu_1", "search_knowledge", [words: "a"])])])
        def loop = new CountingLoop()
        RoleResult r = run(investigator(), gateway, loop)
        assertEquals("MAX_TOKENS", r.outcome)
        assertEquals([], r.toolCalls)
        assertEquals([], loop.executor.invoked)
    }

    @Test
    void "refusal ends REFUSED"() {
        def gateway = new ScriptedGateway([msg("refusal", [])])
        RoleResult r = run(investigator(), gateway)
        assertEquals("REFUSED", r.outcome)
        assertEquals(1, gateway.sent.size())
    }

    @Test
    void "tenant switched between rounds ends TENANT_CHANGED before the next tool"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])]),
                { actAs(OTHER); msg("tool_use", [toolUse("tu_2", "search_knowledge", [words: "b"])]) }])
        def loop = new CountingLoop()
        try {
            RoleResult r = run(investigator(), gateway, loop)
            assertEquals("TENANT_CHANGED", r.outcome)
            assertEquals(1, loop.executor.invoked.size(), "no tool reads the new tenant")
            // and the executor itself refuses when the active tenant is not the pinned one
            assertThrows(TenantChangedException) { new AgentToolExecutor(investigator(), CONV).execute(ec, MINE, "search_knowledge", [words: "c"]) }
        } finally {
            actAs(MINE)
        }
        def again = new ScriptedGateway([msg("end_turn", [text("ok")])])
        String turnId = turn(investigator())
        actAs(OTHER)
        try {
            assertThrows(TenantChangedException) { AgentUsageLedger.startTurn(ec, MINE, CONV, "INVESTIGATOR", "claude-sonnet-5-5", null, null) }
            RoleResult r2 = new CountingLoop().runRole(ec, investigator(), MINE, ask("x"), again, turnId)
            assertEquals("TENANT_CHANGED", r2.outcome, "a turn whose tenant is no longer active sends nothing")
            assertEquals(0, again.sent.size())
        } finally {
            actAs(MINE)
        }
    }

    @Test
    void "gateway 503 ends UNAVAILABLE and leaves no proposal row"() {
        def e503 = InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).body(JsonValue.from([:])).build()
        String turnId = turn(author())
        def loop503 = new CountingLoop()
        RoleResult r = loop503.runRole(ec, author(), MINE, ask("Exclude the test orders."), new ScriptedGateway([e503, e503, e503]), turnId)
        assertEquals("UNAVAILABLE", r.outcome, "two retries, then unavailable")
        assertEquals([500L, 1000L], loop503.pauses)
        assertEquals(0L, count("darpan.agent.AuditChangeProposal"))
        assertEquals(0L, count("darpan.agent.BrainChangeTicket"))
        assertEquals("UNAVAILABLE", ec.entity.find("darpan.agent.AgentTurn").condition("turnId", turnId).useCache(false).one().outcome)

        def e429 = RateLimitException.builder().headers(Headers.builder().build()).body(JsonValue.from([:])).build()
        assertEquals("UNAVAILABLE", run(author(), new ScriptedGateway([e429, e429, e429])).outcome)
        assertEquals("UNAVAILABLE", run(author(), new ScriptedGateway([new darpan.agent.claude.ModelUnavailableException("No Claude API key is configured")])).outcome)
        assertEquals(0L, count("darpan.agent.AuditChangeProposal"))
    }

    @Test
    void "a 400 is a programming error, is not hidden as UNAVAILABLE, and closes the turn as ERROR"() {
        def e400 = BadRequestException.builder().headers(Headers.builder().build()).body(JsonValue.from([:])).build()
        String turnId = turn(investigator())
        assertThrows(BadRequestException) { new CountingLoop().runRole(ec, investigator(), MINE, ask("x"), new ScriptedGateway([e400]), turnId) }
        def row = turnRow(turnId)
        assertEquals("ERROR", row.outcome)
        assertNotNull(row.endedDate)
    }

    @Test
    void "a missing or unknown stop reason ends ERROR, stop_sequence ends DONE"() {
        assertEquals("ERROR", run(investigator(), new ScriptedGateway([msg(null, [text("half")])])).outcome)
        assertEquals("ERROR", run(investigator(), new ScriptedGateway([msg("something_new", [text("?")])])).outcome)
        assertEquals("DONE", run(investigator(), new ScriptedGateway([msg("stop_sequence", [text("ok")])])).outcome)
    }

    @Test
    void "a tenant switched between requests still has both requests priced on the pinned tenant's turn"() {
        Map usage = [input_tokens: 1000, output_tokens: 200]
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])], usage),
                { actAs(OTHER); msg("tool_use", [toolUse("tu_2", "search_knowledge", [words: "b"])], usage) }])
        String turnId = turn(investigator())
        try {
            RoleResult r = new CountingLoop().runRole(ec, investigator(), MINE, ask("x"), gateway, turnId)
            assertEquals("TENANT_CHANGED", r.outcome)
            def row = turnRow(turnId)
            assertEquals(2L, row.requestCount as long)
            assertEquals(8000L, row.costMicros as long, "1000*2 + 200*10 twice")
            assertEquals("TENANT_CHANGED", row.outcome)
            assertEquals(MINE, row.companyUserGroupId)
            assertEquals(8000L, AgentUsageLedger.todayCostMicros(ec, MINE), "counted for the pinned tenant while another is active")
        } finally {
            actAs(MINE)
        }
    }

    @Test
    void "a tool reports only the errors it added and earlier errors survive it"() {
        ec.message.addError("EARLIER")
        try {
            def ex = new AgentToolExecutor(investigator(), CONV)
            Map out = ex.execute(ec, MINE, "get_audit_settings", [audit: "no-such-audit"])
            assertTrue(out.isError as boolean)
            assertTrue((out.content as String).contains("no-such-audit"), out.content as String)
            assertFalse((out.content as String).contains("EARLIER"), out.content as String)
            assertEquals(["EARLIER"], ec.message.errors)
            Map ok = ex.execute(ec, MINE, "search_knowledge", [words: "carrier"])
            assertFalse(ok.isError as boolean, ok.content as String)
            assertEquals(["EARLIER"], ec.message.errors)
        } finally {
            ec.message.clearErrors()
        }
    }

    @Test
    void "a catalog entry for a service that is not get, find, inspect, search or create refuses to load"() {
        Map schema = [type: "object", additionalProperties: false, properties: [:]]
        ["facade.AgentChangeFacadeServices.Apply#AuditChange", "facade.AgentChangeFacadeServices.apply#AuditChange",
         "facade.AgentChangeFacadeServices.REVERT#AuditChange", "facade.X.update#Thing", "facade.X.delete#Thing", "noVerbHere"].each { String svc ->
            assertThrows(IllegalStateException, { AgentToolExecutor.parseCatalog([[name: "t", service: svc, input_schema: schema]]) }, svc)
        }
        assertEquals(["t"], AgentToolExecutor.parseCatalog([[name: "t", service: "facade.X.Get#Thing", input_schema: schema]]).keySet().toList())
        assertThrows(IllegalStateException) { AgentToolExecutor.parseCatalog([[name: "t", service: "facade.X.get#A", control: "QUESTION"]]) }
    }

    @Test
    void "a control tool before another call answers the later call NOT_RUN without running it"() {
        def gateway = new ScriptedGateway([msg("tool_use", [
                toolUse("tu_1", "ask_human", [question: "Which store?", options: []]),
                toolUse("tu_2", "propose_fix_text", [savedRunId: "R1", conclusionCode: "C1",
                        fixActionTemplate: "Re-send it.", reasonText: "x", evidence: []])])])
        def loop = new CountingLoop()
        RoleResult r = run(author(), gateway, loop)
        assertEquals("QUESTION", r.outcome)
        assertEquals("tu_1", r.control.toolUseId)
        assertEquals([], loop.executor.invoked, "propose_fix_text never runs")
        assertEquals(0L, count("darpan.agent.AuditChangeProposal"))
        assertEquals(1, r.pendingToolResults.size())
        def pending = r.pendingToolResults[0].asToolResult()
        assertEquals("tu_2", pending.toolUseId())
        assertTrue(pending.isError().orElse(false))
        assertEquals("NOT_RUN: step ended on ask_human", pending.content().get().asString())
    }

    @Test
    void "usage is priced and recorded per request"() {
        Map usage = [input_tokens: 1000, output_tokens: 200, cache_read_input_tokens: 5000, cache_creation_input_tokens: 400]
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])], usage),
                msg("end_turn", [text("ok")], usage)])
        String turnId = turn(investigator())
        RoleResult r = new CountingLoop().runRole(ec, investigator(), MINE, ask("x"), gateway, turnId)
        assertEquals("DONE", r.outcome)
        def row = ec.entity.find("darpan.agent.AgentTurn").condition("turnId", turnId).useCache(false).one()
        assertEquals(2L, row.requestCount as long)
        assertEquals(2000L, row.inputTokens as long, "uncached input only")
        assertEquals(800L, row.cacheWriteTokens as long, "cache writes in their own column, so a hit rate can be read off the rows")
        assertEquals(400L, row.outputTokens as long)
        assertEquals(10000L, row.cacheReadTokens as long)
        // sonnet-5-5 2/10/0.20 per MTok, writes 1.25 x input: 1000*2 + 400*2.5 + 200*10 + 5000*0.20 = 6000 micro-dollars a request
        assertEquals(12000L, row.costMicros as long)
        assertEquals("end_turn", row.stopReason)
        assertEquals("DONE", row.outcome)
        assertNotNull(row.endedDate)
        assertEquals(MINE, row.companyUserGroupId)
        assertEquals(12000L, AgentUsageLedger.todayCostMicros(ec, MINE))
        assertEquals(0L, AgentUsageLedger.todayCostMicros(ec, OTHER))

        Usage u = MAPPER.readValue(JsonOutput.toJson(usage), Usage)
        assertEquals(11000L, AgentUsageLedger.costMicros("claude-opus-5-5", u))
        assertEquals(6000L, AgentUsageLedger.costMicros("claude-sonnet-5-5", u))
        assertEquals(300L, AgentUsageLedger.costMicros("claude-haiku-5-5", u))
    }

    // ------------------------------------------------------------------ DAR-BE-194: the grant inside a role

    private String grantFor(Long capMicros) {
        return asSystem {
            def g = ec.entity.makeValue("darpan.agent.AgentAccessGrant").setAll([userId: USER, companyUserGroupId: MINE,
                    fromDate: new Timestamp(System.currentTimeMillis() - 3_600_000L), statusId: "ACTIVE", costCapMicros: capMicros])
            g.setSequencedIdPrimary()
            g.create()
            return g.grantId as String
        }
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private void dropGrants() {
        asSystem { ["darpan.agent.AgentAccessGrantEvent", "darpan.agent.AgentAccessGrant"].each { ec.entity.find(it).deleteAll() } }
    }

    @Test
    void "a grant that reaches its limit stops the role before its next request"() {
        // Each scripted Sonnet request costs 400 micro-dollars (100 in x 2 + 20 out x 10); the grant allows 300.
        String grantId = grantFor(300L)
        try {
            def gateway = new ScriptedGateway([msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])]), msg("end_turn", [text("never sent")])])
            String turnId = AgentUsageLedger.startTurn(ec, MINE, CONV, "INVESTIGATOR", "claude-sonnet-5-5", USER, grantId)
            RoleResult r = new CountingLoop().runRole(ec, investigator(), MINE, ask("x"), gateway, turnId)
            assertEquals("NO_ACCESS", r.outcome)
            assertEquals(1, gateway.sent.size(), "the second request is never sent")
            assertEquals("PAUSED", asSystem { ec.entity.find("darpan.agent.AgentAccessGrant").condition("grantId", grantId).useCache(false).one().statusId })
        } finally {
            dropGrants()
        }
    }

    @Test
    void "a pause that fails never loses the request's recorded cost"() {
        String grantId = grantFor(300L)
        Closure original = AgentUsageLedger.pauser
        AgentUsageLedger.pauser = { def e, String id -> throw new IllegalStateException("pause write failed") }
        try {
            String turnId = AgentUsageLedger.startTurn(ec, MINE, CONV, "INVESTIGATOR", "claude-sonnet-5-5", USER, grantId)
            Usage u = MAPPER.readValue('{"input_tokens":100,"output_tokens":20}', Usage)
            assertEquals(400L, AgentUsageLedger.record(ec, MINE, turnId, u, "claude-sonnet-5-5"))
            assertEquals(400L, turnRow(turnId).costMicros as long, "the cost stays on the ledger, so the caps still count it")
            assertFalse(ec.message.hasError(), "a failed pause leaves no error behind for the turn: ${ec.message.errorsString}")
            assertEquals(darpan.agent.AgentAccess.PAUSED, darpan.agent.AgentAccess.access(ec, MINE, USER).state, "and access still reads the limit from spend")
        } finally {
            AgentUsageLedger.pauser = original
            dropGrants()
        }
    }

    @Test
    void "the request prefix is byte-identical across two turns"() {
        def first = new ScriptedGateway([msg("end_turn", [text("one")])])
        def second = new ScriptedGateway([msg("end_turn", [text("two")])])
        run(author(), first, new CountingLoop(), ask("First question about F-1"))
        run(author(), second, new CountingLoop(), ask("A different question about F-2"))
        MessageCreateParams a = first.sent[0], b = second.sent[0]
        assertEquals(MAPPER.writeValueAsString(a.tools().get()), MAPPER.writeValueAsString(b.tools().get()))
        assertEquals(MAPPER.writeValueAsString(a.system().get()), MAPPER.writeValueAsString(b.system().get()))
        assertNotEquals(MAPPER.writeValueAsString(a.messages()), MAPPER.writeValueAsString(b.messages()))

        Map w = wire(a)
        List<Map> tools = (List<Map>) w.tools
        assertEquals(tools*.name.sort(false), tools*.name, "tools sorted by name")
        assertEquals(RoleSpec.author().tools.sort(false), tools*.name)
        // Tools render before system, so the system prompt's marker caches both; a marker on the last tool only spent one
        // of the four breakpoint slots.
        assertEquals([], tools.findAll { it.cache_control }, "no tool carries a cache marker")
        assertEquals([type: "ephemeral"], w.cache_control, "a role that sends more than one request caches its growing tail (automatic caching)")
        tools.each { Map t ->
            // raise_ticket goes without strict so the author's strict tools stay within the API's optional-parameter limit.
            assertEquals(t.name != "raise_ticket", t.strict ?: false, t.name as String)
            assertEquals(false, t.input_schema.additionalProperties, t.name as String)
        }
        assertEquals([type: "ephemeral"], ((List<Map>) w.system)[0].cache_control)
        assertEquals("claude-opus-5-5", w.model)
        assertEquals([type: "adaptive"], w.thinking)
        assertEquals("high", w.output_config.effort)
        assertNull(w.tool_choice, "tool_choice stays auto")
        assertEquals(JsonValue.from("default"), a._additionalBodyProperties().get("fallbacks"))
        assertEquals(["server-side-fallback-2026-07-01"], a._additionalHeaders().values("anthropic-beta"))
    }

    @Test
    void "the router sends no thinking, no tools, no fallbacks and its output schema"() {
        Map schema = [type: "object", additionalProperties: false, required: ["items"], properties: [items: [type: "array", items: [type: "string"]]]]
        def gateway = new ScriptedGateway([msg("end_turn", [text('{"items":[]}')], [input_tokens: 10, output_tokens: 5], "claude-haiku-5-5")])
        RoleResult r = run(RoleSpec.router(schema).copy(systemPromptPath: PROMPT), gateway)
        assertEquals("DONE", r.outcome)
        Map w = wire(gateway.sent[0])
        assertEquals("claude-haiku-5-5", w.model)
        assertNull(w.thinking)
        assertNull(w.tools)
        assertEquals("low", w.output_config.effort)
        assertEquals([type: "json_schema", schema: schema], w.output_config.format)
        assertNull(w.cache_control, "one request: a cached tail would be written at 1.25 x and never read")
        assertFalse(gateway.sent[0]._additionalBodyProperties().containsKey("fallbacks"))
        assertEquals([], gateway.sent[0]._additionalHeaders().values("anthropic-beta"))
    }

    @Test
    void "fallbacks can be switched off by property"() {
        System.setProperty(AgentRoleLoop.FALLBACKS_PROPERTY, "false")
        try {
            def gateway = new ScriptedGateway([msg("end_turn", [text("ok")])])
            run(investigator(), gateway)
            assertFalse(gateway.sent[0]._additionalBodyProperties().containsKey("fallbacks"))
        } finally {
            System.clearProperty(AgentRoleLoop.FALLBACKS_PROPERTY)
        }
    }

    @Test
    void "thinking blocks go back unchanged and one round's tool results share one user message"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [thinking("SIG-1"), toolUse("tu_1", "search_knowledge", [words: "a"]), toolUse("tu_2", "search_knowledge", [words: "b"])]),
                msg("end_turn", [text("ok")])])
        run(investigator(), gateway)
        List<Map> second = (List<Map>) wire(gateway.sent[1]).messages
        assertEquals(3, second.size())
        assertEquals([type: "thinking", thinking: "", signature: "SIG-1"], second[1].content[0])
        assertEquals(["tu_1", "tu_2"], ((List<Map>) second[2].content)*.tool_use_id)
    }

    @Test
    void "tool input that does not match its schema is INVALID_INPUT and never runs"() {
        def gateway = new ScriptedGateway([
                msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: 5]), toolUse("tu_2", "get_finding", [findingId: "F-1", tenant: "OTHER"]),
                                 toolUse("tu_3", "find_lookalikes", [limit: 3])]),
                msg("end_turn", [text("ok")])])
        def loop = new CountingLoop()
        RoleResult r = run(investigator(), gateway, loop)
        assertEquals(["INVALID_INPUT"] * 3, r.toolCalls*.content.collect { (it as String).split(":")[0] })
        assertTrue(r.toolCalls.every { it.isError })
        assertEquals([], loop.executor.invoked)
    }

    @Test
    void "every tool runs inside the agent-call marker"() {
        def gateway = new ScriptedGateway([msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])]), msg("end_turn", [text("ok")])])
        def loop = new CountingLoop()
        assertFalse(AgentCallMarker.inAgentLoop())
        run(investigator(), gateway, loop)
        assertEquals([true], loop.executor.markerSeen)
        assertFalse(AgentCallMarker.inAgentLoop())
    }

    @Test
    void "a 529 before any streamed byte is retried and the retry succeeds"() {
        def loop = new CountingLoop()
        def gateway = new ScriptedGateway([errors.status(529), msg("end_turn", [text("Done after a retry.")])])
        RoleResult r = run(investigator(), gateway, loop)
        assertEquals("DONE", r.outcome)
        assertEquals(2, gateway.sent.size())
        assertEquals([500L], loop.pauses, "the first backoff")
        assertEquals(1, r.requestCount, "only the answered request counts")

        def honoured = new CountingLoop()
        def limited = new ScriptedGateway([errors.status(429, ["retry-after-ms": "1200"]), errors.status(503, ["retry-after": "2"]),
                                           msg("end_turn", [text("Done.")])])
        assertEquals("DONE", run(investigator(), limited, honoured).outcome)
        assertEquals([1200L, 2000L], honoured.pauses, "Retry-After-Ms, then Retry-After in seconds")
        assertEquals(3, limited.sent.size())

        def io = new CountingLoop()
        def dropped = new ScriptedGateway([new com.anthropic.errors.AnthropicIoException("connection reset", null), msg("end_turn", [text("Done.")])])
        assertEquals("DONE", run(investigator(), dropped, io).outcome)
        assertEquals(2, dropped.sent.size(), "a connection error before streaming is retried")
    }

    @Test
    void "a stream that fails after it started is not retried and its usage is recorded"() {
        def usage = MAPPER.readValue('{"input_tokens":1000,"output_tokens":50,"cache_read_input_tokens":200}', com.anthropic.models.messages.Usage)
        def loop = new CountingLoop()
        def gateway = new ScriptedGateway([new darpan.agent.claude.StreamInterruptedException(usage, "claude-opus-5-5", new IOException("stream reset")),
                                           msg("end_turn", [text("never sent")])])
        String turnId = turn(author())
        RoleResult r = loop.runRole(ec, author(), MINE, ask("x"), gateway, turnId)
        assertEquals("UNAVAILABLE", r.outcome)
        assertEquals(1, gateway.sent.size(), "never retried once streaming started")
        assertEquals([], loop.pauses)
        def row = turnRow(turnId)
        assertEquals(1L, row.requestCount as long)
        assertEquals(1000L, row.inputTokens as long)
        assertEquals(50L, row.outputTokens as long)
        assertEquals(200L, row.cacheReadTokens as long)
        assertEquals(AgentUsageLedger.costMicros("claude-opus-5-5", usage), row.costMicros as long, "priced as usual, so the daily cap counts it")
        assertEquals("UNAVAILABLE", row.outcome)
    }

    @Test
    void "each attempt is given all the time left before the deadline"() {
        def loop = new CountingLoop(clock: 1_000_000L)
        def gateway = new ScriptedGateway([
                { loop.clock += 30_000L; msg("tool_use", [toolUse("tu_1", "search_knowledge", [words: "a"])]) },
                { loop.clock += 20_000L; errors.status(529) },
                msg("end_turn", [text("Done.")])])
        RoleResult r = run(author().copy(wallClockMs: 120_000L), gateway, loop)
        assertEquals("DONE", r.outcome)
        assertEquals([120_000L, 90_000L, 69_500L], gateway.timeouts*.toMillis(),
                "the whole 120 s, then what is left after 30 s, then after a 20 s failure and its 0.5 s backoff")
    }

    @Test
    void "no retry starts when too little would be left after the wait"() {
        def loop = new CountingLoop(clock: 1_000_000L)
        def gateway = new ScriptedGateway([{ loop.clock += 15_000L; errors.status(529) }, msg("end_turn", [text("never sent")])])
        assertEquals("UNAVAILABLE", run(investigator().copy(wallClockMs: 20_000L), gateway, loop).outcome)
        assertEquals(1, gateway.sent.size(), "5 s left minus the backoff is under the 5 s minimum")
        assertEquals([], loop.pauses)

        def late = new CountingLoop(clock: 1_000_000L)
        def busy = new ScriptedGateway([errors.status(429, ["retry-after": "30"]), msg("end_turn", [text("never sent")])])
        assertEquals("UNAVAILABLE", run(investigator().copy(wallClockMs: 20_000L), busy, late).outcome)
        assertEquals(1, busy.sent.size(), "a Retry-After past the deadline is not waited for")

        def none = new CountingLoop(clock: 1_000_000L)
        def unsent = new ScriptedGateway([msg("end_turn", [text("never sent")])])
        assertEquals("BUDGET", run(investigator().copy(wallClockMs: AgentRoleLoop.MIN_REQUEST_TIMEOUT_MS - 1), unsent, none).outcome)
        assertEquals(0, unsent.sent.size(), "with under 5 s left no request starts")
    }

    @Test
    void "ask_human ends the role with QUESTION and request_more_evidence with MORE_EVIDENCE"() {
        def q = new ScriptedGateway([msg("tool_use", [toolUse("tu_1", "ask_human", [question: "Which store?", options: ["UK", "EU"]])])])
        RoleResult asked = run(author(), q)
        assertEquals("QUESTION", asked.outcome)
        assertEquals([question: "Which store?", options: ["UK", "EU"]], asked.control.input)
        assertEquals("tu_1", asked.control.toolUseId)
        def more = new ScriptedGateway([msg("tool_use", [toolUse("tu_9", "request_more_evidence", [need: "the refund date"])])])
        RoleResult needed = run(author(), more)
        assertEquals("MORE_EVIDENCE", needed.outcome)
        assertEquals([need: "the refund date"], needed.control.input)
    }
}
