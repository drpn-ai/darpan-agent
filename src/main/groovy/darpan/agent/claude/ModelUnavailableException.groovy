package darpan.agent.claude

/** No Claude client can be built for this request (no key, or a refused base URL). The message never carries the key. */
class ModelUnavailableException extends RuntimeException {
    ModelUnavailableException(String message, Throwable cause = null) { super(message, cause) }
}
