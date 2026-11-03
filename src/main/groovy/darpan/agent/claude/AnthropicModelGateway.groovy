package darpan.agent.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.RawMessageStreamEvent

/** Streams the response (long outputs and thinking need it) and returns the accumulated final message. */
class AnthropicModelGateway implements ModelGateway {
    private final AnthropicClient client

    AnthropicModelGateway(AnthropicClient client) {
        if (client == null) throw new IllegalArgumentException("A Claude client is required")
        this.client = client
    }

    @Override
    Message send(MessageCreateParams params) {
        return accumulate(client.messages().createStreaming(params))
    }

    /** Folds the stream into the final message and always closes it, including when the stream throws mid-way. */
    static Message accumulate(StreamResponse<RawMessageStreamEvent> stream) {
        MessageAccumulator accumulator = MessageAccumulator.create()
        try {
            stream.stream().forEach { RawMessageStreamEvent event -> accumulator.accumulate(event) }
        } finally {
            stream.close()
        }
        return accumulator.message()
    }

    @Override
    String toString() { return "AnthropicModelGateway" }
}
