package darpan.agent

import darpan.agent.claude.AgentClientFactory

/**
 * DAR-BE-159. AgentSwitch (core) answers ON or AGENT_OFF. This component also knows whether a Claude client can be built,
 * so an ON switch with no key reads AGENT_UNAVAILABLE.
 */
class AgentAvailability {
    static String state(def ec) {
        String core = AgentSwitch.state(ec)
        return stateFor(core, core == AgentSwitch.ON ? AgentClientFactory.client(ec) : null)
    }

    static String stateFor(String coreState, Object client) {
        if (coreState == AgentSwitch.ON && client == null) return AgentSwitch.AGENT_UNAVAILABLE
        return coreState
    }
}
