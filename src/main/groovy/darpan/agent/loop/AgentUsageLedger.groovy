package darpan.agent.loop

import com.anthropic.models.messages.Usage
import darpan.facade.common.TenantAccessSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * DAR-BE-159. What each role run costs, on its AgentTurn row: requests, tokens and micro-dollars, written as the signed-in
 * user in the active tenant (no authz bypass). Each write commits on its own, so a turn that fails later keeps what its
 * requests already cost, and the tenant's daily cap counts it.
 */
class AgentUsageLedger {
    private static final Logger logger = LoggerFactory.getLogger(AgentUsageLedger.class)
    private static final String TURN = "darpan.agent.AgentTurn"

    /**
     * Dollars per million tokens: input, output, cache read. Dollars per MTok equal micro-dollars per token. A cache write
     * (5-minute) is priced at 1.25 x input, the API's rate. A model not listed (a fallback model) is priced as Opus, the
     * dearest, so the daily cap never under-counts.
     */
    static final Map<String, List<BigDecimal>> PRICES = [
            "claude-opus-5-5"  : [4.0G, 20.0G, 0.20G],
            "claude-sonnet-5-5": [2.0G, 10.0G, 0.20G],
            "claude-haiku-5-5" : [0.10G, 0.50G, 0.01G]].asImmutable()
    static final BigDecimal CACHE_WRITE_FACTOR = 1.25G

    /** A new AgentTurn for one role run in the active tenant; answers its turnId. */
    static String startTurn(def ec, String conversationId, String role, String model) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId) throw new IllegalStateException("No active tenant")
        String turnId = null
        ec.transaction.runRequireNew(null, "Could not start the agent turn") {
            def row = ec.entity.makeValue(TURN).setAll([conversationId: conversationId, companyUserGroupId: tenantId, role: role, model: model,
                    requestCount: 0L, inputTokens: 0L, outputTokens: 0L, cacheReadTokens: 0L, costMicros: 0L, startedDate: ec.user.nowTimestamp])
            row.setSequencedIdPrimary()
            row.create()
            turnId = row.turnId
        }
        return turnId
    }

    /** The conversation and tenant of a turn the active tenant can see, or null. */
    static Map turnOf(def ec, String turnId) {
        def row = find(ec, turnId)
        return row == null ? null : [conversationId: row.conversationId, companyUserGroupId: row.companyUserGroupId, role: row.role]
    }

    /** Adds one request's usage to the turn and answers its cost in micro-dollars. */
    static long record(def ec, String turnId, Usage usage, String model) {
        long cost = costMicros(model, usage)
        ec.transaction.runRequireNew(null, "Could not record agent usage") {
            def row = find(ec, turnId)
            if (row == null) {
                // Only when the active tenant changed mid-request: the row is then outside the active scope.
                logger.warn("Agent usage for turn ${turnId} not recorded: the turn is not in the active tenant (cost ${cost} micro-dollars)".toString())
                return
            }
            row.requestCount = ((row.requestCount ?: 0L) as long) + 1L
            row.inputTokens = ((row.inputTokens ?: 0L) as long) + usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L)
            row.outputTokens = ((row.outputTokens ?: 0L) as long) + usage.outputTokens()
            row.cacheReadTokens = ((row.cacheReadTokens ?: 0L) as long) + usage.cacheReadInputTokens().orElse(0L)
            row.costMicros = ((row.costMicros ?: 0L) as long) + cost
            row.model = model
            row.update()
        }
        return cost
    }

    /** Closes the turn with how the role ended. */
    static void finish(def ec, String turnId, String stopReason, String outcome) {
        ec.transaction.runRequireNew(null, "Could not close the agent turn") {
            def row = find(ec, turnId)
            if (row == null) return
            row.stopReason = stopReason
            row.outcome = outcome
            row.endedDate = ec.user.nowTimestamp
            row.update()
        }
    }

    /** Micro-dollars one request cost: uncached input, cache writes, output and cache reads at the model's prices. */
    static long costMicros(String model, Usage usage) {
        List<BigDecimal> p = PRICES[model] ?: PRICES["claude-opus-5-5"]
        BigDecimal total = (p[0] * usage.inputTokens()) +
                (p[0] * CACHE_WRITE_FACTOR * usage.cacheCreationInputTokens().orElse(0L)) +
                (p[1] * usage.outputTokens()) +
                (p[2] * usage.cacheReadInputTokens().orElse(0L))
        return total.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    /** What the tenant's turns started today (UTC) cost so far, in micro-dollars. */
    static long todayCostMicros(def ec, String tenantId) {
        Timestamp dayStart = Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant())
        def rows = ec.entity.find(TURN).condition("companyUserGroupId", tenantId)
                .condition("startedDate", org.moqui.entity.EntityCondition.GREATER_THAN_EQUAL_TO, dayStart)
                .selectField("costMicros").useCache(false).list()
        return rows.sum(0L) { ((it.costMicros ?: 0L) as long) } as long
    }

    // The turn row with an explicit tenant condition beside the scope filter (never entity-find-one on the full PK).
    private static def find(def ec, String turnId) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        return ec.entity.find(TURN).condition("turnId", turnId).condition("companyUserGroupId", tenantId).forUpdate(true).useCache(false).list().find()
    }
}
