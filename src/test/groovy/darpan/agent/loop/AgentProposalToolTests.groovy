package darpan.agent.loop

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-159. One flat, strict tool per proposal kind; the executor turns its input into the {kind, target, after,
 * reasonText, evidence} that create#AuditChangeProposal checks (AgentProvenance.validateShape), so the core is unchanged.
 * Every field of a proposal tool is required; an empty string or list means "not given" and is left out of target and
 * after, so the core reads exactly what it read when the field was absent.
 */
class AgentProposalToolTests {
    static final List<Map> EVIDENCE = [[field: "recordKey", value: "CM-1", source: "HUMAN"]]

    static Map proposal(String tool, Map input) {
        return AgentToolExecutor.proposalParams((Map) AgentToolExecutor.TOOLS[tool].proposal, input)
    }

    @Test
    void "an exclusion with an empty field expression excludes by the primary id"() {
        Map p = proposal("propose_exclusion", [savedRunId: "R1", fileSide: "FILE_1", fieldExpression: "", filterValues: ["CM-1"],
                                               reasonText: "A test memo.", evidence: EVIDENCE])
        assertEquals([kind: "EXCLUSION_FILTER", target: [savedRunId: "R1", fileSide: "FILE_1"], after: [filterValues: ["CM-1"]],
                      reasonText: "A test memo.", evidence: EVIDENCE], p)
        assertEquals('$.records[*].tranId', proposal("propose_exclusion", [savedRunId: "R1", fileSide: "FILE_1",
                fieldExpression: '$.records[*].tranId', filterValues: ["CM-1"], reasonText: "x", evidence: []]).after.fieldExpression)
    }

    @Test
    void "a narrowing keeps only its non-empty lists"() {
        Map p = proposal("propose_statement_narrowing", [auditId: "A1", checkCode: "C1", statementCode: "R1", diffTypes: [],
                                                         conclusionCodes: ["VOIDED"], ruleIds: [], reasonText: "x", evidence: []])
        assertEquals("STATEMENT_NARROWING", p.kind)
        assertEquals([auditId: "A1", checkCode: "C1", statementCode: "R1"], p.target)
        assertEquals([conclusionCodes: ["VOIDED"]], p.after)
    }

    @Test
    void "fix text maps whole"() {
        Map p = proposal("propose_fix_text", [savedRunId: "R1", conclusionCode: "C1", fixActionTemplate: "Re-send it.", reasonText: "x", evidence: []])
        assertEquals([kind: "FIX_TEXT", target: [savedRunId: "R1", conclusionCode: "C1"], after: [fixActionTemplate: "Re-send it."],
                      reasonText: "x", evidence: []], p)
    }

    @Test
    void "conclusion rows drop empty optional fields at every level"() {
        Map p = proposal("propose_conclusion_rows", [savedRunId: "R1", reasonText: "x", evidence: [], rows: [
                [conclusionCode: "VOIDED_MEMO", parentConclusionCode: "", bucket: "", fixActionTemplate: "Close it.",
                 conditions: [[fieldExpression: '$.status', operator: "EQUALS", checkValue: "Voided"],
                              [fieldExpression: '$.note', operator: "IS_EMPTY", checkValue: ""]]],
                [conclusionCode: "OTHER", parentConclusionCode: "VOIDED_MEMO", bucket: "", fixActionTemplate: "", conditions: []]]])
        assertEquals([savedRunId: "R1"], p.target)
        assertEquals([rows: [
                [conclusionCode: "VOIDED_MEMO", fixActionTemplate: "Close it.",
                 conditions: [[fieldExpression: '$.status', operator: "EQUALS", checkValue: "Voided"], [fieldExpression: '$.note', operator: "IS_EMPTY"]]],
                [conclusionCode: "OTHER", parentConclusionCode: "VOIDED_MEMO"]]], p.after)
    }

    @Test
    void "an empty required value is left out so the core refuses it as missing"() {
        Map p = proposal("propose_exclusion", [savedRunId: "", fileSide: "FILE_1", fieldExpression: "", filterValues: [], reasonText: "x", evidence: []])
        assertFalse(((Map) p.target).containsKey("savedRunId"))
        assertNull(((Map) p.after).filterValues, "no values: create#AuditChangeProposal answers that filterValues is required")
    }

    @Test
    void "every proposal tool maps to the one proposal service and is read by the author as a proposal"() {
        List<String> tools = AgentToolExecutor.TOOLS.findAll { k, v -> v.proposal != null }.keySet() as List
        assertEquals(["propose_conclusion_rows", "propose_exclusion", "propose_fix_text", "propose_statement_narrowing"], tools.sort())
        tools.each { String t ->
            assertEquals("facade.AgentChangeFacadeServices.create#AuditChangeProposal", AgentToolExecutor.serviceOf(t), t)
            assertTrue(AgentToolExecutor.isProposalTool(t), t)
        }
        assertFalse(AgentToolExecutor.isProposalTool("raise_ticket"))
    }
}
