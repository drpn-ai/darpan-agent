package darpan.agent.config

import darpan.agent.AgentOrchestrator
import darpan.config.DarpanSettings
import darpan.config.SettingDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-307: every agent scalar is a settings-catalog entry, read through AgentLimits (row, then -D property, then catalog default). */
class AgentLimitsTests {
    /** Today's code values, held as literals because the constants are gone: key, default, min, max, unit. */
    private static final List<List> TABLE = [
            ["turnsPerUserPerHour", "20", 1, 1000, "count"], ["dailyUsdCapPerTenant", "25", 0.01, 1000, "usd"],
            ["turnWallClockMs", "240000", 30000, 600000, "ms"], ["explainMaxRequests", "5", 1, 50, "count"],
            ["routerMaxRequests", "1", 1, 5, "count"], ["routerWallClockMs", "20000", 5000, 600000, "ms"], ["routerMaxTokens", "2000", 256, 128000, "tokens"],
            ["investigatorMaxRequests", "12", 1, 50, "count"], ["investigatorMaxToolCalls", "30", 0, 200, "count"],
            ["investigatorWallClockMs", "90000", 5000, 600000, "ms"], ["investigatorMaxTokens", "16000", 256, 128000, "tokens"],
            ["authorMaxRequests", "8", 1, 50, "count"], ["authorMaxToolCalls", "6", 0, 100, "count"],
            ["authorWallClockMs", "120000", 5000, 600000, "ms"], ["authorMaxTokens", "32000", 256, 128000, "tokens"],
            ["emailSmileTimeoutMs", "4000", 1000, 10000, "ms"], ["emailSmileMaxTokens", "200", 50, 2000, "tokens"],
            ["auditRunWordsTimeoutMs", "60000", 5000, 300000, "ms"], ["auditRunWordsMaxTokens", "8000", 1000, 64000, "tokens"],
            ["auditRunWordsHeadlineMaxChars", "400", 50, 2000, "chars"], ["auditRunWordsFixMaxChars", "300", 50, 2000, "chars"],
            ["gatewayConnectTimeoutMs", "10000", 1000, 60000, "ms"], ["gatewayReadIdleTimeoutMs", "30000", 5000, 300000, "ms"],
            ["requestMaxRetries", "2", 0, 5, "count"], ["retryBackoffBaseMs", "500", 100, 10000, "ms"], ["retryBackoffMaxMs", "8000", 500, 60000, "ms"],
            ["minConfidence", "0.6", 0, 1, "factor"],
            ["maxEvidenceRounds", "2", 0, 5, "count"], ["maxItems", "10", 1, 50, "count"], ["maxFacts", "100", 1, 1000, "count"],
            ["maxAnswerChars", "4000", 200, 20000, "chars"], ["maxMessageChars", "20000", 1000, 20000, "chars"], ["earlierTurns", "4", 0, 20, "count"],
    ]

    private static Map<String, SettingDefinition> agentCatalog() {
        Path file = Paths.get("src/main/resources/darpan/settings-catalog.json").toAbsolutePath()
        assertTrue(Files.isRegularFile(file), file.toString())
        return DarpanSettings.parseCatalog(file.toFile().getText("UTF-8"), file.toString())
    }

    private static String keyOf(String name) { return "darpan.agent." + name }

    @BeforeEach
    void fresh() {
        DarpanSettings.resetForTest()
        TABLE.each { System.clearProperty(keyOf(it[0] as String)) }
        DarpanSettings.rowLoader = { -> [:] as Map<String, Map> }
    }

    @AfterEach
    void clean() {
        DarpanSettings.resetForTest()
        TABLE.each { System.clearProperty(keyOf(it[0] as String)) }
    }

    @Test
    @DisplayName("the agent catalog parses, every default is valid and equals today's code value")
    void theAgentCatalogParsesAndDefaultsAreToday() {
        Map<String, SettingDefinition> cat = agentCatalog()
        assertEquals(TABLE.collect { keyOf(it[0] as String) } as Set, cat.keySet(), "exactly the 33 keys")
        assertEquals(33, cat.size())
        TABLE.each { List row ->
            SettingDefinition d = cat[keyOf(row[0] as String)]
            assertTrue(d.area.startsWith("Agent"), "area of ${d.key}: ${d.area}")
            assertTrue(d.unit in ["ms", "tokens", "chars", "count", "usd", "factor"], "unit of ${d.key}: ${d.unit}")
            assertEquals(row[4], d.unit, d.key)
            assertEquals(new BigDecimal(row[1] as String), new BigDecimal(d.defaultValue), "default of ${d.key}")
            assertEquals(0, new BigDecimal(row[2].toString()).compareTo(d.min), "min of ${d.key}")
            assertEquals(0, new BigDecimal(row[3].toString()).compareTo(d.max), "max of ${d.key}")
            assertNotNull(DarpanSettings.parse(d, d.defaultValue), "default of ${d.key} is inside its own range")
            assertTrue(d.description && !d.description.contains("\n"), d.key)
            assertEquals(d.key in [AgentLimits.DAILY_USD_CAP_PER_TENANT, AgentLimits.MIN_CONFIDENCE] ? "DECIMAL" : "INT", d.type, d.key)
        }
        assertEquals(AgentLimits.RETRY_BACKOFF_MAX_MS, cat[AgentLimits.RETRY_BACKOFF_BASE_MS].lessThan)
        assertTrue(cat[AgentLimits.MIN_CONFIDENCE].description.contains("router.md"))
    }

    @Test
    @DisplayName("no agent key is also in the core catalog")
    void noAgentKeyIsInTheCoreCatalog() {
        Path core = Paths.get("../darpan/src/main/resources/darpan/settings-catalog.json").toAbsolutePath()
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(core), "darpan core not checked out beside darpan-agent")
        Map<String, SettingDefinition> coreCat = DarpanSettings.parseCatalog(core.toFile().getText("UTF-8"), core.toString())
        assertTrue(coreCat.keySet().disjoint(agentCatalog().keySet()))
    }

    @Test
    @DisplayName("every agent key is in the merged catalog beside a core key")
    void theAgentKeysAreInTheMergedCatalog() {
        Set<String> keys = DarpanSettings.catalog().keySet()
        assertTrue(keys.containsAll(TABLE.collect { keyOf(it[0] as String) } + ["darpan.twoFactor.reminderRepeatDays"]), keys.toString())
    }

    @Test
    @DisplayName("read() with no rows is every default")
    void noRowsIsEveryDefault() {
        AgentLimits l = AgentLimits.read()
        assertEquals(32000, l.authorMaxTokens)
        assertEquals(0, new BigDecimal("0.6").compareTo(l.minConfidence))
        assertEquals(0, new BigDecimal("25").compareTo(l.dailyUsdCapPerTenant))
        assertEquals(20, l.turnsPerUserPerHour)
        assertEquals(500, l.retryBackoffBaseMs)
        assertEquals(8000, l.retryBackoffMaxMs)
        assertEquals(20, AgentOrchestrator.turnsPerHour())
    }

    @Test
    @DisplayName("a settings row changes the value; an out-of-range row is skipped")
    void aRowChangesTheValueAndABadOneIsSkipped() {
        DarpanSettings.resetForTest()
        DarpanSettings.rowLoader = { -> [(AgentLimits.DAILY_USD_CAP_PER_TENANT): [settingValue: "5"], (AgentLimits.TURNS_PER_USER_PER_HOUR): [settingValue: "3"],
                                         (AgentLimits.MAX_ITEMS): [settingValue: "0"]] as Map<String, Map> }
        assertEquals(0, new BigDecimal("5").compareTo(AgentOrchestrator.dailyCapUsd()))
        assertEquals(3, AgentOrchestrator.turnsPerHour())
        AgentLimits l = AgentLimits.read()
        assertEquals(3, l.turnsPerUserPerHour)
        assertEquals(10, l.maxItems)
    }

    @Test
    @DisplayName("an inverted backoff pair reads both defaults")
    void anInvertedBackoffPairReadsBothDefaults() {
        DarpanSettings.resetForTest()
        DarpanSettings.rowLoader = { -> [(AgentLimits.RETRY_BACKOFF_BASE_MS): [settingValue: "9000"], (AgentLimits.RETRY_BACKOFF_MAX_MS): [settingValue: "1000"]] as Map<String, Map> }
        AgentLimits l = AgentLimits.read()
        assertEquals(500, l.retryBackoffBaseMs)
        assertEquals(8000, l.retryBackoffMaxMs)
    }

    @Test
    @DisplayName("a -D property still applies when no row is set")
    void aPropertyStillAppliesWhenNoRowIsSet() {
        System.setProperty(AgentLimits.TURNS_PER_USER_PER_HOUR, "1000")
        System.setProperty(AgentLimits.DAILY_USD_CAP_PER_TENANT, "7.5")
        DarpanSettings.resetForTest()
        DarpanSettings.rowLoader = { -> [:] as Map<String, Map> }
        assertEquals(1000, AgentOrchestrator.turnsPerHour())
        assertEquals(0, new BigDecimal("7.5").compareTo(AgentOrchestrator.dailyCapUsd()))
        assertEquals(1000, AgentLimits.read().turnsPerUserPerHour)
    }

    @Test
    @DisplayName("every agent catalog key is read through AgentLimits and nothing else reads the -D property")
    void nothingElseReadsTheCatalogKeys() {
        Path root = Paths.get("src/main/groovy").toAbsolutePath()
        List<String> keys = TABLE.collect { keyOf(it[0] as String) }
        List<String> offenders = []
        Files.walk(root).filter { it.toString().endsWith(".groovy") && !it.toString().endsWith("/config/AgentLimits.groovy") }.each { Path f ->
            String text = f.toFile().getText("UTF-8")
            keys.each { String k -> if (text.contains('"' + k + '"')) offenders << "${f.fileName}: ${k}".toString() }
            if (text =~ /System\.getProperty\(\s*AgentLimits\./) offenders << "${f.fileName}: -D read of an AgentLimits key".toString()
        }
        assertEquals([], offenders)
    }
}
