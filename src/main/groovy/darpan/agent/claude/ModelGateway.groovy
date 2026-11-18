package darpan.agent.claude

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams

import darpan.agent.config.AgentLimits

import java.time.Duration

/** The one seam between the agent loop and the model. Tests pass a fake; production passes {@link AnthropicModelGateway}. */
interface ModelGateway {
    Message send(MessageCreateParams params)

    /** The same request, given at most {@code timeout} per attempt. A gateway without timeouts ignores it. */
    default Message send(MessageCreateParams params, Duration timeout) {
        return send(params)
    }

    /**
     * The same, with the turn's own limits snapshot, so the gateway reads no setting per request. A gateway that keeps no
     * transport settings ignores them.
     */
    default Message send(MessageCreateParams params, Duration timeout, AgentLimits limits) {
        return send(params, timeout)
    }
}
