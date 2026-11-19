package darpan.agent.config

import darpan.config.DarpanSettings
import groovy.transform.Immutable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-307: the agent's budgets, timeouts, retries and thresholds, read once per call from DarpanSettings (a settings row,
 * then the -D property of the same key, then the catalog default in darpan/settings-catalog.json, the only home of a default).
 * read() never throws: DarpanSettings falls back to the catalog default for a bad row, and the one cross-field rule
 * (backoff base below backoff max) is repaired here.
 */
@Immutable
class AgentLimits {
    private static final Logger logger = LoggerFactory.getLogger(AgentLimits)

    static final String TURNS_PER_USER_PER_HOUR = "darpan.agent.turnsPerUserPerHour"
    static final String DAILY_USD_CAP_PER_TENANT = "darpan.agent.dailyUsdCapPerTenant"
    static final String TURN_WALL_CLOCK_MS = "darpan.agent.turnWallClockMs"
    static final String EXPLAIN_MAX_REQUESTS = "darpan.agent.explainMaxRequests"
    static final String ROUTER_MAX_REQUESTS = "darpan.agent.routerMaxRequests"
    static final String ROUTER_WALL_CLOCK_MS = "darpan.agent.routerWallClockMs"
    static final String ROUTER_MAX_TOKENS = "darpan.agent.routerMaxTokens"
    static final String INVESTIGATOR_MAX_REQUESTS = "darpan.agent.investigatorMaxRequests"
    static final String INVESTIGATOR_MAX_TOOL_CALLS = "darpan.agent.investigatorMaxToolCalls"
    static final String INVESTIGATOR_WALL_CLOCK_MS = "darpan.agent.investigatorWallClockMs"
    static final String INVESTIGATOR_MAX_TOKENS = "darpan.agent.investigatorMaxTokens"
    static final String AUTHOR_MAX_REQUESTS = "darpan.agent.authorMaxRequests"
    static final String AUTHOR_MAX_TOOL_CALLS = "darpan.agent.authorMaxToolCalls"
    static final String AUTHOR_WALL_CLOCK_MS = "darpan.agent.authorWallClockMs"
    static final String AUTHOR_MAX_TOKENS = "darpan.agent.authorMaxTokens"
    static final String EMAIL_SMILE_TIMEOUT_MS = "darpan.agent.emailSmileTimeoutMs"
    static final String EMAIL_SMILE_MAX_TOKENS = "darpan.agent.emailSmileMaxTokens"
    static final String AUDIT_RUN_WORDS_TIMEOUT_MS = "darpan.agent.auditRunWordsTimeoutMs"
    static final String AUDIT_RUN_WORDS_MAX_TOKENS = "darpan.agent.auditRunWordsMaxTokens"
    static final String AUDIT_RUN_WORDS_HEADLINE_MAX_CHARS = "darpan.agent.auditRunWordsHeadlineMaxChars"
    static final String AUDIT_RUN_WORDS_FIX_MAX_CHARS = "darpan.agent.auditRunWordsFixMaxChars"
    static final String AUDIT_READ_REVIEW_TIMEOUT_MS = "darpan.agent.auditReadReviewTimeoutMs"
    static final String AUDIT_READ_REVIEW_MAX_TOKENS = "darpan.agent.auditReadReviewMaxTokens"
    static final String AUDIT_READ_REVIEW_TEXT_MAX_CHARS = "darpan.agent.auditReadReviewTextMaxChars"
    static final String GATEWAY_CONNECT_TIMEOUT_MS = "darpan.agent.gatewayConnectTimeoutMs"
    static final String GATEWAY_READ_IDLE_TIMEOUT_MS = "darpan.agent.gatewayReadIdleTimeoutMs"
    static final String REQUEST_MAX_RETRIES = "darpan.agent.requestMaxRetries"
    static final String RETRY_BACKOFF_BASE_MS = "darpan.agent.retryBackoffBaseMs"
    static final String RETRY_BACKOFF_MAX_MS = "darpan.agent.retryBackoffMaxMs"
    static final String MIN_CONFIDENCE = "darpan.agent.minConfidence"
    static final String MAX_EVIDENCE_ROUNDS = "darpan.agent.maxEvidenceRounds"
    static final String MAX_ITEMS = "darpan.agent.maxItems"
    static final String MAX_FACTS = "darpan.agent.maxFacts"
    static final String MAX_ANSWER_CHARS = "darpan.agent.maxAnswerChars"
    static final String MAX_MESSAGE_CHARS = "darpan.agent.maxMessageChars"
    static final String EARLIER_TURNS = "darpan.agent.earlierTurns"

    int turnsPerUserPerHour
    BigDecimal dailyUsdCapPerTenant
    int turnWallClockMs
    int explainMaxRequests
    int routerMaxRequests
    int routerWallClockMs
    int routerMaxTokens
    int investigatorMaxRequests
    int investigatorMaxToolCalls
    int investigatorWallClockMs
    int investigatorMaxTokens
    int authorMaxRequests
    int authorMaxToolCalls
    int authorWallClockMs
    int authorMaxTokens
    int emailSmileTimeoutMs
    int emailSmileMaxTokens
    int auditRunWordsTimeoutMs
    int auditRunWordsMaxTokens
    int auditRunWordsHeadlineMaxChars
    int auditRunWordsFixMaxChars
    int auditReadReviewTimeoutMs
    int auditReadReviewMaxTokens
    int auditReadReviewTextMaxChars
    int gatewayConnectTimeoutMs
    int gatewayReadIdleTimeoutMs
    int requestMaxRetries
    int retryBackoffBaseMs
    int retryBackoffMaxMs
    BigDecimal minConfidence
    int maxEvidenceRounds
    int maxItems
    int maxFacts
    int maxAnswerChars
    int maxMessageChars
    int earlierTurns

    /** The effective limits now. When a row written straight to the database leaves the backoff base at or above the max, both take their catalog defaults (WARN once). */
    static AgentLimits read() {
        int base = DarpanSettings.intValue(RETRY_BACKOFF_BASE_MS)
        int max = DarpanSettings.intValue(RETRY_BACKOFF_MAX_MS)
        if (base >= max) {
            logger.warn("Agent retry backoff base {} ms is not below the max {} ms; using the catalog defaults for both", base, max)
            base = DarpanSettings.catalog().get(RETRY_BACKOFF_BASE_MS).defaultValue as int
            max = DarpanSettings.catalog().get(RETRY_BACKOFF_MAX_MS).defaultValue as int
        }
        return new AgentLimits(
                turnsPerUserPerHour: DarpanSettings.intValue(TURNS_PER_USER_PER_HOUR),
                dailyUsdCapPerTenant: DarpanSettings.decimalValue(DAILY_USD_CAP_PER_TENANT),
                turnWallClockMs: DarpanSettings.intValue(TURN_WALL_CLOCK_MS),
                explainMaxRequests: DarpanSettings.intValue(EXPLAIN_MAX_REQUESTS),
                routerMaxRequests: DarpanSettings.intValue(ROUTER_MAX_REQUESTS),
                routerWallClockMs: DarpanSettings.intValue(ROUTER_WALL_CLOCK_MS),
                routerMaxTokens: DarpanSettings.intValue(ROUTER_MAX_TOKENS),
                investigatorMaxRequests: DarpanSettings.intValue(INVESTIGATOR_MAX_REQUESTS),
                investigatorMaxToolCalls: DarpanSettings.intValue(INVESTIGATOR_MAX_TOOL_CALLS),
                investigatorWallClockMs: DarpanSettings.intValue(INVESTIGATOR_WALL_CLOCK_MS),
                investigatorMaxTokens: DarpanSettings.intValue(INVESTIGATOR_MAX_TOKENS),
                authorMaxRequests: DarpanSettings.intValue(AUTHOR_MAX_REQUESTS),
                authorMaxToolCalls: DarpanSettings.intValue(AUTHOR_MAX_TOOL_CALLS),
                authorWallClockMs: DarpanSettings.intValue(AUTHOR_WALL_CLOCK_MS),
                authorMaxTokens: DarpanSettings.intValue(AUTHOR_MAX_TOKENS),
                emailSmileTimeoutMs: DarpanSettings.intValue(EMAIL_SMILE_TIMEOUT_MS),
                emailSmileMaxTokens: DarpanSettings.intValue(EMAIL_SMILE_MAX_TOKENS),
                auditRunWordsTimeoutMs: DarpanSettings.intValue(AUDIT_RUN_WORDS_TIMEOUT_MS),
                auditRunWordsMaxTokens: DarpanSettings.intValue(AUDIT_RUN_WORDS_MAX_TOKENS),
                auditRunWordsHeadlineMaxChars: DarpanSettings.intValue(AUDIT_RUN_WORDS_HEADLINE_MAX_CHARS),
                auditRunWordsFixMaxChars: DarpanSettings.intValue(AUDIT_RUN_WORDS_FIX_MAX_CHARS),
                auditReadReviewTimeoutMs: DarpanSettings.intValue(AUDIT_READ_REVIEW_TIMEOUT_MS),
                auditReadReviewMaxTokens: DarpanSettings.intValue(AUDIT_READ_REVIEW_MAX_TOKENS),
                auditReadReviewTextMaxChars: DarpanSettings.intValue(AUDIT_READ_REVIEW_TEXT_MAX_CHARS),
                gatewayConnectTimeoutMs: DarpanSettings.intValue(GATEWAY_CONNECT_TIMEOUT_MS),
                gatewayReadIdleTimeoutMs: DarpanSettings.intValue(GATEWAY_READ_IDLE_TIMEOUT_MS),
                requestMaxRetries: DarpanSettings.intValue(REQUEST_MAX_RETRIES),
                retryBackoffBaseMs: base,
                retryBackoffMaxMs: max,
                minConfidence: DarpanSettings.decimalValue(MIN_CONFIDENCE),
                maxEvidenceRounds: DarpanSettings.intValue(MAX_EVIDENCE_ROUNDS),
                maxItems: DarpanSettings.intValue(MAX_ITEMS),
                maxFacts: DarpanSettings.intValue(MAX_FACTS),
                maxAnswerChars: DarpanSettings.intValue(MAX_ANSWER_CHARS),
                maxMessageChars: DarpanSettings.intValue(MAX_MESSAGE_CHARS),
                earlierTurns: DarpanSettings.intValue(EARLIER_TURNS)
        )
    }
}
