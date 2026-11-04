package darpan.agent.loop

import com.anthropic.models.messages.Usage
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.moqui.entity.EntityCondition

import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * DAR-BE-159. What each role run costs, on its AgentTurn row: requests, tokens and micro-dollars, written as the signed-in
 * user to the turn of the tenant pinned at turn start. The row is found by id through TenantScopedFinder and its owner
 * compared in code, so a tenant switched in another tab neither hides the row nor lets a request go uncounted. Each write
 * commits on its own, so a turn that fails later keeps what its requests already cost, and the daily cap counts it.
 */
class AgentUsageLedger {
    private static final String TURN = "darpan.agent.AgentTurn"
    private static final String CONVERSATION = "darpan.agent.AgentConversation"
    /** The turn is the pinned tenant's, compared on the row: the active tenant may have changed in another tab. */
    private static final String WHY = "agent usage: pinned tenant compared on the row"

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

    /**
     * A new AgentTurn for one role run of the pinned tenant; answers its turnId. The pinned tenant must still be the active
     * one and own the conversation.
     */
    static String startTurn(def ec, String pinnedTenantId, String conversationId, String role, String model) {
        if (!pinnedTenantId || TenantAccessSupport.currentActiveTenantUserGroupId(ec) != pinnedTenantId)
            throw new TenantChangedException()
        if (TenantScopedFinder.findOneOwnedByTenant(ec, CONVERSATION, "conversationId", conversationId, pinnedTenantId, WHY) == null)
            throw new IllegalArgumentException("Conversation ${conversationId} is not the tenant's".toString())
        String turnId = null
        ec.transaction.runRequireNew(null, "Could not start the agent turn") {
            def row = ec.entity.makeValue(TURN).setAll([conversationId: conversationId, companyUserGroupId: pinnedTenantId, role: role, model: model,
                    requestCount: 0L, inputTokens: 0L, outputTokens: 0L, cacheReadTokens: 0L, cacheWriteTokens: 0L, costMicros: 0L, startedDate: ec.user.nowTimestamp])
            row.setSequencedIdPrimary()
            row.create()
            turnId = row.turnId
        }
        return turnId
    }

    /** The conversation and role of the pinned tenant's turn, or null when the turn is not that tenant's. */
    static Map turnOf(def ec, String turnId, String pinnedTenantId) {
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, TURN, "turnId", turnId, pinnedTenantId, WHY)
        return row == null ? null : [conversationId: row.conversationId, companyUserGroupId: row.companyUserGroupId, role: row.role]
    }

    /**
     * Adds one request's usage to the pinned tenant's turn and answers its cost in micro-dollars. Recorded even when the
     * active tenant changed during the request (the money is spent either way), so a tab switch cannot dodge the daily cap.
     */
    static long record(def ec, String pinnedTenantId, String turnId, Usage usage, String model) {
        long cost = costMicros(model, usage)
        ec.transaction.runRequireNew(null, "Could not record agent usage") {
            def row = owned(ec, turnId, pinnedTenantId)
            row.requestCount = ((row.requestCount ?: 0L) as long) + 1L
            row.inputTokens = ((row.inputTokens ?: 0L) as long) + usage.inputTokens()
            row.outputTokens = ((row.outputTokens ?: 0L) as long) + usage.outputTokens()
            row.cacheReadTokens = ((row.cacheReadTokens ?: 0L) as long) + usage.cacheReadInputTokens().orElse(0L)
            row.cacheWriteTokens = ((row.cacheWriteTokens ?: 0L) as long) + usage.cacheCreationInputTokens().orElse(0L)
            row.costMicros = ((row.costMicros ?: 0L) as long) + cost
            row.model = model
            row.update()
        }
        return cost
    }

    /** Closes the pinned tenant's turn with how the role ended. */
    static void finish(def ec, String pinnedTenantId, String turnId, String stopReason, String outcome) {
        ec.transaction.runRequireNew(null, "Could not close the agent turn") {
            def row = owned(ec, turnId, pinnedTenantId)
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

    /** What the pinned tenant's turns started today (UTC) cost so far, in micro-dollars, whichever tenant is active. */
    static long todayCostMicros(def ec, String pinnedTenantId) {
        if (!pinnedTenantId) return 0L
        Timestamp dayStart = Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant())
        def rows = TenantScopedFinder.findGlobalUnscoped(ec, TURN, WHY).condition("companyUserGroupId", pinnedTenantId)
                .condition("startedDate", EntityCondition.GREATER_THAN_EQUAL_TO, dayStart)
                .selectField("costMicros").useCache(false).list()
        return rows.sum(0L) { ((it.costMicros ?: 0L) as long) } as long
    }

    // The pinned tenant's turn, read by id and owner-checked in code (TenantScopedFinder; never find-one with a tenant
    // condition beside the full PK). Missing means a caller bug, not a tab switch: it is thrown, never skipped.
    private static def owned(def ec, String turnId, String pinnedTenantId) {
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, TURN, "turnId", turnId, pinnedTenantId, WHY)
        if (row == null) throw new IllegalStateException("Agent turn ${turnId} is not the pinned tenant's".toString())
        return row
    }
}
