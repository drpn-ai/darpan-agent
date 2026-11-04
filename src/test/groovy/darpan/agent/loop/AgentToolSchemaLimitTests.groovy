package darpan.agent.loop

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-159. The Messages API refuses a request whose strict tools hold more than 24 optional parameters in all (a
 * property not in its object's required list, nested ones included): "Schemas contains too many optional parameters (31)
 * ... (limit: 24)", the 400 every author request got in the first paid eval (2026-11-04). No offline test sent a real
 * request, so this pins the limit for every role's tool set.
 */
class AgentToolSchemaLimitTests {

    static List<RoleSpec> roles() {
        return [RoleSpec.router(), RoleSpec.investigator(), RoleSpec.author(), RoleSpec.author().copy(tools: ["raise_ticket"])]
    }

    @Test
    void "every role's strict tools stay within the API's optional-parameter limit"() {
        roles().each { RoleSpec spec ->
            int n = AgentToolExecutor.strictOptionalParameters(spec.tools)
            assertTrue(n <= AgentToolExecutor.STRICT_OPTIONAL_LIMIT, "${spec.role} ${spec.tools}: ${n} optional parameters in strict tools".toString())
        }
        assertEquals(20, AgentToolExecutor.strictOptionalParameters(RoleSpec.author().tools), "propose_change 19 + ask_human 1")
    }

    @Test
    void "the count is the API's: the author's four tools all strict held 31"() {
        assertEquals(31, AgentToolExecutor.optionalParameters(RoleSpec.author().tools))
    }

    @Test
    void "propose_change stays strict and raise_ticket is sent without strict"() {
        Map<String, Boolean> strict = AgentToolExecutor.toolDefinitions(["propose_change", "raise_ticket", "ask_human"])
                .collectEntries { [(it.name()): it.strict().orElse(false)] }
        assertEquals([ask_human: true, propose_change: true, raise_ticket: false], strict)
    }

    @Test
    void "a role whose strict tools pass the limit is refused when the spec is built"() {
        // RoleSpec calls this with STRICT_OPTIONAL_LIMIT; a lower limit stands in for a schema that grew.
        AgentToolExecutor.requireWithinStrictLimit("TEST", ["propose_change"], 19)
        Exception e = assertThrows(IllegalArgumentException) { AgentToolExecutor.requireWithinStrictLimit("TEST", ["propose_change"], 18) }
        assertTrue(e.message.contains("19 optional parameters"), e.message)
    }
}
