package darpan.agent

import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageParam
import darpan.agent.claude.ModelGateway
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

    /** The request shapes a turn sends: the router, the investigator, the author and the ticket-only author. */
    static List<RoleSpec> specs() {
        return [RoleSpec.router(AgentOrchestrator.ROUTER_SCHEMA), RoleSpec.investigator().copy(outputSchema: AgentOrchestrator.EVIDENCE_SCHEMA),
                RoleSpec.author(), RoleSpec.author().copy(tools: ["raise_ticket"])]
    }

    /** "accepted", or the refusal, once known; null until then. */
    private static volatile String known = null

    /** Null when every shape is accepted (or the API could not be asked just now); "ROLE: message" when one is refused. */
    static synchronized String refusal(ModelGateway gateway) {
        if (known != null) return known == ACCEPTED ? null : known
        for (RoleSpec spec : specs()) {
            try {
                gateway.send(AgentRoleLoop.buildParams(spec.copy(maxTokens: MAX_TOKENS), [MessageParam.ofUser(PING)]))
            } catch (AnthropicServiceException e) {
                int status = e.statusCode()
                if (status >= 400 && status < 500 && status != 429) {
                    known = "${spec.role} ${spec.model}: ${e.message}".toString()
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
        return null
    }

    private static final String ACCEPTED = "accepted"

    /** Tests only: forget what was learned, so the next call asks again. */
    static synchronized void reset() { known = null }
}
