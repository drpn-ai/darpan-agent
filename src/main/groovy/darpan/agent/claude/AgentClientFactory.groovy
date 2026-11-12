package darpan.agent.claude

import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import darpan.facade.common.OutboundHttpPolicy

/**
 * DAR-BE-159. Builds the Claude client once per JVM (cached by credential + base URL, so a change is honoured).
 * Key order: env ANTHROPIC_API_KEY, then the conf property / System property darpan.agent.anthropicApiKey. Moqui copies conf
 * default-properties into System properties at startup, so System.getProperty covers both. The key is never logged,
 * never put in an exception message and never exposed by toString.
 *
 * DAR-BE-244: with no key, workload identity federation (prod): ANTHROPIC_FEDERATION_RULE_ID, ANTHROPIC_ORGANIZATION_ID,
 * ANTHROPIC_SERVICE_ACCOUNT_ID and ANTHROPIC_IDENTITY_TOKEN_FILE (ANTHROPIC_WORKSPACE_ID when the rule spans workspaces).
 * WorkloadIdentityToken keeps an AWS-signed JWT in that file and the SDK's own env chain (AnthropicBackend.fromEnv)
 * exchanges it. Some but not all of those set, or a blank ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / ANTHROPIC_PROFILE beside
 * them (each outranks federation in the SDK), is refused with IllegalStateException naming only the variables.
 */
class AgentClientFactory {
    static final String KEY_ENV = "ANTHROPIC_API_KEY"
    static final String KEY_PROPERTY = "darpan.agent.anthropicApiKey"
    static final String BASE_URL_PROPERTY = "darpan.agent.anthropicBaseUrl"
    static final String DEFAULT_BASE_URL = "https://api.anthropic.com"
    static final List<String> ALLOWED_HOSTS = ["api.anthropic.com"].asImmutable()
    static final List<String> FEDERATION_ENV = ["ANTHROPIC_FEDERATION_RULE_ID", "ANTHROPIC_ORGANIZATION_ID",
                                                "ANTHROPIC_SERVICE_ACCOUNT_ID", "ANTHROPIC_IDENTITY_TOKEN_FILE"].asImmutable()
    static final List<String> OUTRANKS_FEDERATION_ENV = ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_PROFILE"].asImmutable()

    /** Environment lookup; tests replace it (and {@link #reset} restores it). */
    static Closure<String> env = { String name -> System.getenv(name) }

    /**
     * The SDK does not retry (R55): its retries would re-run a request whose stream already started, and each attempt's
     * Timeout.request is OkHttp's callTimeout over the streamed body. AgentRoleLoop retries itself, only before any
     * streamed byte and only inside the turn's deadline.
     */
    static final int SDK_MAX_RETRIES = 0
    private static final Object LOCK = new Object()
    private static String cachedFingerprint
    private static AnthropicClient cachedClient

    /**
     * The shared client, or null when neither a key nor federation is configured. Throws IllegalStateException for a refused
     * base URL, an incomplete federation setup, or an identity token AWS would not mint.
     */
    static AnthropicClient client(def ec) {
        String key = resolveKey()
        String tokenFile = key ? null : federationTokenFile()
        if (!key && !tokenFile) return null
        String baseUrl = resolveBaseUrl()
        if (tokenFile) WorkloadIdentityToken.ensureFresh(tokenFile)
        String fingerprint = (key ?: "federation:" + tokenFile) + "\u0000" + baseUrl
        synchronized (LOCK) {
            if (cachedClient != null && fingerprint == cachedFingerprint) return cachedClient
            AnthropicClient replaced = cachedClient
            cachedClient = key ?
                    AnthropicOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).maxRetries(SDK_MAX_RETRIES).build() :
                    AnthropicOkHttpClient.builder().backend(AnthropicBackend.builder().fromEnv().baseUrl(baseUrl).build())
                            .maxRetries(SDK_MAX_RETRIES).build()
            cachedFingerprint = fingerprint
            closeQuietly(replaced)
            return cachedClient
        }
    }

    /** Forget the cached client (tests, key rotation). */
    static void reset() {
        env = { String name -> System.getenv(name) }
        synchronized (LOCK) {
            AnthropicClient old = cachedClient
            cachedClient = null; cachedFingerprint = null
            closeQuietly(old)
        }
    }

    // Releases the okhttp connection pool of a replaced client; a failed close must not break the new client.
    private static void closeQuietly(AnthropicClient old) {
        if (old == null) return
        try { old.close() } catch (Exception ignored) { }
    }

    static String resolveKey() {
        return env.call(KEY_ENV)?.trim() ?:
                System.getProperty(KEY_PROPERTY)?.trim() ?:
                null
    }

    /**
     * The identity token file when federation is fully configured, null when none of it is. Partial setups and a blank
     * variable that would outrank federation inside the SDK are refused, by variable name only.
     */
    static String federationTokenFile() {
        List<String> set = FEDERATION_ENV.findAll { env.call(it)?.trim() }
        if (set.isEmpty()) return null
        List<String> missing = FEDERATION_ENV - set
        if (missing) throw new IllegalStateException("Workload identity federation is incomplete: " + missing.join(", ") + " not set")
        List<String> outranking = OUTRANKS_FEDERATION_ENV.findAll { env.call(it) != null }
        if (outranking) throw new IllegalStateException("Unset " + outranking.join(", ") + ": it outranks workload identity federation")
        return env.call("ANTHROPIC_IDENTITY_TOKEN_FILE").trim()
    }

    static String resolveBaseUrl() {
        String url = System.getProperty(BASE_URL_PROPERTY)?.trim() ?: DEFAULT_BASE_URL
        def check = OutboundHttpPolicy.validate(url, ALLOWED_HOSTS)
        if (!check.ok) throw new IllegalStateException("Claude base URL refused: " + check.error)
        return check.normalizedUrl ?: url
    }
}
