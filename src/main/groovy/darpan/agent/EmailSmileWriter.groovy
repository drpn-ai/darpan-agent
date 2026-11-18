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
import darpan.agent.loop.AgentRoleLoop
import darpan.agent.loop.AgentUsageLedger
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration

/**
 * DAR-BE-298. The one playful line of a Darpan account email (agent.AgentWriterServices.write#EmailSmile), by Claude Haiku
 * at send time. Core (darpan.email.EmailSmile) asks for it by key and checks the answer again; on any miss it sends the
 * template's own line.
 *
 * What goes to the model: the prompt, the key's brief (below) and the template's own line. Nothing about the reader or a
 * tenant, so the switch is the deployment's alone (darpan.agent.enabled / DARPAN_AGENT_ENABLED), not a tenant's
 * agentEnabled. A key with no brief gets nothing. Never throws: no line is an answer.
 */
class EmailSmileWriter {
    private static final Logger logger = LoggerFactory.getLogger(EmailSmileWriter.class)

    static final String MODEL = "claude-haiku-5-5"
    static final String PROMPT = "darpan/agent/prompts/email-smile.md"
    static final long MAX_TOKENS = 200L
    /** The reset code is sent while the reader waits on the sign-in page: a slow model must not hold it up. */
    static final Duration TIMEOUT = Duration.ofSeconds(4)

    static final Map SCHEMA = [type: "object", properties: [line: [type: "string"]], required: ["line"],
                               additionalProperties: false].asImmutable()

    /** Each key's brief: what the email is about and where the line sits. Keys are core's EmailTemplate ids. */
    static final Map<String, String> BRIEFS = [
            DARPAN_PASSWORD_RESET_CODE   : "A password reset code email. Your line follows \"Forgot your Darpan password?\" and comes before \"Here's your way back in:\" and the code. Be kind about forgetting.",
            DARPAN_PASSWORD_CHANGED      : "The reader's password was just changed. Your line opens the email, right before \"It changed on <time>, and every other device got signed out on the way.\"",
            DARPAN_2FA_PASSKEY_ADDED     : "A passkey was added to the reader's account, so they can sign in with a touch or a glance instead of a password. Your line opens the email, right before the passkey's name and \"can now sign you in to Darpan with a touch or a glance\".",
            DARPAN_2FA_APP_ADDED         : "An authenticator app was set up for the reader's account. Your line ends a paragraph that says the app and Darpan are a pair and \"A new code every 30 seconds.\"",
            DARPAN_2FA_RECOVERY_USED     : "The reader signed in with a one-time recovery code, the spare key for when their phone or passkey isn't to hand. Your line follows \"A recovery code got you into Darpan on <time>.\" Be kind: it happens.",
            DARPAN_2FA_RESET             : "An administrator reset the reader's two-factor sign-in, so they set it up again next time. Your line opens the email, right before \"A Darpan administrator reset your two-factor on <time>.\"",
            DARPAN_RECOVERY_EMAIL_SET_OLD: "This inbox is no longer the reader's backup address for password resets; a new address replaced it. Your line ends \"Reset codes go to your new address now.\" A fond farewell to the old inbox.",
            DARPAN_RECOVERY_EMAIL_SET_NEW: "This inbox is now the reader's backup address for password resets. Your line ends \"Done. This inbox is now your spare key to Darpan.\"",
            DARPAN_TENANT_WELCOME        : "A welcome email to someone just given access to a retailer's workspace in Darpan. Your line ends the closing \"Questions? Just reply. A real person reads this.\"",
    ].asImmutable()

    /** Tests replace it; production asks for the client on each call. */
    static Closure<ModelGateway> gatewayFor = { def ec -> AnthropicModelGateway.perRequest(ec) }
    /** Tests replace it; production reads the deployment switch. */
    static Closure<Boolean> enabled = { def ec -> deploymentOn() }

    /** The line, or null when the agent is off, the key has no brief, or nothing usable came back. */
    static String write(def ec, String key, String fallback) {
        String brief = key ? BRIEFS[key] : null
        if (!brief) return null
        try {
            if (!enabled.call(ec)) return null
            Message message = gatewayFor.call(ec).send(params(brief, fallback), TIMEOUT)
            logger.info("Email smile {}: {} in, {} out, {} micro-USD", key, message.usage().inputTokens(),
                    message.usage().outputTokens(), AgentUsageLedger.costMicros(MODEL, message.usage()))
            if (message.stopReason().orElse(null) != StopReason.END_TURN) return null
            String text = message.content().find { it.isText() }?.asText()?.text()
            Object answer = text ? new JsonSlurper().parseText(text) : null
            String line = answer instanceof Map ? (answer.line as String)?.replaceAll(/\s+/, " ")?.trim() : null
            return line && line != fallback?.trim() ? line : null
        } catch (Exception e) {
            // The class only: a message could carry request detail.
            logger.warn("Email smile {} failed ({}); the template's line goes", key, e.class.simpleName)
            return null
        }
    }

    static boolean deploymentOn() {
        String on = System.getenv(AgentSwitch.ENABLED_ENV)?.trim() ?: System.getProperty(AgentSwitch.ENABLED_PROPERTY)?.trim()
        return "true".equalsIgnoreCase(on)
    }

    /** One request: the prompt (cached), the brief and the template's line, the schema. No thinking: one short line. */
    static MessageCreateParams params(String brief, String fallback) {
        Map output = [format: [type: "json_schema", schema: SCHEMA]]
        return MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams([TextBlockParam.builder().text(AgentConfig.current(null).promptForPath(PROMPT))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()])
                .addUserMessage("<brief>${brief}</brief>\n<example>${fallback ?: ''}</example>".toString())
                .outputConfig(ObjectMappers.jsonMapper().convertValue(output, OutputConfig))
                .build()
    }
}
