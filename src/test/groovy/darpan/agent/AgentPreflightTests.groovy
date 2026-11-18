package darpan.agent

import com.anthropic.models.messages.MessageCreateParams
import darpan.agent.config.AgentConfig
import darpan.agent.loop.AgentRoleLoopTests
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** The edge the offline tests cannot see: the API refusing a role's request shape (grammar, schema or limit). */
class AgentPreflightTests {

    @AfterEach
    void forget() { AgentPreflight.reset() }

    static Closure<Object> ok = { AgentRoleLoopTests.msg("end_turn", [AgentRoleLoopTests.text("OK")]) }

    @Test
    void "a refused shape is remembered with the API's reason, and a passing check asks each role once"() {
        def accepted = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, ok])
        assertNull(AgentPreflight.refusal(accepted))
        assertEquals(5, accepted.sent.size(), "router, investigator, author, ticket-only author, escalation author")
        assertEquals("claude-opus-5-5", accepted.sent[4].model().asString(), "the escalation shape is checked too")
        assertNull(AgentPreflight.refusal(accepted))
        assertEquals(5, accepted.sent.size(), "asked once per JVM")

        AgentPreflight.reset()
        def refused = new AgentRoleLoopTests.ScriptedGateway([ok, ok, AgentRoleLoopTests.errors.status(400)])
        String why = AgentPreflight.refusal(refused)
        assertTrue(why?.startsWith("AUTHOR "), why)
        assertEquals(why, AgentPreflight.refusal(new AgentRoleLoopTests.ScriptedGateway([])), "remembered: nothing more is sent")
    }

    @Test
    void "a preflight check reads no setting while it holds its lock"() {
        AgentConfig cfg = AgentConfig.defaults()
        darpan.agent.config.AgentLimits lim = darpan.agent.config.AgentLimits.read()
        int reads = 0
        try {
            darpan.config.DarpanSettings.rowLoader = { -> reads++; [:] as Map<String, Map> }
            darpan.config.DarpanSettings.expire()
            def accepted = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, ok])
            assertNull(AgentPreflight.refusal(accepted, cfg, lim))
            assertEquals(5, accepted.sent.size())
            assertEquals(0, reads, "specs are built before the lock and the sends take no setting")
        } finally {
            darpan.config.DarpanSettings.resetForTest()
        }
    }

    @Test
    void "a transient failure says nothing about the shape, so it is not remembered"() {
        assertNull(AgentPreflight.refusal(new AgentRoleLoopTests.ScriptedGateway([AgentRoleLoopTests.errors.status(529)])))
        def later = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, ok])
        assertNull(AgentPreflight.refusal(later))
        assertEquals(5, later.sent.size(), "the next call asks again")
        assertTrue(later.sent.every { MessageCreateParams p -> p.maxTokens() == AgentPreflight.MAX_TOKENS })
    }

    @Test
    void "a refused escalation shape is named AUTHOR_ESCALATION"() {
        def refused = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, AgentRoleLoopTests.errors.status(404)])
        String why = AgentPreflight.refusal(refused, AgentConfig.defaults())
        assertTrue(why?.startsWith("AUTHOR_ESCALATION claude-opus-5-5"), why)
    }

    @Test
    void "preflight asks again when a turn role's model changes"() {
        def accepted = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, ok])
        assertNull(AgentPreflight.refusal(accepted, AgentConfig.defaults()))
        assertEquals(5, accepted.sent.size())

        AgentConfig typo = AgentConfig.defaults().with(models: [ROUTER: "claude-typo-1"])
        def refused = new AgentRoleLoopTests.ScriptedGateway([AgentRoleLoopTests.errors.status(404)])
        String why = AgentPreflight.refusal(refused, typo)
        assertTrue(why?.startsWith("ROUTER claude-typo-1"), why)
        assertEquals(1, refused.sent.size(), "the changed model was asked, not the remembered answer")
        assertEquals(why, AgentPreflight.refusal(new AgentRoleLoopTests.ScriptedGateway([]), typo), "the same models stay remembered")

        def back = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, ok])
        assertNull(AgentPreflight.refusal(back, AgentConfig.defaults()), "fixing the model asks again and the agent is back")
        assertEquals(5, back.sent.size())
    }
}
