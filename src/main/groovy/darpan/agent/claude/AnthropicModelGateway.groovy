package darpan.agent.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.RawMessageStreamEvent

import java.time.Duration

/**
 * Streams the response (long outputs and thinking need it) and returns the accumulated final message. The client is
 * looked up for every request: a key rotation closes the old client, so none is held across requests or turns.
 */
class AnthropicModelGateway implements ModelGateway {
    private final Closure<AnthropicClient> clientSupplier

    AnthropicModelGateway(AnthropicClient client) {
        if (client == null) throw new IllegalArgumentException("A Claude client is required")
        this.clientSupplier = { client }
    }

    private AnthropicModelGateway(Closure<AnthropicClient> clientSupplier) { this.clientSupplier = clientSupplier }

    /** The production gateway: {@link AgentClientFactory#client} on every request. */
    static AnthropicModelGateway perRequest(def ec) {
        return supplying({ AgentClientFactory.client(ec) } as Closure<AnthropicClient>)
    }

    /** A gateway that asks the supplier for the client on every request (null means no key). */
    static AnthropicModelGateway supplying(Closure<AnthropicClient> clientSupplier) {
        if (clientSupplier == null) throw new IllegalArgumentException("A client supplier is required")
        return new AnthropicModelGateway(clientSupplier)
    }

    @Override
    Message send(MessageCreateParams params) {
        return send(params, null)
    }

    /** Each attempt (the client makes up to AgentClientFactory.MAX_RETRIES + 1) is bounded by timeout when one is given. */
    @Override
    Message send(MessageCreateParams params, Duration timeout) {
        AnthropicClient client
        try {
            client = clientSupplier.call()
        } catch (IllegalStateException e) {
            // The factory's refusal (a base URL outside the allow-list) never carries the key.
            throw new ModelUnavailableException(e.message, e)
        }
        if (client == null) throw new ModelUnavailableException("No Claude API key is configured")
        if (timeout == null) return accumulate(client.messages().createStreaming(params))
        return accumulate(client.messages().createStreaming(params, RequestOptions.builder().timeout(timeout).build()))
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
