package darpan.agent.claude

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams

import java.time.Duration

/** The one seam between the agent loop and the model. Tests pass a fake; production passes {@link AnthropicModelGateway}. */
interface ModelGateway {
    Message send(MessageCreateParams params)

    /** The same request, given at most {@code timeout} per attempt. A gateway without timeouts ignores it. */
    default Message send(MessageCreateParams params, Duration timeout) {
        return send(params)
    }
}
