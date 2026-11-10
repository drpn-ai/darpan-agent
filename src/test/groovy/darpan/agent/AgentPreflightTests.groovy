package darpan.agent

import com.anthropic.models.messages.MessageCreateParams
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
    void "a transient failure says nothing about the shape, so it is not remembered"() {
        assertNull(AgentPreflight.refusal(new AgentRoleLoopTests.ScriptedGateway([AgentRoleLoopTests.errors.status(529)])))
        def later = new AgentRoleLoopTests.ScriptedGateway([ok, ok, ok, ok, ok])
        assertNull(AgentPreflight.refusal(later))
        assertEquals(5, later.sent.size(), "the next call asks again")
        assertTrue(later.sent.every { MessageCreateParams p -> p.maxTokens() == AgentPreflight.MAX_TOKENS })
    }
}
