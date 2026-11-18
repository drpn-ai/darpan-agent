package darpan.agent

import darpan.config.DarpanSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-304: the agent's hourly turns and daily tenant cap are settings: row, then the system property, then the catalog default. */
class AgentCapSettingsTests {
    @BeforeEach
    void fresh() {
        DarpanSettings.resetForTest()
        System.clearProperty(AgentOrchestrator.TURNS_PER_HOUR_PROPERTY)
        System.clearProperty(AgentOrchestrator.DAILY_CAP_PROPERTY)
        DarpanSettings.rowLoader = { -> [:] as Map<String, Map> }
    }

    @AfterEach
    void clean() {
        DarpanSettings.resetForTest()
        System.clearProperty(AgentOrchestrator.TURNS_PER_HOUR_PROPERTY)
        System.clearProperty(AgentOrchestrator.DAILY_CAP_PROPERTY)
    }

    @Test
    @DisplayName("both agent keys are in the merged catalog beside a core key")
    void theAgentKeysAreInTheMergedCatalog() {
        Set<String> keys = DarpanSettings.catalog().keySet()
        assertTrue(keys.containsAll([AgentOrchestrator.TURNS_PER_HOUR_PROPERTY, AgentOrchestrator.DAILY_CAP_PROPERTY, "darpan.twoFactor.reminderRepeatDays"]), keys.toString())
    }

    @Test
    @DisplayName("defaults are 20 turns an hour and 25 dollars a day")
    void defaultsAre20And25() {
        assertEquals(20, AgentOrchestrator.turnsPerHour())
        assertEquals(0, new BigDecimal("25").compareTo(AgentOrchestrator.dailyCapUsd()))
    }

    @Test
    @DisplayName("a settings row changes the cap")
    void aRowChangesTheCap() {
        DarpanSettings.resetForTest()
        DarpanSettings.rowLoader = { -> ["darpan.agent.dailyUsdCapPerTenant": [settingValue: "5"], "darpan.agent.turnsPerUserPerHour": [settingValue: "3"]] as Map<String, Map> }
        assertEquals(0, new BigDecimal("5").compareTo(AgentOrchestrator.dailyCapUsd()))
        assertEquals(3, AgentOrchestrator.turnsPerHour())
    }

    @Test
    @DisplayName("the eval runner's system properties still apply when no row is set")
    void theEvalRunnersPropertyStillApplies() {
        System.setProperty(AgentOrchestrator.TURNS_PER_HOUR_PROPERTY, "1000")
        System.setProperty(AgentOrchestrator.DAILY_CAP_PROPERTY, "7.5")
        DarpanSettings.resetForTest()
        DarpanSettings.rowLoader = { -> [:] as Map<String, Map> }
        assertEquals(1000, AgentOrchestrator.turnsPerHour())
        assertEquals(0, new BigDecimal("7.5").compareTo(AgentOrchestrator.dailyCapUsd()))
    }
}
