package darpan.agent.claude

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams

/** The one seam between the agent loop and the model. Tests pass a fake; production passes {@link AnthropicModelGateway}. */
interface ModelGateway {
    Message send(MessageCreateParams params)
}
