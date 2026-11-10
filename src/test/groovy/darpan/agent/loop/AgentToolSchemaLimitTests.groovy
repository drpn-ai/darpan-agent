package darpan.agent.loop

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-159. Strict tool use has published limits per request, across all strict tool schemas and the JSON output schema
 * (structured-outputs docs, "Schema complexity limits"): at most 20 strict tools, 24 optional parameters (a property not
 * in its object's required list, nested included) and 16 parameters with union types (anyOf or a type array). Past them
 * the API answers 400 on every request of the role. The first paid eval (2026-11-04) hit the optional limit (31) and then
 * the unpublished compiled-grammar limit ("Schema is too complex"), which no offline count can see; the flat, all-required
 * proposal tools keep the author well inside both.
 */
class AgentToolSchemaLimitTests {

    static List<RoleSpec> roles() {
        Map anySchema = [type: "object", additionalProperties: false, required: ["x"], properties: [x: [type: "string"]]]
        return [RoleSpec.router(anySchema), RoleSpec.investigator(), RoleSpec.author(), RoleSpec.author().copy(tools: ["raise_ticket"])]
    }

    @Test
    void "every role stays within the published strict limits"() {
        roles().each { RoleSpec spec ->
            Map n = AgentToolExecutor.strictCounts(spec.tools, spec.outputSchema)
            String who = "${spec.role} ${spec.tools}: ${n}".toString()
            assertTrue((n.tools as int) <= AgentToolExecutor.STRICT_TOOL_LIMIT, who)
            assertTrue((n.optional as int) <= AgentToolExecutor.STRICT_OPTIONAL_LIMIT, who)
            assertTrue((n.unions as int) <= AgentToolExecutor.STRICT_UNION_LIMIT, who)
        }
    }

    @Test
    void "the author's strict tools have no optional parameters and no unions"() {
        // Six strict tools: a seventh (propose_expected_causes, 2026-11-06 eval) made every author request a 400.
        assertEquals([tools: 6, optional: 0, unions: 0], AgentToolExecutor.strictCounts(RoleSpec.author().tools, null))
    }

    @Test
    void "optional parameters are counted as the API counts them, nested ones included"() {
        Map schema = [type: "object", required: ["a"], properties: [
                a: [type: "string"],
                b: [type: "object", required: ["c"], properties: [c: [type: "string"], d: [type: "string"]]],
                e: [type: "array", items: [type: "object", properties: [f: [type: ["string", "null"]]]]]]]
        // b, d, e and f are not in their object's required list; f is also a union.
        assertEquals([optional: 4, unions: 1], AgentToolExecutor.schemaCounts(schema))
    }

    @Test
    void "the proposal tools are strict except propose_expected_causes, and raise_ticket is sent without strict"() {
        Map<String, Boolean> strict = AgentToolExecutor.toolDefinitions(RoleSpec.author().tools).collectEntries { [(it.name()): it.strict().orElse(false)] }
        assertEquals([ask_human: true, propose_conclusion_rows: true, propose_exclusion: true, propose_expected_causes: false,
                      propose_fix_text: true, propose_statement_narrowing: true, raise_ticket: false, request_more_evidence: true], strict)
    }

    @Test
    void "the author's model follows its system property, and only a model the ledger prices is taken"() {
        String was = System.getProperty(RoleSpec.AUTHOR_MODEL_PROPERTY)
        try {
            System.clearProperty(RoleSpec.AUTHOR_MODEL_PROPERTY)
            assertEquals("claude-sonnet-5-5", RoleSpec.author().model)
            System.setProperty(RoleSpec.AUTHOR_MODEL_PROPERTY, " claude-sonnet-5-5 ")
            RoleSpec sonnet = RoleSpec.author()
            assertEquals("claude-sonnet-5-5", sonnet.model)
            assertTrue(sonnet.adaptiveThinking(), "a Sonnet author still thinks")
            assertEquals("HIGH", sonnet.effort)
            System.setProperty(RoleSpec.AUTHOR_MODEL_PROPERTY, "claude-unknown-9")
            Exception e = assertThrows(IllegalArgumentException) { RoleSpec.author() }
            assertTrue(e.message.contains("claude-unknown-9"), e.message)
        } finally {
            if (was == null) System.clearProperty(RoleSpec.AUTHOR_MODEL_PROPERTY) else System.setProperty(RoleSpec.AUTHOR_MODEL_PROPERTY, was)
        }
    }

    @Test
    void "a role past a strict limit is refused when the spec is built"() {
        AgentToolExecutor.requireWithinStrictLimits("TEST", ["raise_ticket"], null)
        Map tooMany = [type: "object", additionalProperties: false, properties: (1..25).collectEntries { [("p${it}".toString()): [type: "string"]] }]
        Exception e = assertThrows(IllegalArgumentException) { AgentToolExecutor.requireWithinStrictLimits("TEST", [], tooMany) }
        assertTrue(e.message.contains("25 optional parameters"), e.message)
    }
}
