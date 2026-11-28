package darpan.agent

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import darpan.agent.claude.ModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigTestSupport
import darpan.agent.config.ModelPrice
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-298. An email's playful line: what reaches the model, and the paths that leave the template's own line. */
class EmailSmileWriterTests {
    private static final def MAPPER = ObjectMappers.jsonMapper()
    private static final Closure ENABLED = EmailSmileWriter.enabled
    private static final Closure GATEWAY = EmailSmileWriter.gatewayFor

    @AfterEach
    void restore() {
        EmailSmileWriter.enabled = ENABLED
        EmailSmileWriter.gatewayFor = GATEWAY
        AgentConfigTestSupport.clearSettings()
    }

    @Test
    void "a line comes back trimmed, within the timeout, and only the brief and the example are sent"() {
        EmailSmileWriter.enabled = { def ec -> true }
        List<MessageCreateParams> sent = []
        List<Duration> timeouts = []
        EmailSmileWriter.gatewayFor = { def ec -> gateway(sent, timeouts) { message("end_turn", '{"line": "  Spare keys:   the unsung heroes. "}') } }
        assertEquals("Spare keys: the unsung heroes.", EmailSmileWriter.write(null, "DARPAN_2FA_RECOVERY_USED", "Phone in the other bag? Happens."))
        assertEquals([Duration.ofMillis(4000)], timeouts)
        String user = sent[0].messages()[0].content().toString()
        assertTrue(user.contains(EmailSmileWriter.brief(null, "DARPAN_2FA_RECOVERY_USED")), user)
        assertTrue(user.contains("<example>Phone in the other bag? Happens.</example>"), user)
        assertEquals("claude-haiku-5-5", sent[0].model().asString())
    }

    @Test
    void "the line uses the configured model, prompt, timeout and max tokens"() {
        EmailSmileWriter.enabled = { def ec -> true }
        AgentConfigTestSupport.settings(["darpan.agent.emailSmileTimeoutMs": "2500", "darpan.agent.emailSmileMaxTokens": "321"])
        List<MessageCreateParams> sent = []
        List<Duration> timeouts = []
        EmailSmileWriter.gatewayFor = { def ec -> gateway(sent, timeouts) { message("end_turn", '{"line": "Spare keys."}') } }
        AgentConfig cfg = AgentConfig.defaults().with(models: [EMAIL_SMILE: "claude-sonnet-5-5"], prompts: ["email-smile": "CONFIGURED SMILE PROMPT"])
        assertEquals("Spare keys.", AgentConfig.withConfig(cfg) { EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate.") })
        assertEquals([Duration.ofMillis(2500)], timeouts)
        assertEquals("claude-sonnet-5-5", sent[0].model().asString())
        assertEquals(321L, sent[0].maxTokens())
        assertTrue(sent[0].system().get().toString().contains("CONFIGURED SMILE PROMPT"), sent[0].system().toString())
    }

    @Test
    void "an unpriced smile model sends nothing and gives no line"() {
        EmailSmileWriter.enabled = { def ec -> true }
        EmailSmileWriter.gatewayFor = { def ec -> fail("the model was asked") }
        AgentConfig cfg = AgentConfig.defaults().with(models: [EMAIL_SMILE: "claude-unpriced-9"])
        assertNull(AgentConfig.withConfig(cfg) { EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate.") })
    }

    @Test
    void "the brief comes through brief(ec, key)"() {
        assertTrue(EmailSmileWriter.brief(null, "DARPAN_TENANT_WELCOME").contains("welcome email"))
        assertNull(EmailSmileWriter.brief(null, "DARPAN_2FA_CODES_LOCKED"))
        assertNull(EmailSmileWriter.brief(null, null))
    }

    @Test
    void "every email core asks about has a brief"() {
        ["DARPAN_PASSWORD_RESET_CODE", "DARPAN_PASSWORD_CHANGED", "DARPAN_2FA_PASSKEY_ADDED", "DARPAN_2FA_APP_ADDED",
         "DARPAN_2FA_RECOVERY_USED", "DARPAN_2FA_RESET", "DARPAN_RECOVERY_EMAIL_SET_OLD", "DARPAN_RECOVERY_EMAIL_SET_NEW",
         "DARPAN_TENANT_WELCOME"].each { assertTrue(EmailSmileWriter.brief(null, it)?.length() > 40, it) }
        assertNull(EmailSmileWriter.brief(null, "DARPAN_2FA_CODES_LOCKED"), "the alarm email has no playful line")
    }

    @Test
    void "switch off, unknown key, the example back, a refusal, a cut-off or a failure is no line"() {
        EmailSmileWriter.enabled = { def ec -> false }
        EmailSmileWriter.gatewayFor = { def ec -> fail("the model was asked") }
        assertNull(EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate."))

        EmailSmileWriter.enabled = { def ec -> fail("the switch was read") }
        assertNull(EmailSmileWriter.write(null, "DARPAN_2FA_CODES_LOCKED", "x"))
        assertNull(EmailSmileWriter.write(null, null, "x"))

        EmailSmileWriter.enabled = { def ec -> true }
        List<Duration> timeouts = []
        EmailSmileWriter.gatewayFor = { def ec -> gateway([], timeouts) { message("end_turn", '{"line": "Clean slate."}') } }
        assertNull(EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate."), "the example back is no new line")
        EmailSmileWriter.gatewayFor = { def ec -> gateway([], timeouts) { message("refusal", "") } }
        assertNull(EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate."))
        EmailSmileWriter.gatewayFor = { def ec -> gateway([], timeouts) { message("max_tokens", '{"line": "Fre') } }
        assertNull(EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate."))
        EmailSmileWriter.gatewayFor = { def ec -> gateway([], timeouts) { throw new IllegalStateException("No Claude API key is configured") } }
        assertNull(EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate."))
    }

    private static ModelGateway gateway(List<MessageCreateParams> sent, List<Duration> timeouts, Closure<Message> answer) {
        return new ModelGateway() {
            Message send(MessageCreateParams params) { sent << params; return answer.call() }
            Message send(MessageCreateParams params, Duration timeout) { sent << params; timeouts << timeout; return answer.call() }
        }
    }

    private static Message message(String stopReason, String text) {
        return MAPPER.readValue(JsonOutput.toJson([id: "msg_test", type: "message", role: "assistant", model: "claude-haiku-5-5",
                content: text ? [[type: "text", text: text]] : [], stop_reason: stopReason, stop_sequence: null,
                usage: [input_tokens: 400, output_tokens: 20]]), Message)
    }

    /**
     * OC186 (Aditi 2026-11-28): an email that serves a tenant (the welcome) records its spend on that tenant's ledger; a
     * person's own email (reset, two-factor, recovery) has no tenant to charge and stays log-only.
     */
    @Test
    void "only an email that names a tenant records its spend"() {
        List<String> recorded = []
        darpan.agent.loop.AgentUsageLedger.writerRecorder = { def ec, String tenantId, String role, def usage, String model, def cfg ->
            recorded << "${tenantId}:${role}".toString(); 1L }
        EmailSmileWriter.enabled = { def ec -> true }
        EmailSmileWriter.gatewayFor = { def ec -> new darpan.agent.claude.ModelGateway() {
            com.anthropic.models.messages.Message send(com.anthropic.models.messages.MessageCreateParams params) {
                return com.anthropic.core.ObjectMappers.jsonMapper().readValue(groovy.json.JsonOutput.toJson([id: "m", type: "message",
                        role: "assistant", model: "claude-haiku-5-5", content: [[type: "text", text: '{"line": "Welcome aboard."}']],
                        stop_reason: "end_turn", stop_sequence: null, usage: [input_tokens: 50, output_tokens: 9]]), com.anthropic.models.messages.Message)
            }
        } }
        try {
            EmailSmileWriter.write(null, "DARPAN_2FA_RESET", "Clean slate.")
            EmailSmileWriter.write(null, "DARPAN_TENANT_WELCOME", "Questions? Just reply.", "MALBON")
            assertEquals(["MALBON:EMAIL_SMILE"], recorded)
        } finally {
            darpan.agent.loop.AgentUsageLedger.resetForTests()
        }
    }
}
