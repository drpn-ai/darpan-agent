package darpan.agent.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams

/** Streams the response (long outputs and thinking need it) and returns the accumulated final message. */
class AnthropicModelGateway implements ModelGateway {
    private final AnthropicClient client

    AnthropicModelGateway(AnthropicClient client) {
        if (client == null) throw new IllegalArgumentException("A Claude client is required")
        this.client = client
    }

    @Override
    Message send(MessageCreateParams params) {
        MessageAccumulator accumulator = MessageAccumulator.create()
        def stream = client.messages().createStreaming(params)
        try {
            stream.stream().forEach { event -> accumulator.accumulate(event) }
        } finally {
            stream.close()
        }
        return accumulator.message()
    }

    @Override
    String toString() { return "AnthropicModelGateway" }
}
