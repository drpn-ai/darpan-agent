package darpan.agent.config

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-307: the agent's own config tables over their code defaults, through a cached snapshot with a last-good fallback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentConfigTests {
    private ExecutionContext ec

    @BeforeAll
    void boot() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "agent-config")
    }

    @AfterAll
    void shutdown() {
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
        ec?.destroy()
    }

    @BeforeEach
    void fresh() {
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
        AgentConfigTestSupport.clear(ec)
    }

    @AfterEach
    void after() {
        AgentConfigTestSupport.clear(ec)
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
    }

    private static void assertDec(Number expected, BigDecimal actual) {
        assertNotNull(actual)
        assertEquals(0, new BigDecimal(expected.toString()).compareTo(actual), "expected ${expected} but was ${actual}".toString())
    }

    @Test
    @DisplayName("with no rows every value is today's default")
    void defaultsWithNoRows() {
        AgentConfig c = AgentConfig.current(ec)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/router.md"), c.prompt("router"))
        assertEquals("claude-sonnet-5-5", c.model(AgentConfigDefaults.AUTHOR))
        assertEquals("claude-opus-5-5", c.model(AgentConfigDefaults.AUTHOR_ESCALATION))
        assertDec(5.0, c.price("claude-opus-5-5").cacheWrite)
        assertNull(c.tenantCap("ANY"))
    }

    @Test
    @DisplayName("an edited row wins; a deleted row falls back")
    void editedRowWinsDeletedFallsBack() {
        AgentConfigTestSupport.set(ec, [
                AgentRoleConfig: [[roleId: "AUTHOR", modelId: "claude-opus-5-5"]],
                AgentPrompt    : [[promptId: "router", promptText: "EDITED ROUTER"]],
                AgentModelPrice: [[modelId: "claude-opus-5-5", inputUsdPerMTok: 9, outputUsdPerMTok: 20, cacheReadUsdPerMTok: 1, cacheWriteUsdPerMTok: 11]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals("claude-opus-5-5", c.model("AUTHOR"))
        assertEquals("EDITED ROUTER", c.prompt("router"))
        assertEquals("EDITED ROUTER", c.promptForPath("darpan/agent/prompts/router.md"))
        assertDec(9, c.price("claude-opus-5-5").input)
        AgentConfigTestSupport.clear(ec)
        c = AgentConfig.current(ec)
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/router.md"), c.prompt("router"))
        assertDec(4, c.price("claude-opus-5-5").input)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/author.md"), c.promptForPath("darpan/agent/prompts/author.md"))
        assertNotNull(c.promptForPath("darpan/agent/prompts/tools-not-a-prompt.md".replace("tools-not-a-prompt", "router")))
        assertThrows(IllegalStateException) { c.promptForPath("darpan/agent/prompts/nope.md") }
    }

    @Test
    @DisplayName("an added price prices a new model")
    void addedPrice() {
        assertNull(AgentConfig.current(ec).price("claude-new-9"))
        AgentConfigTestSupport.set(ec, [AgentModelPrice: [[modelId: "claude-new-9", inputUsdPerMTok: 1, outputUsdPerMTok: 5, cacheReadUsdPerMTok: 0.1, cacheWriteUsdPerMTok: 1.25]]])
        assertDec(5, AgentConfig.current(ec).price("claude-new-9").output)
        assertDec(20, AgentConfig.current(ec).dearest().output)
    }

    @Test
    @DisplayName("unpricedRoles lists a role whose model has no price and skips none")
    void unpricedRoles() {
        AgentConfigTestSupport.set(ec, [AgentRoleConfig: [[roleId: "ROUTER", modelId: "claude-unpriced-1"], [roleId: "INVESTIGATOR", modelId: "none"]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals(["ROUTER"], c.unpricedRoles(AgentConfigDefaults.TURN_ROLES))
    }

    @Test
    @DisplayName("the tenant override is valid only inside the global cap's range")
    void tenantOverrideRange() {
        AgentConfigTestSupport.set(ec, [AgentTenantCap: [
                [companyUserGroupId: "T_FIVE", dailyUsdCap: 5], [companyUserGroupId: "T_ZERO", dailyUsdCap: 0],
                [companyUserGroupId: "T_BIG", dailyUsdCap: 5000]]])
        AgentConfig c = AgentConfig.current(ec)
        assertDec(5, c.tenantCap("T_FIVE"))
        assertNull(c.tenantCap("T_ZERO"))
        assertNull(c.tenantCap("T_BIG"))
        assertNull(c.tenantCap("T_OTHER"), "another tenant's override never applies")
        assertNull(c.tenantCap(null))
    }

    @Test
    @DisplayName("an invalid row falls back to its default")
    void invalidRowFallsBack() {
        AgentConfigTestSupport.set(ec, [
                AgentRoleConfig: [[roleId: "AUTHOR", modelId: "bad id"], [roleId: "NOT_A_ROLE", modelId: "claude-opus-5-5"]],
                AgentModelPrice: [[modelId: "claude-sonnet-5-5", inputUsdPerMTok: -1, outputUsdPerMTok: 10, cacheReadUsdPerMTok: 0.2, cacheWriteUsdPerMTok: 2.5]],
                AgentPrompt    : [[promptId: "author", promptText: "  "], [promptId: "unknown", promptText: "x"]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertNull(c.model("NOT_A_ROLE"))
        assertDec(2, c.price("claude-sonnet-5-5").input)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/author.md"), c.prompt("author"))
    }

    @Test
    @DisplayName("a change shows after expire and after the TTL, not before")
    void changeShowsAfterExpireOrTtl() {
        long now = 1_000_000L
        AgentConfig.clock = { -> now }
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"))
        // a row written straight to the table (no expire) is not seen inside the TTL
        boolean was = ec.artifactExecution.disableAuthz()
        try { ec.entity.makeValue("darpan.agent.AgentRoleConfig").setAll([roleId: "AUTHOR", modelId: "claude-opus-5-5"]).createOrUpdate() } finally { if (!was) ec.artifactExecution.enableAuthz() }
        now += AgentConfig.TTL_MS - 1
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"))
        now += 1
        assertEquals("claude-opus-5-5", AgentConfig.current(ec).model("AUTHOR"))
        // and expire() shows an edit at once
        AgentConfigTestSupport.set(ec, [AgentRoleConfig: [[roleId: "AUTHOR", modelId: "claude-haiku-5-5"]]])
        assertEquals("claude-haiku-5-5", AgentConfig.current(ec).model("AUTHOR"))
    }

    @Test
    @DisplayName("a failed load keeps the last snapshot")
    void failedLoadKeepsLast() {
        long now = 5_000L
        AgentConfig.clock = { -> now }
        AgentConfig good = AgentConfig.defaults().with([models: [AUTHOR: "claude-opus-5-5"]])
        int calls = 0
        AgentConfig.loader = { e -> if (calls++ == 0) return good; throw new IllegalStateException("db down") }
        assertEquals("claude-opus-5-5", AgentConfig.current(ec).model("AUTHOR"))
        now += AgentConfig.TTL_MS
        assertEquals("claude-opus-5-5", AgentConfig.current(ec).model("AUTHOR"), "the failed reload keeps the last good snapshot")
        assertEquals(2, calls)
        now += AgentConfig.TTL_MS
        AgentConfig.loader = { e -> AgentConfig.defaults() }
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"), "a later good load replaces it")
    }

    @Test
    @DisplayName("without the framework current() is defaults")
    void noFramework() {
        AgentConfig.frameworkUp = { -> false }
        AgentConfig.loader = { e -> throw new AssertionError("must not load") }
        AgentConfig c = AgentConfig.current(null)
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertNull(c.tenantCap("X"))
        AgentConfig custom = AgentConfig.defaults().with([models: [AUTHOR: "claude-haiku-5-5"]])
        assertEquals("claude-haiku-5-5", AgentConfig.withConfig(custom) { AgentConfig.current(null).model("AUTHOR") })
    }

    @Test
    @DisplayName("no data file seeds the agent config entities or an agent DarpanSetting row")
    void noDataFileSeedsAgentConfig() {
        List<Path> roots = [Paths.get("data"), Paths.get("../darpan/data")].collect { it.toAbsolutePath().normalize() }
        int scanned = 0
        List<String> hits = []
        roots.findAll { Files.isDirectory(it) }.each { Path root ->
            Files.walk(root).withCloseable { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".xml") && !it.toString().contains("/data/test/") }.each { Path f ->
                    scanned++
                    String text = f.toFile().getText("UTF-8")
                    if (text =~ /darpan\.agent\.Agent(Prompt|PromptVersion|RoleConfig|ModelPrice|TenantCap|Text)\b/ || text =~ /DarpanSetting[^>]*settingKey="darpan\.agent\./ ||
                            text =~ /settingKey="darpan\.agent\.[^"]*"[^>]*DarpanSetting/) hits << f.toString()
                }
            }
        }
        assertTrue(scanned > 0, "the scan must see the darpan data files")
        assertEquals([], hits)
    }
}
