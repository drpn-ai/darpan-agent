package darpan.agent.claude

import com.anthropic.models.messages.Usage

/**
 * A streamed response failed after its first event arrived (R55). The request is billed, so it carries the usage the stream
 * had reported (message_start, updated by any message_delta), or null when none came, and it is never retried.
 */
class StreamInterruptedException extends RuntimeException {
    final Usage usage
    final String model

    StreamInterruptedException(Usage usage, String model, Throwable cause) {
        super("The response stream failed after it started: ${cause?.class?.simpleName}".toString(), cause)
        this.usage = usage
        this.model = model
    }
}
