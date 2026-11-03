package darpan.agent.claude

import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import org.junit.jupiter.api.Test

import java.util.stream.Stream

import static org.junit.jupiter.api.Assertions.*

class AnthropicModelGatewayTests {
    static class FakeStream implements StreamResponse<RawMessageStreamEvent> {
        List<RawMessageStreamEvent> events
        Throwable failAfterEvents
        int closed = 0
        FakeStream(List<RawMessageStreamEvent> events, Throwable failAfterEvents = null) { this.events = events; this.failAfterEvents = failAfterEvents }
        Stream<RawMessageStreamEvent> stream() {
            Iterator<RawMessageStreamEvent> iter = events.iterator()
            return Stream.generate {
                if (iter.hasNext()) return iter.next()
                throw failAfterEvents
            }.limit(failAfterEvents ? events.size() + 1 : events.size())
        }
        void close() { closed++ }
    }

    /** Wire-format stream events, read with the SDK's own mapper so required fields stay the SDK's business. */
    static List<RawMessageStreamEvent> helloEvents() {
        def mapper = com.anthropic.core.ObjectMappers.jsonMapper()
        return [
                '{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}',
                '{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}',
                '{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, "}}',
                '{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world"}}',
                '{"type":"content_block_stop","index":0}',
                '{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}',
                '{"type":"message_stop"}',
        ].collect { String json -> (RawMessageStreamEvent) mapper.readValue(json, RawMessageStreamEvent) }
    }

    @Test
    void "accumulates text and stop reason and closes the stream"() {
        def fake = new FakeStream(helloEvents())
        Message m = AnthropicModelGateway.accumulate(fake)
        assertEquals("Hello, world", m.content().find { it.isText() }.asText().text())
        assertEquals(StopReason.END_TURN, m.stopReason().get())
        assertEquals(1, fake.closed)
    }

    @Test
    void "closes the stream and propagates when it throws mid-way"() {
        def fake = new FakeStream(helloEvents().take(3), new IllegalArgumentException("boom"))
        def e = assertThrows(IllegalArgumentException) { AnthropicModelGateway.accumulate(fake) }
        assertEquals("boom", e.message)
        assertEquals(1, fake.closed)
    }

    @Test
    void "the client is looked up on every request and a missing one is unavailable"() {
        int lookups = 0
        def gateway = AnthropicModelGateway.supplying { lookups++; null }
        def params = com.anthropic.models.messages.MessageCreateParams.builder().model("claude-haiku-5-5").maxTokens(16L).addUserMessage("hi").build()
        assertThrows(ModelUnavailableException) { gateway.send(params) }
        assertThrows(ModelUnavailableException) { gateway.send(params) }
        assertEquals(2, lookups)
        def refused = AnthropicModelGateway.supplying { throw new IllegalStateException("Claude base URL refused: host") }
        assertEquals("Claude base URL refused: host", assertThrows(ModelUnavailableException) { refused.send(params) }.message)
    }
}
