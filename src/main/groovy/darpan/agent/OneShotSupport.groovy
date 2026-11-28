package darpan.agent

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import groovy.json.JsonSlurper

/**
 * DAR-OPS-029 S44a. What the three one-shot writers (AuditRunWriter, AuditReadReviewer, EmailSmileWriter) share: one structured
 * request with a cached system prompt, the answer read back only from a finished turn, the deployment switch, and their
 * one-line text rule. Each writer keeps its own facts, schema, checks and fallbacks. Pure, except deploymentOn.
 */
class OneShotSupport {

    /** One request: the prompt (cached), the user text, and the structured-output config (a format, and an effort when given). */
    static MessageCreateParams params(String model, long maxTokens, String prompt, String userText, Map output) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams([TextBlockParam.builder().text(prompt).cacheControl(CacheControlEphemeral.builder().build()).build()])
                .addUserMessage(userText)
                .outputConfig(ObjectMappers.jsonMapper().convertValue(output, OutputConfig))
                .build()
    }

    /** The parsed JSON answer of a turn that ended on END_TURN; null for any other stop or no text. Throws on unparsable text. */
    static Object answer(Message message) {
        if (message.stopReason().orElse(null) != StopReason.END_TURN) return null
        String text = message.content().find { it.isText() }?.asText()?.text()
        return text ? new JsonSlurper().parseText(text) : null
    }

    /** The deployment's agent switch (DARPAN_AGENT_ENABLED, else darpan.agent.enabled), before any tenant's own. */
    static boolean deploymentOn() {
        String on = System.getenv(AgentSwitch.ENABLED_ENV)?.trim() ?: System.getProperty(AgentSwitch.ENABLED_PROPERTY)?.trim()
        return "true".equalsIgnoreCase(on)
    }

    /** A model's text as one line: whitespace runs to one space, trimmed; null for anything but a String. */
    static String oneLine(Object v) {
        return v instanceof String ? ((String) v).replaceAll(/\s+/, " ").trim() : null
    }
}
