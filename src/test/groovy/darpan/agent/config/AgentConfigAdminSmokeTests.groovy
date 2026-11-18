package darpan.agent.config

import darpan.admin.AuditLogTestSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.impl.service.ServiceFacadeImpl

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-307 Task 6: admin.AgentConfigAdminServices through the real service engine, authz ON, as a real DARPAN_SUPER_ADMIN on a
 * thread of its own. Synthetic tenant and users. Refusals save nothing; every write is audited and live on the next read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentConfigAdminSmokeTests {
    private static final String S = "admin.AgentConfigAdminServices."
    private static final String SUPER_ID = "AGCA_SUPER"
    private static final String SUPER = "agca.super"
    private static final String EDITOR = "test.customer"
    private static final String TENANT = "AGCA_ACME"
    private static final String CAP_KEY = "darpan.agent.dailyUsdCapPerTenant"
    private static final Timestamp FROM = Timestamp.valueOf("2026-01-01 00:00:00")
    private static final List<String> METHODS = ["get#AgentConfig", "get#AgentPrompt", "update#AgentPrompt", "update#AgentRoleModel",
            "update#AgentModelPrice", "update#AgentText", "update#AgentTenantDailyCap", "reset#AgentConfigItem"]

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "agent-config-admin")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserAccount", [userId: SUPER_ID, username: SUPER, userFullName: SUPER, emailAddress: "agca.super@example.com",
                    disabled: "N", requirePasswordChange: "N"])
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID, fromDate: FROM])
            upsert("moqui.security.UserGroup", [userGroupId: TENANT, description: "Acme", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("darpan.auth.TenantSetting", [companyUserGroupId: TENANT, timeZone: "UTC", disabled: "N"])
        }
    }

    @AfterAll
    void cleanup() {
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void fresh() {
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
        AgentConfigTestSupport.clear(ec)
        AuditLogTestSupport.wipe(ec)
        ec.message.clearErrors()
    }

    @AfterEach
    void after() {
        AgentConfigTestSupport.clear(ec)
        AgentConfig.resetForTest()
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("get shows defaults with sources, usedBy and the global cap")
    void getShowsDefaults() {
        Map c = ok(svc("get#AgentConfig", [:])).result.config
        assertEquals(AgentConfigDefaults.PROMPT_PATHS.keySet().toList(), c.prompts*.promptId)
        assertTrue(c.prompts.every { !it.edited && !it.defaultChangedSinceEdit && it.chars > 0 })
        Map router = c.roles.find { it.roleId == "ROUTER" }
        assertEquals(["claude-sonnet-5-5", "claude-sonnet-5-5", false], [router.modelId, router.defaultModelId, router.edited])
        Map sonnet = c.prices.find { it.modelId == "claude-sonnet-5-5" }
        assertEquals("DEFAULT", sonnet.source)
        assertEquals(["ROUTER", "INVESTIGATOR", "AUTHOR"] as Set, sonnet.usedBy as Set)
        assertEquals(0, new BigDecimal("2.0").compareTo(sonnet.inputUsdPerMTok as BigDecimal))
        assertEquals(["AUTHOR_ESCALATION"], c.prices.find { it.modelId == "claude-opus-5-5" }.usedBy)
        Map cap = c.globalDailyUsdCap
        assertEquals(["25", "DEFAULT"], [cap.effectiveValue, cap.source])
        assertEquals(0, new BigDecimal("0.01").compareTo(cap.min as BigDecimal))
        assertEquals(0, new BigDecimal("1000").compareTo(cap.max as BigDecimal))
        assertEquals([], c.tenantCaps)
        Map t = c.texts.find { it.textId == "reply.RATE_LIMITED" }
        assertEquals(["count"], t.tokens)
        assertEquals([false, false, AgentTextDefaults.MAX_OTHER_CHARS], [t.edited, t.defaultChangedSinceEdit, t.maxChars])
        assertEquals(t.defaultText, t.text)
        Map p = ok(svc("get#AgentPrompt", [promptId: "router"])).result.prompt
        assertEquals([false, []], [p.edited, p.versions])
        assertEquals(p.defaultText, p.text)
    }

    @Test
    @DisplayName("get#AgentConfig carries no scalar settings")
    void getCarriesNoScalars() {
        Map c = ok(svc("get#AgentConfig", [:])).result.config
        assertEquals(["globalDailyUsdCap", "prices", "prompts", "roles", "tenantCaps", "texts"] as Set, c.keySet() as Set)
        assertFalse(c.containsKey("settings"))
        assertFalse(c.toString().contains("darpan.agent.turnWallClockMs"))
    }

    // ------------------------------------------------------------------ prompts

    @Test
    @DisplayName("a prompt edit is used by the next AgentConfig.current and writes an audit row and a version")
    void promptEdit() {
        String text = "You are the router. Pick one route."
        Map p = ok(svc("update#AgentPrompt", [promptId: "router", promptText: text])).result.prompt
        assertEquals([true, text], [p.edited, p.text])
        assertEquals(text, AgentConfig.current(ec).prompt("router"))
        List<Map> versions = ok(svc("get#AgentPrompt", [promptId: "router"])).result.prompt.versions
        assertEquals([text], versions*.text)
        assertEquals(SUPER_ID, versions[0].changedByUserId)
        assertEquals([false], versions*.reset)
        Map c = ok(svc("get#AgentConfig", [:])).result.config
        assertEquals([true, text.length(), SUPER_ID], c.prompts.find { it.promptId == "router" }.with { [it.edited, it.chars, it.lastChangedByUserId] })
        assertEquals(["Agent prompt router edited (${text.length()} chars).".toString()], audit()*.detailText)
        assertEquals([S + "update#AgentPrompt", "AgentPrompt", "router"], [audit()[0].serviceName, audit()[0].targetType, audit()[0].targetId])
        assertFalse(audit()[0].detailText.contains("Pick one route"), "the audit row never carries the prompt text")

        ok(svc("update#AgentPrompt", [promptId: "router", promptText: text + " Again."]))
        assertEquals(2, ok(svc("get#AgentPrompt", [promptId: "router"])).result.prompt.versions.size())

        ok(svc("reset#AgentConfigItem", [kind: "PROMPT", id: "router"]))
        Map after = ok(svc("get#AgentPrompt", [promptId: "router"])).result.prompt
        assertFalse(after.edited as boolean)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/router.md"), AgentConfig.current(ec).prompt("router"))
        assertEquals([true, false, false], after.versions*.reset, "a reset writes a null version, newest first")
        assertNull(after.versions[0].text)
        assertEquals(3, audit().size())
        assertEquals(S + "reset#AgentConfigItem", audit()[2].serviceName)
    }

    @Test
    @DisplayName("saving the default text stores no edit")
    void defaultTextStoresNoEdit() {
        String text = "Temporary router."
        ok(svc("update#AgentPrompt", [promptId: "router", promptText: text]))
        String dflt = AgentConfigDefaults.classpathText("darpan/agent/prompts/router.md")
        Map p = ok(svc("update#AgentPrompt", [promptId: "router", promptText: dflt])).result.prompt
        assertFalse(p.edited as boolean)
        assertEquals(0L, count("darpan.agent.AgentPrompt"))
        assertEquals(2L, count("darpan.agent.AgentPromptVersion"), "every save writes a version row")
        assertEquals(dflt, AgentConfig.current(ec).prompt("router"))
    }

    @Test
    @DisplayName("a prompt naming a tenant id is refused")
    void promptNamingTenantRefused() {
        Map r = svc("update#AgentPrompt", [promptId: "router", promptText: "Treat ${TENANT} orders first."])
        assertTrue(r.errors.contains("The prompt names tenant ${TENANT}; agent prompts are shared by every tenant.".toString()), "${r.errors}")
        assertEquals(0L, count("darpan.agent.AgentPrompt"))
        assertEquals(0L, count("darpan.agent.AgentPromptVersion"))
        assertEquals([], audit())
        ok(svc("update#AgentPrompt", [promptId: "router", promptText: "Mentions ${TENANT.toLowerCase()} and ${TENANT}X only."]))
        assertTrue(svc("update#AgentPrompt", [promptId: "nope", promptText: "x"]).errors.contains("Unknown agent prompt nope."))
        assertTrue(svc("update#AgentPrompt", [promptId: "router", promptText: "  \n "]).errors.contains("The prompt is empty."))
        assertTrue(svc("update#AgentPrompt", [promptId: "router", promptText: "a" * 100_001]).errors
                .contains("The prompt is longer than 100000 characters."))
        assertEquals(1L, count("darpan.agent.AgentPrompt"), "only the one valid edit")
    }

    // ------------------------------------------------------------------ models and prices

    @Test
    @DisplayName("a role cannot point at an unpriced model")
    void unpricedModelRefused() {
        Map r = svc("update#AgentRoleModel", [roleId: "AUTHOR", modelId: "claude-new-9"])
        assertTrue(r.errors.contains("Model claude-new-9 has no price; add its price first."), "${r.errors}")
        assertEquals(0L, count("darpan.agent.AgentRoleConfig"))
        assertTrue(svc("update#AgentRoleModel", [roleId: "AUTHOR", modelId: "Bad Model!"]).errors.contains("Bad Model! is not a model id."))
        assertTrue(svc("update#AgentRoleModel", [roleId: "NOPE", modelId: "claude-sonnet-5-5"]).errors.contains("Unknown agent role NOPE."))
        assertEquals([], audit())
    }

    @Test
    @DisplayName("a new model is usable once priced")
    void newModelUsableOncePriced() {
        Map price = ok(svc("update#AgentModelPrice", [modelId: "claude-new-9", inputUsdPerMTok: 3, outputUsdPerMTok: 15,
                cacheReadUsdPerMTok: "0.3", cacheWriteUsdPerMTok: "3.75"])).result.price
        assertEquals(["claude-new-9", "ADDED"], [price.modelId, price.source])
        assertNotNull(AgentConfig.current(ec).price("claude-new-9"))
        Map role = ok(svc("update#AgentRoleModel", [roleId: "AUTHOR", modelId: "claude-new-9"])).result.role
        assertEquals(["claude-new-9", "claude-sonnet-5-5", true], [role.modelId, role.defaultModelId, role.edited])
        assertEquals("claude-new-9", AgentConfig.current(ec).model("AUTHOR"))
        assertEquals(["AUTHOR"], ok(svc("get#AgentConfig", [:])).result.config.prices.find { it.modelId == "claude-new-9" }.usedBy)
        assertEquals(["Agent model AUTHOR claude-sonnet-5-5 -> claude-new-9.".toString()], audit().findAll { it.targetType == "AgentRoleConfig" }*.detailText)
        assertEquals("AgentModelPrice", audit()[0].targetType)
        assertEquals("claude-new-9", audit()[0].targetId)
        // an edit of a shipped price shows as EDITED
        assertEquals("EDITED", ok(svc("update#AgentModelPrice", [modelId: "claude-sonnet-5-5", inputUsdPerMTok: 2.5, outputUsdPerMTok: 10,
                cacheReadUsdPerMTok: 0.2, cacheWriteUsdPerMTok: 2.5])).result.price.source)
        // reset of the role goes back to the default
        ok(svc("reset#AgentConfigItem", [kind: "ROLE_MODEL", id: "AUTHOR"]))
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"))
        ok(svc("reset#AgentConfigItem", [kind: "PRICE", id: "claude-sonnet-5-5"]))
        assertEquals(0, new BigDecimal("2.0").compareTo(AgentConfig.current(ec).price("claude-sonnet-5-5").input))
    }

    @Test
    @DisplayName("none is only for AUTHOR_ESCALATION")
    void noneOnlyForEscalation() {
        assertTrue(svc("update#AgentRoleModel", [roleId: "AUTHOR", modelId: "none"]).errors.contains("Only AUTHOR_ESCALATION can be none."))
        assertEquals(0L, count("darpan.agent.AgentRoleConfig"))
        ok(svc("update#AgentRoleModel", [roleId: "AUTHOR_ESCALATION", modelId: "none"]))
        assertEquals("none", AgentConfig.current(ec).model("AUTHOR_ESCALATION"))
    }

    @Test
    @DisplayName("price bounds are enforced")
    void priceBounds() {
        String msg = "Prices are dollars per million tokens: input and output above 0, cache prices 0 or more, none above 1000."
        [[inputUsdPerMTok: 0], [outputUsdPerMTok: 0], [inputUsdPerMTok: -1], [cacheReadUsdPerMTok: -0.1], [cacheWriteUsdPerMTok: 1000.01],
         [outputUsdPerMTok: 1001], [inputUsdPerMTok: "abc"]].each { Map bad ->
            Map p = [modelId: "claude-new-9", inputUsdPerMTok: 1, outputUsdPerMTok: 2, cacheReadUsdPerMTok: 0, cacheWriteUsdPerMTok: 1] + bad
            assertTrue(svc("update#AgentModelPrice", p).errors.contains(msg), "refused: ${bad}")
        }
        assertEquals(0L, count("darpan.agent.AgentModelPrice"))
        ok(svc("update#AgentModelPrice", [modelId: "claude-new-9", inputUsdPerMTok: 1000, outputUsdPerMTok: 1000, cacheReadUsdPerMTok: 0, cacheWriteUsdPerMTok: 0]))
        assertTrue(svc("update#AgentModelPrice", [modelId: "BAD ID", inputUsdPerMTok: 1, outputUsdPerMTok: 2, cacheReadUsdPerMTok: 0, cacheWriteUsdPerMTok: 1])
                .errors.contains("BAD ID is not a model id."))
    }

    @Test
    @DisplayName("reset of a price a role uses is refused")
    void resetOfUsedPriceRefused() {
        ok(svc("update#AgentModelPrice", [modelId: "claude-new-9", inputUsdPerMTok: 3, outputUsdPerMTok: 15, cacheReadUsdPerMTok: 0.3, cacheWriteUsdPerMTok: 3.75]))
        ok(svc("update#AgentRoleModel", [roleId: "AUTHOR", modelId: "claude-new-9"]))
        int audits = audit().size()
        Map r = svc("reset#AgentConfigItem", [kind: "PRICE", id: "claude-new-9"])
        assertTrue(r.errors.contains("claude-new-9 is the model of AUTHOR; pick another model first."), "${r.errors}")
        assertEquals(1L, count("darpan.agent.AgentModelPrice"))
        assertEquals(audits, audit().size())
        ok(svc("reset#AgentConfigItem", [kind: "ROLE_MODEL", id: "AUTHOR"]))
        ok(svc("reset#AgentConfigItem", [kind: "PRICE", id: "claude-new-9"]))
        assertEquals(0L, count("darpan.agent.AgentModelPrice"))
        assertTrue(svc("reset#AgentConfigItem", [kind: "PRICE", id: "claude-never-added"]).errors.contains("Unknown agent price claude-never-added."))
        assertTrue(svc("reset#AgentConfigItem", [kind: "WIDGET", id: "x"]).hasError)
    }

    // ------------------------------------------------------------------ texts

    @Test
    @DisplayName("a text edit must keep the default's tokens and no markup")
    void textEditRules() {
        String id = "reply.RATE_LIMITED"
        assertFalse(svc("update#AgentText", [textId: id, text: "Slow down."]).errors.isEmpty(), "missing {count}")
        assertFalse(svc("update#AgentText", [textId: id, text: "<b>{count}</b> sent."]).errors.isEmpty(), "markup")
        assertTrue(svc("update#AgentText", [textId: "reply.NOPE", text: "x"]).errors.contains("Unknown agent text reply.NOPE."))
        assertEquals(0L, count("darpan.agent.AgentText"))
        assertEquals(AgentTextDefaults.validTextError(id, "Slow down."), svc("update#AgentText", [textId: id, text: "Slow down."]).errors[0])
        Map t = ok(svc("update#AgentText", [textId: id, text: "That is {count} messages this hour; wait a while."])).result.text
        assertEquals([true, "That is {count} messages this hour; wait a while."], [t.edited, t.text])
        assertEquals("That is 7 messages this hour; wait a while.", AgentConfig.current(ec).text(id, [count: 7]))
        assertEquals(1, audit().size())
        assertTrue(audit()[0].detailText.contains("That is {count} messages this hour; wait a while."), "a text edit audits its words")
        assertEquals("AgentText", audit()[0].targetType)
        ok(svc("reset#AgentConfigItem", [kind: "TEXT", id: id]))
        assertEquals(AgentTextDefaults.TEXTS.get(id).replace("{count}", "7"), AgentConfig.current(ec).text(id, [count: 7]))
    }

    // ------------------------------------------------------------------ tenant caps

    @Test
    @DisplayName("a tenant cap override is saved, used for that tenant only, refused outside the global range, and reset to the global")
    void tenantCap() {
        Map cap = ok(svc("update#AgentTenantDailyCap", [tenantUserGroupId: TENANT, dailyUsdCap: "5"])).result.tenantCap
        assertEquals(TENANT, cap.tenantUserGroupId)
        assertEquals(0, new BigDecimal("5").compareTo(AgentConfig.current(ec).tenantCap(TENANT)))
        assertNull(AgentConfig.current(ec).tenantCap("AGCA_OTHER"))
        assertEquals([TENANT], ok(svc("get#AgentConfig", [:])).result.config.tenantCaps*.tenantUserGroupId)
        assertEquals(["Agent tenant cap ${TENANT} 25 -> 5.".toString()], audit()*.detailText)
        assertEquals(["AgentTenantCap", TENANT], [audit()[0].targetType, audit()[0].targetId])
        ok(svc("update#AgentTenantDailyCap", [tenantUserGroupId: TENANT, dailyUsdCap: "2.5"]))
        assertEquals("Agent tenant cap ${TENANT} 5 -> 2.5.".toString(), audit()[1].detailText)

        String range = "A tenant's daily cap must be a number from 0.01 to 1000 dollars, the same range as the global cap."
        ["0", "0.001", "1000.01", "-3", "abc"].each { String bad ->
            assertTrue(svc("update#AgentTenantDailyCap", [tenantUserGroupId: TENANT, dailyUsdCap: bad]).errors.contains(range), "refused: ${bad}")
        }
        assertTrue(svc("update#AgentTenantDailyCap", [tenantUserGroupId: TENANT, dailyUsdCap: ""]).hasError, "blank is refused")
        assertTrue(svc("update#AgentTenantDailyCap", [tenantUserGroupId: "AGCA_GHOST", dailyUsdCap: "5"]).errors.contains("Tenant AGCA_GHOST was not found."))
        assertEquals(0, new BigDecimal("2.5").compareTo(AgentConfig.current(ec).tenantCap(TENANT)), "refusals left the cap alone")
        assertEquals(1L, count("darpan.agent.AgentTenantCap"))

        ok(svc("reset#AgentConfigItem", [kind: "TENANT_CAP", id: TENANT]))
        assertNull(AgentConfig.current(ec).tenantCap(TENANT))
        assertEquals("Agent tenant cap ${TENANT} 2.5 -> 25.".toString(), audit()[2].detailText)
    }

    // ------------------------------------------------------------------ access

    @Test
    @DisplayName("no method asks for a fresh two-factor code")
    void noFreshCode() {
        METHODS.each { String m ->
            Set<String> names = ((ServiceFacadeImpl) ec.service).getServiceDefinition(S + m).getInParameterNames()
            assertTrue(names.every { !it.toLowerCase().contains("code") && !it.toLowerCase().contains("twofactor") }, "${m}: ${names}")
        }
        ok(svc("update#AgentTenantDailyCap", [tenantUserGroupId: TENANT, dailyUsdCap: "7"]))
    }

    @Test
    @DisplayName("every method refuses a non-super-admin and saves nothing")
    void nonSuperAdminRefused() {
        Map params = [promptId: "router", promptText: "Hijacked.", roleId: "AUTHOR", modelId: "claude-opus-5-5", textId: "reply.CLARIFY", text: "Hijacked?",
                tenantUserGroupId: TENANT, dailyUsdCap: "1", inputUsdPerMTok: 1, outputUsdPerMTok: 1, cacheReadUsdPerMTok: 0, cacheWriteUsdPerMTok: 0,
                kind: "PROMPT", id: "router"]
        METHODS.each { String m ->
            Map fenced = svc(m, params, EDITOR)
            assertNotNull(fenced.authzDenied, "${m}: the admin fence must refuse")
            Map gated = svc(m, params, EDITOR, true)
            assertTrue(gated.errors.contains("This operation requires super-admin access."), "${m}: ${gated.errors}")
        }
        ENTITIES_WRITTEN.each { assertEquals(0L, count(it), it) }
        assertEquals([], audit())
    }

    private static final List<String> ENTITIES_WRITTEN = ["darpan.agent.AgentPrompt", "darpan.agent.AgentPromptVersion", "darpan.agent.AgentRoleConfig",
            "darpan.agent.AgentModelPrice", "darpan.agent.AgentText", "darpan.agent.AgentTenantCap"]

    // ------------------------------------------------------------------ helpers

    private long count(String entity) { asSystem { ec.entity.find(entity).useCache(false).count() } }

    private List<Map> audit() {
        asSystem { ec.entity.find("darpan.admin.AdminAuditLog").orderBy("auditTimestamp").useCache(false).list()*.getMap() }
    }

    private <T> T asSystem(Closure<T> work) {
        boolean was = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!was) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entity, Map fields) { ec.entity.makeValue(entity).setAll(fields).createOrUpdate() }

    private Map svc(String method, Map params, String username = SUPER, boolean bypassFence = false) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                def call = tec.service.sync().name(S + method).parameters(new LinkedHashMap(params))
                if (bypassFence) call.disableAuthz()
                try { holder.result = call.call() } catch (ArtifactAuthorizationException e) { holder.authzDenied = e }
                holder.errors = new ArrayList<String>(tec.message.errors) + (tec.message.validationErrors*.message ?: [])
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "agent-config-admin-smoke")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static Map ok(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors}")
        return out
    }
}
