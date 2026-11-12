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
    void "a narrowing keeps only its non-empty lists, and names no causes (DAR-BE-196)"() {
        Map p = proposal("propose_statement_narrowing", [auditId: "A1", checkCode: "C1", statementCode: "R1", diffTypes: [],
                                                         ruleIds: ["RULE_7"], reasonText: "x", evidence: []])
        assertEquals("STATEMENT_NARROWING", p.kind)
        assertEquals([auditId: "A1", checkCode: "C1", statementCode: "R1"], p.target)
        assertEquals([ruleIds: ["RULE_7"]], p.after)
        Map schema = (Map) ((Map) AgentToolExecutor.TOOLS["propose_statement_narrowing"]).get("input_schema")
        assertTrue(((Map) schema.get("properties")).containsKey("ruleIds"), "the schema is the one read")
        assertFalse(((Map) schema.get("properties")).containsKey("conclusionCodes"))
    }

    @Test
    void "expected causes map whole to the check's run"() {
        Map p = proposal("propose_expected_causes", [auditId: "A1", checkCode: "C1", expectedCodes: ["VOIDED"], reasonText: "x", evidence: []])
        assertEquals([kind: "EXPECTED_CAUSES", target: [auditId: "A1", checkCode: "C1"], after: [expectedCodes: ["VOIDED"]],
                      reasonText: "x", evidence: []], p)
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
    void "the audit setup tool takes its kind from the input and sends only that kind's fields"() {
        Map settings = proposal("propose_audit_setup", [kind: "AUDIT_SETTINGS", auditId: "A1", time: "06:30", timeZone: "UTC", active: false,
                                                        reasonText: "x", evidence: EVIDENCE])
        assertEquals([kind: "AUDIT_SETTINGS", target: [auditId: "A1"], after: [time: "06:30", timeZone: "UTC", active: false],
                      reasonText: "x", evidence: EVIDENCE], settings)
        Map statement = proposal("propose_audit_setup", [kind: "ADD_STATEMENT", auditId: "A1", text: "Every return is credited.", category: "AUDC_RETURNS",
                                                         weight: "", reasonText: "x", evidence: []])
        assertEquals([kind: "ADD_STATEMENT", target: [auditId: "A1"], after: [text: "Every return is credited.", category: "AUDC_RETURNS"],
                      reasonText: "x", evidence: []], statement)
        Map check = proposal("propose_audit_setup", [kind: "ADD_CHECK", auditId: "A1", label: "Open memos", savedRunId: "R1", statementCode: "S1",
                                                     diffTypes: [], reasonText: "x", evidence: []])
        assertEquals([kind: "ADD_CHECK", target: [auditId: "A1"], after: [label: "Open memos", savedRunId: "R1", statementCode: "S1"],
                      reasonText: "x", evidence: []], check)
        Map tool = AgentToolExecutor.TOOLS["propose_audit_setup"]
        assertEquals("facade.AgentChangeFacadeServices.create#AuditChangeProposal", AgentToolExecutor.route(tool, [kind: "ADD_CHECK", auditId: "A1"]).service)
        Map schema = (Map) tool.get("input_schema")
        assertTrue(AgentToolExecutor.validate(schema, [kind: "AUDIT_SETTINGS", auditId: "A1", time: "06:30", reasonText: "x", evidence: []]).isEmpty())
        assertFalse(AgentToolExecutor.validate(schema, [kind: "EXPECTED_CAUSES", auditId: "A1", reasonText: "x", evidence: []]).isEmpty(), "only the three setup kinds")
    }

    @Test
    void "the statement edit tool names the statement in the target and sends only what changes, false included"() {
        Map off = proposal("propose_statement_edit", [auditId: "A1", statementCode: "R7", active: false, reasonText: "x", evidence: []])
        assertEquals([kind: "STATEMENT_EDIT", target: [auditId: "A1", statementCode: "R7"], after: [active: false], reasonText: "x", evidence: []], off)
        Map reword = proposal("propose_statement_edit", [auditId: "A1", statementCode: "R2", text: "Refunded returns are received within 5 days.",
                                                         reasonText: "x", evidence: EVIDENCE])
        assertEquals([kind: "STATEMENT_EDIT", target: [auditId: "A1", statementCode: "R2"], after: [text: "Refunded returns are received within 5 days."],
                      reasonText: "x", evidence: EVIDENCE], reword)
        Map schema = (Map) AgentToolExecutor.TOOLS["propose_statement_edit"].get("input_schema")
        assertTrue(AgentToolExecutor.validate(schema, [auditId: "A1", statementCode: "R2", active: true, reasonText: "x", evidence: []]).isEmpty())
        assertFalse(AgentToolExecutor.validate(schema, [auditId: "A1", active: true, reasonText: "x", evidence: []]).isEmpty(), "the statement is required")
    }

    @Test
    void "an empty required value is left out so the core refuses it as missing"() {
        Map p = proposal("propose_exclusion", [savedRunId: "", fileSide: "FILE_1", fieldExpression: "", filterValues: [], reasonText: "x", evidence: []])
        assertFalse(((Map) p.target).containsKey("savedRunId"))
        assertNull(((Map) p.after).filterValues, "no values: create#AuditChangeProposal answers that filterValues is required")
    }

    @Test
    void "get_audit_settings goes to the finding's scope when a finding is open, and to the whole audit when none is"() {
        Map tool = AgentToolExecutor.TOOLS["get_audit_settings"]
        assertEquals([service: "facade.AgentReadFacadeServices.get#AgentAuditScope", params: [findingId: "F-1"]],
                AgentToolExecutor.route(tool, [audit: "month-close", findingId: "F-1"]))
        assertEquals([service: "facade.AuditFacadeServices.get#AuditSettings", params: [audit: "month-close"]],
                AgentToolExecutor.route(tool, [audit: "month-close"]))
        assertEquals([service: "facade.AuditFacadeServices.get#AuditSettings", params: [audit: "month-close"]],
                AgentToolExecutor.route(tool, [audit: "month-close", findingId: " "]))
        assertTrue(AgentToolExecutor.validate((Map) tool.get("input_schema"), [audit: "month-close", findingId: "F-1"]).isEmpty())
    }

    @Test
    void "find_lookalikes maps to the compact lookalike read with its limit as the service allows"() {
        Map tool = AgentToolExecutor.TOOLS["find_lookalikes"]
        assertEquals([service: "facade.AgentReadFacadeServices.find#AuditFindingLookalikes", params: [findingId: "F-1", limit: 3]],
                AgentToolExecutor.route(tool, [findingId: "F-1", limit: 3]))
        assertTrue(((String) ((Map) ((Map) tool.get("input_schema")).get("properties")).limit.description).contains("1 to 10"))
    }

    @Test
    void "every proposal tool maps to the one proposal service and is read by the author as a proposal"() {
        List<String> tools = AgentToolExecutor.TOOLS.findAll { k, v -> v.proposal != null }.keySet() as List
        assertEquals(["propose_audit_setup", "propose_conclusion_rows", "propose_exclusion", "propose_expected_causes", "propose_fix_text",
                      "propose_statement_edit", "propose_statement_narrowing"], tools.sort())
        tools.each { String t ->
            assertEquals("facade.AgentChangeFacadeServices.create#AuditChangeProposal", AgentToolExecutor.serviceOf(t), t)
            assertTrue(AgentToolExecutor.isProposalTool(t), t)
        }
        assertFalse(AgentToolExecutor.isProposalTool("raise_ticket"))
    }
}
