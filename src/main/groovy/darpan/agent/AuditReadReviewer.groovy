package darpan.agent

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import darpan.agent.claude.AnthropicModelGateway
import darpan.agent.claude.ModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigDefaults
import darpan.agent.config.AgentLimits
import darpan.agent.loop.AgentUsageLedger
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration

/**
 * DAR-BE-320 phase 1b. After an audit play whose read report has flags (darpan.audit.AuditReadReport), one staff ticket per
 * tenant, flag and connection (BrainChangeTicket, category READ_ECONOMY_EXTRACT or READ_ECONOMY_BRAIN), so the extract
 * strategy and the brain's read rules are refined from what the audits really read. Code found the flags; the model
 * (agent.AgentWriterServices.review#AuditReads, role AUDIT_READ_REVIEW, prompt audit-read-review) only adds a diagnosis, a
 * suggestion and the owner, checked against the flags. A flag that already has an open ticket is not sent again.
 *
 * The model runs only where the agent is on for the tenant (AuditRunWriter.enabledFor): the facts are endpoint ids,
 * connection ids, check codes and counts, never a record. Off, or no usable answer: the ticket is still raised, with code's
 * own sentence and the flag's default owner. Never changes config or code. Never throws.
 */
class AuditReadReviewer {
    private static final Logger logger = LoggerFactory.getLogger(AuditReadReviewer.class)

    static final List<String> OWNERS = ["EXTRACT_STRATEGY", "BRAIN_RULE"].asImmutable()
    /** With no usable review: a skipped lookup means the pairing or a filter is wrong; everything else is the read. */
    static final Map<String, String> DEFAULT_OWNER = [LOOKUP_OVER_SHARE: "BRAIN_RULE"].asImmutable()

    static final Map SCHEMA = [
            type                : "object",
            properties          : [
                    reviews: [type : "array",
                              items: [type                : "object",
                                      properties          : [key       : [type: "string"],
                                                             owner     : [type: "string", enum: OWNERS],
                                                             diagnosis : [type: "string"],
                                                             suggestion: [type: "string"]],
                                      required            : ["key", "owner", "diagnosis", "suggestion"],
                                      additionalProperties: false]]],
            required            : ["reviews"],
            additionalProperties: false].asImmutable()

    /** Tests replace it; production asks for the client on each call. */
    static Closure<ModelGateway> gatewayFor = { def ec -> AnthropicModelGateway.perRequest(ec) }
    /** Tests replace it; production reads the tenant's agent switch. */
    static Closure<Boolean> enabled = { def ec, String tenantId -> AuditRunWriter.enabledFor(ec, tenantId) }
    /** Tests replace it: the open read-economy ticket's id for a tenant and key hash, or null. */
    static Closure<String> openTicket = { def ec, String tenantId, String hash -> BrainTicketSupport.openReadEconomyId(ec, tenantId, hash) }
    /** Tests replace it: writes the ticket row, answering its id. */
    static Closure<String> insertTicket = { def ec, Map fields ->
        BrainTicketSupport.insertOnce(ec, [ticketId: TenantScopedFinder.mintId(ec, "darpan.agent.BrainChangeTicket", "ticketId"),
                                           createdDate: ec.user.nowTimestamp] + fields)
    }

    /**
     * Raises the tickets for the report's flags. Answers {tickets: [{ticketId, flag, key, duplicate}], reviewed: n} where
     * reviewed counts the flags the model's words were kept for.
     */
    static Map review(def ec, String tenantId, String auditId, Map report, AgentConfig cfg = AgentConfig.current(ec),
                      AgentLimits lim = AgentLimits.read()) {
        List<Map> flags = (report?.flags instanceof List ? report.flags : []) as List<Map>
        if (!tenantId || !flags) return [tickets: [], reviewed: 0]
        List<Map> tickets = []
        List<Map> fresh = []
        flags.each { Map f ->
            String key = keyOf(f)
            String open = openTicket.call(ec, tenantId, BrainTicketSupport.sha256(key))
            if (open) tickets << [ticketId: open, flag: f.flag, key: key, duplicate: true]
            else if (!fresh.any { keyOf(it) == key }) fresh << f
        }
        if (!fresh) return [tickets: tickets, reviewed: 0]
        Map<String, Map> reviews = ask(ec, tenantId, report, fresh, cfg, lim)
        fresh.each { Map f ->
            String key = keyOf(f)
            String id = raise(ec, tenantId, auditId, report, f, reviews[key])
            if (id) tickets << [ticketId: id, flag: f.flag, key: key, duplicate: false]
        }
        return [tickets: tickets, reviewed: reviews.size()]
    }

    /** One ticket per tenant + flag + connection: endpoint and connection, never the checks (a check added later is the same flag). */
    static String keyOf(Map flag) {
        return "read-economy|${flag.flag}|${flag.endpointEnumId ?: ''}|${flag.sourceConfigId ?: ''}".toString()
    }

    /** The model's checked reviews by flag key; empty when the agent is off, the model is unpriced, or nothing usable came back. */
    static Map<String, Map> ask(def ec, String tenantId, Map report, List<Map> flags, AgentConfig cfg, AgentLimits lim) {
        try {
            if (!enabled.call(ec, tenantId)) return [:]
            String model = cfg.model(AgentConfigDefaults.AUDIT_READ_REVIEW)
            if (cfg.price(model) == null) {
                logger.warn("Audit read review for {}: the model '{}' has no price; tickets raised without a review", tenantId, model)
                return [:]
            }
            Map facts = facts(report, flags)
            Message message = gatewayFor.call(ec).send(params(facts, cfg, lim), Duration.ofMillis(lim.auditReadReviewTimeoutMs), lim)
            logger.info("Audit read review for {}: {} in, {} out, {} micro-USD", tenantId, message.usage().inputTokens(),
                    message.usage().outputTokens(), AgentUsageLedger.costMicros(model, message.usage(), cfg))
            if (message.stopReason().orElse(null) != StopReason.END_TURN) return [:]
            String text = message.content().find { it.isText() }?.asText()?.text()
            return checked(facts, text ? new JsonSlurper().parseText(text) : null, lim)
        } catch (Exception e) {
            logger.warn("Audit read review for {} failed ({}); tickets raised without a review", tenantId, e.class.simpleName)
            return [:]
        }
    }

    /** What the model sees: the play, its totals and groups, and the flags to review, each with its key. Pure. */
    static Map facts(Map report, List<Map> flags) {
        return [reportDate: report.reportDate, period: report.period, totals: report.totals, groups: report.groups,
                flags     : flags.collect { Map f ->
                    [key   : keyOf(f), flag: f.flag, endpoint: f.endpointEnumId, connection: f.sourceConfigId, checks: f.checks,
                     detail: f.detail, text: f.text]
                }]
    }

    static MessageCreateParams params(Map facts, AgentConfig cfg, AgentLimits lim) {
        Map output = [effort: "low", format: [type: "json_schema", schema: SCHEMA]]
        return MessageCreateParams.builder()
                .model(cfg.model(AgentConfigDefaults.AUDIT_READ_REVIEW))
                .maxTokens((long) lim.auditReadReviewMaxTokens)
                .systemOfTextBlockParams([TextBlockParam.builder().text(cfg.prompt("audit-read-review"))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()])
                .addUserMessage("<facts>\n" + JsonOutput.toJson(facts) + "\n</facts>")
                .outputConfig(ObjectMappers.jsonMapper().convertValue(output, OutputConfig))
                .build()
    }

    /**
     * Keeps a review only when its key is one of the flags', its owner is known, and both texts are present, short enough,
     * free of {placeholders}, and name no number the facts do not have. The first review per key wins. Pure.
     */
    static Map<String, Map> checked(Map facts, Object answer, AgentLimits lim) {
        Map<String, Map> out = [:]
        if (!(answer instanceof Map) || !(answer.reviews instanceof List)) return out
        Set<String> keys = ((facts.flags ?: []) as List<Map>)*.key.toSet() as Set<String>
        Set<String> numbers = AuditRunWriter.numbersIn(JsonOutput.toJson(facts))
        (answer.reviews as List).each { Object r ->
            if (!(r instanceof Map)) return
            String key = r.key as String
            if (!keys.contains(key) || out.containsKey(key) || !OWNERS.contains(r.owner)) return
            String diagnosis = oneLine(r.diagnosis)
            String suggestion = oneLine(r.suggestion)
            if (![diagnosis, suggestion].every { usable(it, numbers, lim) }) return
            out[key] = [owner: r.owner, diagnosis: diagnosis, suggestion: suggestion]
        }
        return out
    }

    private static boolean usable(String text, Set<String> numbers, AgentLimits lim) {
        return text && text.length() <= lim.auditReadReviewTextMaxChars && !text.contains("{") && !text.contains("}") &&
                numbers.containsAll(AuditRunWriter.numbersIn(text, true))
    }

    /** The ticket for one flag: code's sentence, then the review's words when kept. Null when it could not be written. */
    private static String raise(def ec, String tenantId, String auditId, Map report, Map flag, Map review) {
        try {
            String owner = review?.owner ?: (DEFAULT_OWNER[flag.flag as String] ?: "EXTRACT_STRATEGY")
            String text = ([flag.text] + (review ? ["Diagnosis: ${review.diagnosis}", "Suggestion: ${review.suggestion}"] : []))
                    .join("\n\n")
            return insertTicket.call(ec, [
                    companyUserGroupId: tenantId, conversationId: null,
                    category     : owner == "BRAIN_RULE" ? "READ_ECONOMY_BRAIN" : "READ_ECONOMY_EXTRACT",
                    verbatimText : text, textHash: BrainTicketSupport.sha256(keyOf(flag)),
                    refsJson     : JsonOutput.toJson([auditId: auditId, reportDate: report.reportDate, period: report.period,
                                                      checks : flag.checks]),
                    evidenceJson : JsonOutput.toJson([flag  : flag.flag, endpoint: flag.endpointEnumId, connection: flag.sourceConfigId,
                                                      detail: flag.detail, reviewedByModel: review != null]),
                    status       : "RECEIVED"])
        } catch (Exception e) {
            logger.warn("Audit read review for {}: the {} ticket was not raised ({})", tenantId, flag.flag, e.class.simpleName)
            return null
        }
    }

    private static String oneLine(Object v) {
        return v instanceof String ? ((String) v).replaceAll(/\s+/, " ").trim() : null
    }
}
