package darpan.agent.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageDeltaUsage
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.Usage
import darpan.agent.config.AgentLimits

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

    /**
     * One attempt (the client does not retry: AgentRoleLoop does, before any streamed byte). timeout bounds the whole call,
     * streamed body included (OkHttp callTimeout), so the caller passes what is left of its deadline.
     */
    @Override
    Message send(MessageCreateParams params, Duration timeout) {
        return send(params, timeout, null)
    }

    /** As above, with the transport limits of the caller's own snapshot; null reads them from the settings (a caller that has none). */
    @Override
    Message send(MessageCreateParams params, Duration timeout, AgentLimits limits) {
        AnthropicClient client
        try {
            client = clientSupplier.call()
        } catch (IllegalStateException e) {
            // The factory's refusal (a base URL outside the allow-list) never carries the key.
            throw new ModelUnavailableException(e.message, e)
        }
        if (client == null) throw new ModelUnavailableException("No Claude API key is configured")
        if (timeout == null) return accumulate(client.messages().createStreaming(params))
        return accumulate(client.messages().createStreaming(params, RequestOptions.builder().timeout(attemptTimeout(timeout, limits)).build()))
    }

    /**
     * Connect and read-idle limits from the caller's limits snapshot (a turn carries one, so no settings read happens while a lock
     * is held); the read limit is an idle timeout, not a whole-body one (OkHttp's read timeout is per socket read, so a live stream
     * that sends events or pings keeps its whole time); the whole call at most request. Without a snapshot they are read from the
     * settings, per request.
     */
    static Timeout attemptTimeout(Duration request, AgentLimits limits = null) {
        AgentLimits lim = limits ?: AgentLimits.read()
        return Timeout.builder()
                .connect(Duration.ofMillis(lim.gatewayConnectTimeoutMs))
                .read(Duration.ofMillis(lim.gatewayReadIdleTimeoutMs))
                .request(request).build()
    }

    /**
     * Folds the stream into the final message and always closes it. A failure before the first event is rethrown as it is
     * (nothing was streamed; the caller may retry); after it, as a StreamInterruptedException carrying the usage reported so
     * far, never retried.
     */
    static Message accumulate(StreamResponse<RawMessageStreamEvent> stream) {
        MessageAccumulator accumulator = MessageAccumulator.create()
        boolean started = false
        Usage startUsage = null
        MessageDeltaUsage deltaUsage = null
        String model = null
        try {
            stream.stream().forEach { RawMessageStreamEvent event ->
                started = true
                if (event.isMessageStart()) {
                    Message m = event.messageStart().get().message()
                    startUsage = m.usage()
                    model = m.model().asString()
                } else if (event.isMessageDelta()) {
                    deltaUsage = event.messageDelta().get().usage()
                }
                accumulator.accumulate(event)
            }
        } catch (RuntimeException e) {
            if (!started) throw e
            throw new StreamInterruptedException(partialUsage(startUsage, deltaUsage), model, e)
        } finally {
            stream.close()
        }
        return accumulator.message()
    }

    /** message_start's usage with the counts a later message_delta reported (they are cumulative); null with no start. */
    static Usage partialUsage(Usage start, MessageDeltaUsage delta) {
        if (start == null) return null
        if (delta == null) return start
        Usage.Builder b = start.toBuilder().outputTokens(delta.outputTokens())
        delta.inputTokens().ifPresent { Long v -> b.inputTokens(v) }
        delta.cacheReadInputTokens().ifPresent { Long v -> b.cacheReadInputTokens(v) }
        delta.cacheCreationInputTokens().ifPresent { Long v -> b.cacheCreationInputTokens(v) }
        return b.build()
    }

    @Override
    String toString() { return "AnthropicModelGateway" }
}
