package darpan.agent

import darpan.agent.claude.AgentClientFactory
import darpan.agent.claude.AnthropicModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigDefaults
import darpan.agent.config.AgentLimits
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

    /** For a caller outside a turn (the eval): reads its own snapshot. A turn passes its own, see the three-argument form. */
    static String state(def ec) { return state(ec, AgentConfig.current(ec), AgentLimits.read()) }

    /**
     * The state on the caller's config and limits, never a fresh read: a turn asks before every role with its one snapshot, so
     * a model edited mid-turn neither changes a running turn nor starts a preflight inside it (the preflight answers from
     * its memory for an unchanged set of models).
     */
    static String state(def ec, AgentConfig cfg, AgentLimits lim) {
        String state = resolve(AgentSwitch.state(ec)) { AgentClientFactory.client(ec) }
        if (state != AgentSwitch.ON) return state
        if (pricingRefusal(cfg)) return AGENT_REFUSED
        return AgentPreflight.refusal(AnthropicModelGateway.perRequest(ec), cfg, lim) == null ? state : AGENT_REFUSED
    }

    /**
     * DAR-BE-307: true when a turn role's model has no price, so its spend could not be counted: the agent refuses the turn
     * and sends nothing (never billed at a guess). Logged as an error, as the preflight's refusal is.
     */
    static boolean pricingRefusal(AgentConfig cfg) {
        List<String> unpriced = cfg.unpricedRoles(AgentConfigDefaults.TURN_ROLES)
        if (!unpriced) return false
        unpriced.each { String role -> logger.error("Agent refused: model {} of {} has no price", cfg.model(role), role) }
        return true
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
