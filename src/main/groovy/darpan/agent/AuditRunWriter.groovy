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
import darpan.agent.loop.AgentRoleLoop
import darpan.agent.loop.AgentUsageLedger
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration

/**
 * DAR-BE-207. The words of the audit run update (agent.AgentWriterServices.write#AuditRunWords): Claude Haiku reads the
 * fact sheet core built (darpan.audit.AuditRunFacts) and writes a headline and one next-step sentence per cause group.
 * Code owns every number and id; what comes back is checked against the fact sheet and anything that does not hold is
 * dropped, so the email falls back to the cause's own fix text (and no headline) piece by piece, never as a whole wrong.
 *
 * Runs only where the agent is on for the tenant (the deployment switch and TenantSetting.agentEnabled = Y): the fact
 * sheet carries the tenant's order ids and cause text to Anthropic. The tenant is the one named by the caller, not the
 * session's, because the update is sent after an automation run, where no one is signed in. Never throws: no words is
 * an answer.
 */
class AuditRunWriter {
    private static final Logger logger = LoggerFactory.getLogger(AuditRunWriter.class)

    static final String MODEL = "claude-haiku-5-5"
    static final String PROMPT = "darpan/agent/prompts/audit-run-words.md"
    /** Room for adaptive thinking at low effort plus the JSON (about 1-2k tokens for a large audit). */
    static final long MAX_TOKENS = 8_000L
    /** One attempt; a run update waits for nothing else, so a slow model only delays the email. */
    static final Duration TIMEOUT = Duration.ofSeconds(60)
    static final int HEADLINE_MAX_CHARS = 400
    static final int FIX_MAX_CHARS = 300
    private static final String WHY = "audit run words: agent switch of the tenant named by the run update"

    /** Structured output. fixes is a list, not a map: structured outputs allow no free-form keys. */
    static final Map SCHEMA = [
            type                : "object",
            properties          : [
                    headline: [type: "string"],
                    fixes   : [type : "array",
                               items: [type                : "object",
                                       properties          : [key: [type: "string"], text: [type: "string"]],
                                       required            : ["key", "text"],
                                       additionalProperties: false]]],
            required            : ["headline", "fixes"],
            additionalProperties: false].asImmutable()

    /** Tests replace it; production asks for the client on each call (AnthropicModelGateway.perRequest). */
    static Closure<ModelGateway> gatewayFor = { def ec -> AnthropicModelGateway.perRequest(ec) }
    /** Tests replace it; production reads the switch ({@link #enabledFor}). */
    static Closure<Boolean> enabled = { def ec, String tenantId -> enabledFor(ec, tenantId) }

    /** {headline, fixes {"R2|cause": sentence}}; empty when the agent is off for the tenant or no usable words came back. */
    static Map write(def ec, String tenantId, Map facts) {
        if (!tenantId || !(facts?.groups instanceof List)) return [:]
        try {
            if (!enabled.call(ec, tenantId)) return [:]
            Message message = gatewayFor.call(ec).send(params(facts), TIMEOUT)
            logger.info("Audit run words for {}: {} in, {} out, {} micro-USD", tenantId, message.usage().inputTokens(),
                    message.usage().outputTokens(), AgentUsageLedger.costMicros(MODEL, message.usage()))
            StopReason stop = message.stopReason().orElse(null)
            if (stop != StopReason.END_TURN) {
                logger.warn("Audit run words for {}: the model stopped with {}; sent without words", tenantId, stop)
                return [:]
            }
            String text = message.content().find { it.isText() }?.asText()?.text()
            return checked(facts, text ? new JsonSlurper().parseText(text) : null)
        } catch (Exception e) {
            // The class only: a message could carry request detail.
            logger.warn("Audit run words for {} failed ({}); sent without words", tenantId, e.class.simpleName)
            return [:]
        }
    }

    /** The agent switch for a named tenant: the deployment property, then that tenant's TenantSetting.agentEnabled. */
    static boolean enabledFor(def ec, String tenantId) {
        String on = System.getenv(AgentSwitch.ENABLED_ENV)?.trim() ?: System.getProperty(AgentSwitch.ENABLED_PROPERTY)?.trim()
        if (!"true".equalsIgnoreCase(on)) return false
        def setting = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY)
                .condition("companyUserGroupId", tenantId).useCache(false).one()
        return setting?.agentEnabled == "Y"
    }

    /** One request: the prompt (cached), the fact sheet, low effort, the schema. Thinking left to the model's default. */
    static MessageCreateParams params(Map facts) {
        Map output = [effort: "low", format: [type: "json_schema", schema: SCHEMA]]
        return MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams([TextBlockParam.builder().text(AgentRoleLoop.systemPrompt(PROMPT))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()])
                .addUserMessage("<facts>\n" + JsonOutput.toJson(facts) + "\n</facts>")
                .outputConfig(ObjectMappers.jsonMapper().convertValue(output, OutputConfig))
                .build()
    }

    /**
     * Keeps only what holds against the fact sheet. The headline goes when it is too long, holds a {placeholder}, or names
     * a number the facts do not have (other than the count of rules with open findings). A fix goes when its key is not a
     * group's, it is blank or too long, holds a {placeholder}, or repeats one of its group's ids. Pure.
     */
    static Map checked(Map facts, Object answer) {
        if (!(answer instanceof Map)) return [:]
        Map out = [headline: null, fixes: [:]]

        Set<String> numbers = numbersIn(JsonOutput.toJson(facts))
        numbers << String.valueOf(((facts.rules ?: []) as List<Map>).count { ((it.open ?: 0) as int) > 0 })
        String headline = oneLine(answer.headline)
        if (headline && headline.length() <= HEADLINE_MAX_CHARS && !headline.contains("{") && numbers.containsAll(numbersIn(headline, true))) {
            out.headline = headline
        }

        Map<String, Map> groups = ((facts.groups ?: []) as List<Map>).collectEntries { [("${it.rule}|${it.cause ?: ''}".toString()): it] }
        ((answer.fixes instanceof List ? answer.fixes : []) as List).each { Object entry ->
            if (!(entry instanceof Map)) return
            String key = entry.key as String
            Map group = groups[key]
            String text = oneLine(entry.text)
            if (group == null || !text || out.fixes.containsKey(key)) return
            if (text.length() > FIX_MAX_CHARS || text.contains("{") || text.contains("}")) return
            if (((group.ids ?: []) as List).any { it && text.contains(it as String) }) return
            out.fixes[key] = text
        }
        return out
    }

    /**
     * Every whole number in the text, leading zeros dropped ("09" is 9). Prose may group thousands ("1,204" is 1204); JSON
     * never does, and there a comma separates values ([3,5] is 3 and 5).
     */
    static Set<String> numbersIn(String text, boolean prose = false) {
        Set<String> found = new HashSet<>()
        def matcher = prose ? (text =~ /\d{1,3}(?:,\d{3})+(?!\d)|\d+/) : (text =~ /\d+/)
        matcher.each { String m -> found << m.replace(",", "").replaceFirst(/^0+(?=\d)/, "") }
        return found
    }

    private static String oneLine(Object v) {
        return v instanceof String ? ((String) v).replaceAll(/\s+/, " ").trim() : null
    }
}
