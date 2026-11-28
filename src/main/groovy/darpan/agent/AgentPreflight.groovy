package darpan.agent

import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageParam
import darpan.agent.claude.ModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigDefaults
import darpan.agent.config.AgentLimits
import darpan.agent.loop.AgentRoleLoop
import darpan.agent.loop.RoleSpec
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Whether the Messages API accepts each role's request shape (model, tools with their strict flags, output schema), asked
 * once per JVM with one small real request per role. The API refuses some shapes only when it compiles them: too many
 * optional parameters, "Schema is too complex", "The compiled grammar is too large". Offline tests never see those; without
 * this, every turn of a refused role is a 400. A refusal reads AGENT_REFUSED (a Darpan staff fix, said to the person in
 * words) and is logged with the API's message. A failure that is not a refusal (5xx, 429, network) says nothing about the
 * shape, so it is not remembered and the next turn asks again.
 */
class AgentPreflight {
    private static final Logger logger = LoggerFactory.getLogger(AgentPreflight.class)

    /** Room for adaptive thinking to start: the answer is not read, only whether the request was accepted. */
    static final long MAX_TOKENS = 256L
    static final String PING = "Preflight check: reply with the word OK and nothing else."

    /** The request shapes a turn sends: router, investigator, author, ticket-only author, and the escalation author when on. */
    static List<RoleSpec> specs(AgentConfig cfg, AgentLimits lim = AgentLimits.read()) {
        List<RoleSpec> out = [RoleSpec.router(AgentOrchestrator.ROUTER_SCHEMA, cfg, lim), RoleSpec.investigator(cfg, lim).copy(outputSchema: AgentOrchestrator.EVIDENCE_SCHEMA),
                              RoleSpec.author(cfg, lim), RoleSpec.author(cfg, lim).copy(tools: ["raise_ticket"])]
        RoleSpec stronger = RoleSpec.escalationAuthor(cfg, lim)
        if (stronger != null) out << stronger
        return out
    }

    /** What the models were when the answer was learned: each spec's role=model. A different one asks again. */
    private static String fingerprint(List<RoleSpec> specs) {
        return specs.indexed().collect { int i, RoleSpec s -> "${label(specs, i)}=${s.model}".toString() }.join(",")
    }

    /** The escalation author is the fifth shape and its own role in the config, so a refusal names AUTHOR_ESCALATION, not AUTHOR. */
    private static String label(List<RoleSpec> specs, int i) { return i == 4 ? AgentConfigDefaults.AUTHOR_ESCALATION : specs[i].role }

    /** "accepted", or the refusal, once known for the models in knownFingerprint; null until then. */
    private static volatile String known = null
    private static volatile String knownFingerprint = null

    /** Null when every shape is accepted (or the API could not be asked just now); "ROLE model: message" when one is refused. */
    static String refusal(ModelGateway gateway) { return refusal(gateway, AgentConfig.current(null)) }

    /**
     * The same, for the turn roles' models in cfg. The specs (which read the settings) are built before the lock is taken, so
     * no config or settings read ever happens while it is held.
     */
    static String refusal(ModelGateway gateway, AgentConfig cfg, AgentLimits lim = AgentLimits.read()) {
        List<RoleSpec> specs = specs(cfg, lim)
        return check(gateway, specs, fingerprint(specs))
    }

    private static synchronized String check(ModelGateway gateway, List<RoleSpec> specs, String fingerprint) {
        if (known != null && knownFingerprint == fingerprint) return known == ACCEPTED ? null : known
        known = null
        for (int i = 0; i < specs.size(); i++) {
            RoleSpec spec = specs[i]
            try {
                gateway.send(AgentRoleLoop.buildParams(spec.copy(maxTokens: MAX_TOKENS), [MessageParam.ofUser(PING)]))
            } catch (AnthropicServiceException e) {
                int status = e.statusCode()
                if (status >= 400 && status < 500 && status != 429) {
                    known = "${label(specs, i)} ${spec.model}: ${e.message}".toString()
                    knownFingerprint = fingerprint
                    logger.error("Agent request shape refused by the Claude API, the agent is off until it is fixed: {}", known)
                    return known
                }
                logger.warn("Agent preflight could not ask the Claude API ({}); it asks again on the next turn", status)
                return null
            } catch (Exception e) {
                logger.warn("Agent preflight could not ask the Claude API ({}); it asks again on the next turn", e.class.simpleName)
                return null
            }
        }
        known = ACCEPTED
        knownFingerprint = fingerprint
        return null
    }

    private static final String ACCEPTED = "accepted"

    /** Tests only: forget what was learned, so the next call asks again. */
    static synchronized void resetForTests() { known = null; knownFingerprint = null }
}
