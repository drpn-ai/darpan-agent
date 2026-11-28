package darpan.agent

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import darpan.agent.claude.AnthropicModelGateway
import darpan.agent.claude.ModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigDefaults
import darpan.agent.config.AgentLimits
import darpan.agent.loop.AgentUsageLedger
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
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
 * session's, because the update is sent after an automation run, where no one is signed in. The model and prompt are the
 * agent config's, the timeout, token and length caps the settings (read once per call); a model with no price sends nothing.
 * Never throws: no words is an answer.
 */
class AuditRunWriter {
    private static final Logger logger = LoggerFactory.getLogger(AuditRunWriter.class)

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
    static Map write(def ec, String tenantId, Map facts, AgentConfig cfg = AgentConfig.current(ec), AgentLimits lim = AgentLimits.read()) {
        if (!tenantId || !(facts?.groups instanceof List)) return [:]
        try {
            if (!enabled.call(ec, tenantId)) return [:]
            String model = cfg.model(AgentConfigDefaults.AUDIT_RUN_WORDS)
            if (cfg.price(model) == null) {
                logger.warn("Audit run words for {}: the model '{}' has no price; sent without words", tenantId, model)
                return [:]
            }
            Message message = gatewayFor.call(ec).send(params(facts, cfg, lim), Duration.ofMillis(lim.auditRunWordsTimeoutMs), lim)
            // OC186: an answer is spend on the tenant's ledger, refused or cut too (it was billed)
            long cost = AgentUsageLedger.recordWriter(ec, tenantId, AgentConfigDefaults.AUDIT_RUN_WORDS, message.usage(),
                    message.model()?.asString() ?: model, cfg)
            logger.info("Audit run words for {}: {} in, {} out, {} micro-USD", tenantId, message.usage().inputTokens(),
                    message.usage().outputTokens(), cost)
            StopReason stop = message.stopReason().orElse(null)
            if (stop != StopReason.END_TURN) {
                logger.warn("Audit run words for {}: the model stopped with {}; sent without words", tenantId, stop)
                return [:]
            }
            return checked(facts, OneShotSupport.answer(message), lim)
        } catch (Exception e) {
            // The class only: a message could carry request detail.
            logger.warn("Audit run words for {} failed ({}); sent without words", tenantId, e.class.simpleName)
            return [:]
        }
    }

    /** The agent switch for a named tenant: the deployment property, then that tenant's TenantSetting.agentEnabled. */
    static boolean enabledFor(def ec, String tenantId) {
        if (!OneShotSupport.deploymentOn()) return false
        def setting = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY)
                .condition("companyUserGroupId", tenantId).useCache(false).one()
        return setting?.agentEnabled == "Y"
    }

    /** One request: the prompt (cached), the fact sheet, low effort, the schema. Thinking left to the model's default. */
    static MessageCreateParams params(Map facts, AgentConfig cfg, AgentLimits lim) {
        return OneShotSupport.params(cfg.model(AgentConfigDefaults.AUDIT_RUN_WORDS), (long) lim.auditRunWordsMaxTokens,
                cfg.prompt("audit-run-words"), "<facts>\n" + JsonOutput.toJson(facts) + "\n</facts>",
                [effort: "low", format: [type: "json_schema", schema: SCHEMA]])
    }

    /**
     * Keeps only what holds against the fact sheet. The headline goes when it is too long, holds a {placeholder}, or names
     * a number the facts do not have (other than the count of rules with open findings). A fix goes when its key is not a
     * group's, it is blank or too long, holds a {placeholder}, or repeats one of its group's ids. Pure.
     */
    static Map checked(Map facts, Object answer, AgentLimits lim = AgentLimits.read()) {
        if (!(answer instanceof Map)) return [:]
        Map out = [headline: null, fixes: [:]]

        Set<String> numbers = numbersIn(JsonOutput.toJson(facts))
        numbers << String.valueOf(((facts.rules ?: []) as List<Map>).count { ((it.open ?: 0) as int) > 0 })
        String headline = OneShotSupport.oneLine(answer.headline)
        if (headline && headline.length() <= lim.auditRunWordsHeadlineMaxChars && !headline.contains("{") && numbers.containsAll(numbersIn(headline, true))) {
            out.headline = headline
        }

        Map<String, Map> groups = ((facts.groups ?: []) as List<Map>).collectEntries { [("${it.rule}|${it.cause ?: ''}".toString()): it] }
        ((answer.fixes instanceof List ? answer.fixes : []) as List).each { Object entry ->
            if (!(entry instanceof Map)) return
            String key = entry.key as String
            Map group = groups[key]
            String text = OneShotSupport.oneLine(entry.text)
            if (group == null || !text || out.fixes.containsKey(key)) return
            if (text.length() > lim.auditRunWordsFixMaxChars || text.contains("{") || text.contains("}")) return
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
}
