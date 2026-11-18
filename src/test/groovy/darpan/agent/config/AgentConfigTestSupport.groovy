package darpan.agent.config

import darpan.config.DarpanSettings

/** DAR-BE-307 test helper: writes or deletes the agent config rows and the settings rows, then expires the snapshots. */
class AgentConfigTestSupport {
    static final List<String> ENTITIES = ["darpan.agent.AgentRoleConfig", "darpan.agent.AgentModelPrice", "darpan.agent.AgentPrompt",
            "darpan.agent.AgentPromptVersion", "darpan.agent.AgentTenantCap", "darpan.agent.AgentText"]

    /** rows: entity short name (AgentRoleConfig, ...) to a list of field maps; each is created or updated. */
    static void set(def ec, Map<String, List<Map>> rows) {
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            rows.each { String name, List<Map> list ->
                list.each { Map r -> ec.entity.makeValue("darpan.agent." + name).setAll(r).createOrUpdate() }
            }
        } finally { if (!was) ec.artifactExecution.enableAuthz() }
        AgentConfig.expire()
    }

    static void clear(def ec) {
        boolean was = ec.artifactExecution.disableAuthz()
        try { ENTITIES.each { ec.entity.find(it).deleteAll() } } finally { if (!was) ec.artifactExecution.enableAuthz() }
        AgentConfig.expire()
    }

    static void settings(Map<String, String> rows) {
        DarpanSettings.rowLoader = { -> rows.collectEntries { k, v -> [(k): [settingValue: v]] } as Map<String, Map> }
        DarpanSettings.expire()
    }

    static void clearSettings() { DarpanSettings.resetForTest() }
}
