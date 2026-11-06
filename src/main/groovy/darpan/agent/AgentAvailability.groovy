package darpan.agent

import darpan.agent.claude.AgentClientFactory
import darpan.agent.claude.AnthropicModelGateway
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-159. AgentSwitch (core) answers ON or AGENT_OFF. This component also knows whether a Claude client can be built,
 * so an ON switch with no key reads AGENT_UNAVAILABLE, and one whose request shapes the API refuses reads AGENT_REFUSED
 * (AgentPreflight, asked once per JVM).
 */
class AgentAvailability {
    static final String AGENT_REFUSED = "AGENT_REFUSED"

    private static final Logger logger = LoggerFactory.getLogger(AgentAvailability.class)

    static String state(def ec) {
        String state = resolve(AgentSwitch.state(ec)) { AgentClientFactory.client(ec) }
        if (state != AgentSwitch.ON) return state
        return AgentPreflight.refusal(AnthropicModelGateway.perRequest(ec)) == null ? state : AGENT_REFUSED
    }

    /** Core state plus the client lookup; a refused base URL reads AGENT_UNAVAILABLE instead of throwing. */
    static String resolve(String core, Closure clientSupplier) {
        if (core != AgentSwitch.ON) return core
        try {
            return stateFor(core, clientSupplier.call())
        } catch (IllegalStateException e) {
            // The factory's refusal message never carries the key.
            logger.warn("Agent unavailable: {}", e.message)
            return AgentSwitch.AGENT_UNAVAILABLE
        }
    }

    static String stateFor(String coreState, Object client) {
        if (coreState == AgentSwitch.ON && client == null) return AgentSwitch.AGENT_UNAVAILABLE
        return coreState
    }
}
