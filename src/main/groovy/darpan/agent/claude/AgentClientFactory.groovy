package darpan.agent.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import darpan.facade.common.OutboundHttpPolicy

/**
 * DAR-BE-159. Builds the Claude client once per JVM (cached by key + base URL, so a key change is honoured).
 * Key order: env ANTHROPIC_API_KEY, then the conf property / System property darpan.agent.anthropicApiKey. Moqui copies conf
 * default-properties into System properties at startup, so System.getProperty covers both. The key is never logged,
 * never put in an exception message and never exposed by toString.
 */
class AgentClientFactory {
    static final String KEY_ENV = "ANTHROPIC_API_KEY"
    static final String KEY_PROPERTY = "darpan.agent.anthropicApiKey"
    static final String BASE_URL_PROPERTY = "darpan.agent.anthropicBaseUrl"
    static final String DEFAULT_BASE_URL = "https://api.anthropic.com"
    static final List<String> ALLOWED_HOSTS = ["api.anthropic.com"].asImmutable()

    private static final Object LOCK = new Object()
    private static String cachedFingerprint
    private static AnthropicClient cachedClient

    /** The shared client, or null when no key is configured. Throws IllegalStateException for a refused base URL. */
    static AnthropicClient client(def ec) {
        String key = resolveKey()
        if (!key) return null
        String baseUrl = resolveBaseUrl()
        String fingerprint = key + "\u0000" + baseUrl
        synchronized (LOCK) {
            if (cachedClient != null && fingerprint == cachedFingerprint) return cachedClient
            cachedClient = AnthropicOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).maxRetries(2).build()
            cachedFingerprint = fingerprint
            return cachedClient
        }
    }

    /** Forget the cached client (tests, key rotation). */
    static void reset() {
        synchronized (LOCK) { cachedClient = null; cachedFingerprint = null }
    }

    static String resolveKey() {
        return System.getenv(KEY_ENV)?.trim() ?:
                System.getProperty(KEY_PROPERTY)?.trim() ?:
                null
    }

    static String resolveBaseUrl() {
        String url = System.getProperty(BASE_URL_PROPERTY)?.trim() ?: DEFAULT_BASE_URL
        def check = OutboundHttpPolicy.validate(url, ALLOWED_HOSTS)
        if (!check.ok) throw new IllegalStateException("Claude base URL refused: " + check.error)
        return check.normalizedUrl ?: url
    }

    @Override
    String toString() { return "AgentClientFactory" }
}
