package darpan.agent

import darpan.agent.claude.AgentClientFactory
import darpan.agent.loop.AgentRoleLoopTests
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static darpan.agent.loop.AgentRoleLoopTests.msg
import static darpan.agent.loop.AgentRoleLoopTests.text
import static darpan.agent.loop.AgentRoleLoopTests.toolUse
import static org.junit.jupiter.api.Assertions.*
import static org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * DAR-BE-159 Task 9. facade.AgentTurnFacadeServices.send#AgentTurn as a real user on a thread of its own, authz ON (no
 * disableAuthz on that thread's path): the session, the switch, and the tenant pinned through a switch in another tab (R34).
 * The model is a scripted gateway set for that thread only. Tenants are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentFacadeSmokeTests {

    private static final String SERVICE = "facade.AgentTurnFacadeServices.send#AgentTurn"
    private static final String MINE = "AGF_TENANT_A"
    private static final String OTHER = "AGF_TENANT_B"
    private static final String USERNAME = "test.customer"
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String AUDIT = "AGF_AUDIT"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final List<String> AGENT_ENTITIES = ["darpan.agent.AgentMessage", "darpan.agent.AgentTurn", "darpan.agent.AuditChangeProposal",
            "darpan.agent.BrainChangeTicket", "darpan.agent.AgentConversation"]

    private ExecutionContext ec
    private String previousEnabled

    @BeforeAll
    void setup() {
        previousEnabled = System.getProperty(AgentSwitch.ENABLED_PROPERTY)
        System.setProperty(AgentSwitch.ENABLED_PROPERTY, "true")
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-facade")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: USER, fromDate: FROM])
            [MINE, OTHER].each { String t ->
                upsert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(), groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
                upsert("moqui.security.UserGroupMember", [userGroupId: t, userId: USER, fromDate: FROM])
                upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: t, userId: USER,
                        permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
                upsert("darpan.audit.Audit", [auditId: "${AUDIT}_${t}".toString(), companyUserGroupId: t, auditCode: "month-close",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            }
            upsert("darpan.audit.Audit", [auditId: AUDIT, companyUserGroupId: MINE, auditCode: "weekly", auditName: "Weekly", periodEnumId: "AUDP_MONTH"])
        }
    }

    @AfterAll
    void cleanup() {
        if (previousEnabled == null) System.clearProperty(AgentSwitch.ENABLED_PROPERTY) else System.setProperty(AgentSwitch.ENABLED_PROPERTY, previousEnabled)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        asSystem {
            AGENT_ENTITIES.each { ec.entity.find(it).deleteAll() }
            ["darpan.agent.AgentAccessGrantEvent", "darpan.agent.AgentAccessGrant"].each { ec.entity.find(it).deleteAll() }
            [MINE, OTHER].each { upsert("darpan.auth.TenantSetting", [companyUserGroupId: it, agentEnabled: "Y"]) }
            // DAR-BE-194: the person has AI access in both tenants (a tab switch mid-turn must still be the one that stops it).
            [MINE, OTHER].each { String t ->
                upsert("darpan.agent.AgentAccessGrant", [grantId: "AFS_G_${t}".toString(), userId: USER, companyUserGroupId: t,
                        fromDate: FROM, statusId: "ACTIVE"])
            }
        }
    }

    @Test
    void "send#AgentTurn needs a session and a tenant with the agent on"() {
        Map params = [auditId: AUDIT, text: "Why is this one open?"]

        Map anonymous = onThread(null, MINE, params, null)
        assertTrue(anonymous.failure != null || anonymous.hasError, "no session is refused: ${anonymous}")
        assertNull(anonymous.result?.outcome)

        asSystem { upsert("darpan.auth.TenantSetting", [companyUserGroupId: MINE, agentEnabled: "N"]) }
        Map off = onThread(USERNAME, MINE, params, null)
        assertNull(off.failure, String.valueOf(off.failure))
        assertFalse(off.hasError as boolean, off.errors.toString())
        assertEquals("AGENT_OFF", off.result.outcome)

        // With the agent on and a model there, another tenant's audit reads as not found and no model is called.
        def idle = new AgentRoleLoopTests.ScriptedGateway([])
        Map foreign = onThread(USERNAME, OTHER, params, idle)
        assertEquals(0, idle.sent.size())
        assertTrue(foreign.hasError as boolean, "another tenant's audit reads as not found")
        assertTrue((foreign.errors as List).any { (it as String).contains("was not found") }, foreign.errors.toString())

    }

    @Test
    void "on with no key, send#AgentTurn answers AGENT_UNAVAILABLE and writes nothing"() {
        assumeTrue(AgentClientFactory.resolveKey() == null, "an Anthropic key is configured on this machine; the no-key case cannot be shown")
        Map noKey = onThread(USERNAME, MINE, [auditId: AUDIT, text: "Why is this one open?"], null)
        assertNull(noKey.failure, String.valueOf(noKey.failure))
        assertFalse(noKey.hasError as boolean, noKey.errors.toString())
        assertEquals("AGENT_UNAVAILABLE", noKey.result.outcome, "on, with no key, the agent is unavailable and no model is called")
        AGENT_ENTITIES.each { assertEquals(0L, count(it), "${it}: nothing is written before the agent can run") }
    }

    @Test
    void "a tenant switched in another tab mid-turn ends TENANT_CHANGED with every row on the pinned tenant"() {
        String words = "Why is this one open?"
        def g = new AgentRoleLoopTests.ScriptedGateway([
                msg("end_turn", [text(JsonOutput.toJson([items: [[kind: "EXPLAIN", span: words, refs: [findingIds: [], orderKeys: [], statementCodes: []],
                        confidence: 0.9]], question: ""]))], [input_tokens: 50, output_tokens: 10], "claude-haiku-5-5"),
                {
                    // Another tab moves the person to the other tenant while the investigator's first request is out.
                    ExecutionContext tec = Moqui.getExecutionContext()
                    tec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OTHER)
                    TenantAccessSupport.syncUserContext(tec)
                    msg("tool_use", [toolUse("tu_1", "get_audit_settings", [audit: "month-close"])])
                }])
        Map out = onThread(USERNAME, MINE, [auditId: AUDIT, text: words], g)
        assertNull(out.failure, String.valueOf(out.failure))
        assertFalse(out.hasError as boolean, out.errors.toString())
        assertEquals("TENANT_CHANGED", out.result.outcome)
        assertEquals(2, g.sent.size(), "the tool never ran for the new tenant and nothing more was sent")

        List turns = asSystem { ec.entity.find("darpan.agent.AgentTurn").useCache(false).list() }
        assertEquals(["INVESTIGATOR", "ROUTER"], turns*.role.sort())
        assertTrue(turns.every { it.companyUserGroupId == MINE }, turns*.companyUserGroupId.toString())
        assertEquals("TENANT_CHANGED", turns.find { it.role == "INVESTIGATOR" }.outcome)
        assertEquals([1L, 1L], turns.collect { (it.requestCount ?: 0L) as long }, "both requests are priced on the pinned tenant's turns")
        List msgs = asSystem { ec.entity.find("darpan.agent.AgentMessage").orderBy("sequenceNum").useCache(false).list() }
        assertTrue(msgs*.kind.containsAll(["USER", "ASSISTANT", "OPERATOR"]), msgs*.kind.toString())
        assertTrue(msgs.every { it.companyUserGroupId == MINE }, msgs*.companyUserGroupId.toString())
        assertTrue(msgs.find { it.kind == "OPERATOR" }.contentJson.contains("TENANT_CHANGED"))
        AGENT_ENTITIES.each { String name ->
            assertEquals(0L, asSystem { ec.entity.find(name).condition("companyUserGroupId", OTHER).useCache(false).count() }, "${name} on the other tenant")
        }
        assertFalse(asSystem { ec.entity.find("darpan.agent.AgentMessage").condition("kind", "TOOL_RESULT").useCache(false).count() } > 0,
                "no tool ran after the switch")
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One send#AgentTurn on a fresh thread with authz ON (the BrainChangeTicketSmokeTests pattern), logged in as username
     * (none: no session) with tenant active; gateway, when given, is that thread's model.
     */
    private Map onThread(String username, String tenant, Map params, AgentRoleLoopTests.ScriptedGateway gateway) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (username) {
                    if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                    tec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
                    TenantAccessSupport.syncUserContext(tec)
                }
                Closure call = { tec.service.sync().name(SERVICE).parameters(new LinkedHashMap(params)).call() }
                holder.result = gateway == null ? call.call() :
                        AgentOrchestrator.overrideForThread(gateway, AgentOrchestratorTests.AVAILABLE, call)
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "agent-facade-${username}")
        worker.start()
        worker.join()
        return holder
    }

    private long count(String entityName) { asSystem { ec.entity.find(entityName).useCache(false).count() } }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
