package darpan.agent

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import darpan.agent.claude.AnthropicModelGateway
import darpan.agent.claude.ModelGateway
import darpan.agent.loop.AgentRoleLoop
import darpan.agent.loop.AgentToolExecutor
import darpan.agent.loop.AgentUsageLedger
import darpan.agent.loop.RoleResult
import darpan.agent.loop.RoleSpec
import darpan.agent.loop.TenantChangedException
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.regex.Pattern

/**
 * DAR-BE-159. One person's message to the agent, end to end, inside one request (send#AgentTurn).
 *
 *   checks   the agent is ON for the tenant; the audit (and finding, and conversation) is the tenant's and the person's;
 *            20 turns an hour per person; the tenant's daily dollar cap
 *   pin      the active tenant, once; every row this turn writes carries it, whatever another tab does
 *   scrub    keys, secrets, emails and phones out of the text (PasteScrubber) before it is stored or sent
 *   ROUTER   Haiku, no tools, never record data: items[] {kind, span, refs, confidence}, or one question
 *   OTHER    a fixed decline; no other model call
 *   INVESTIGATOR  Sonnet, read tools: typed Evidence (each value tagged HUMAN, CONFIG or TOOL) plus an answer for EXPLAIN
 *   AUTHOR   Opus: propose_change / raise_ticket / ask_human / request_more_evidence (at most 2 rounds). It reads the verbatim
 *            words, the turn's items, the Evidence (validated, typed, pruned: never the investigator's raw tool text or its
 *            prose answer) and the brain catalog in its cached prefix. DISPLAY_FEEDBACK and PRODUCT_BUG go to it with
 *            raise_ticket only.
 *
 * The whole turn is capped at 240 s: each role's wall clock is the smaller of its own and what is left, and a role that
 * would start with less than {@link #MIN_ROLE_MS} left does not start (outcome INCOMPLETE).
 *
 * Outcomes: AGENT_OFF, AGENT_UNAVAILABLE, RATE_LIMITED, OVER_BUDGET (nothing written), DECLINED, QUESTION (from the router,
 * the author's ask_human, or a proposal blocked by provenance), DONE, INCOMPLETE (a role stopped on a budget, a loop, a
 * refusal or the time cap; what it made is still returned), TENANT_CHANGED, ERROR.
 */
class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class)

    static final long OVERALL_CAP_MS = 240_000L
    static final long MIN_ROLE_MS = 5_000L
    static final BigDecimal MIN_CONFIDENCE = 0.6G
    static final int MAX_EVIDENCE_ROUNDS = 2
    static final int MAX_TEXT_CHARS = 20_000
    static final int EARLIER_TURNS = 4
    static final String TURNS_PER_HOUR_PROPERTY = "darpan.agent.turnsPerUserPerHour"
    static final int DEFAULT_TURNS_PER_HOUR = 20
    static final String DAILY_CAP_PROPERTY = "darpan.agent.dailyUsdCapPerTenant"
    static final BigDecimal DEFAULT_DAILY_CAP_USD = 25G

    static final String DECLINE = "I can only help with this audit's findings, checks and rules."
    static final String CLARIFY = "Which finding, record or statement do you mean?"

    static final Set<String> BRAIN_KINDS = (["CAUSE_FEEDBACK", "RULE_NEW", "RULE_CHANGE", "CORRECTION"] as Set<String>).asImmutable()
    static final Set<String> ANSWER_KINDS = (["EXPLAIN", "INVESTIGATION_PASTE"] as Set<String>).asImmutable()
    static final Set<String> TICKET_KINDS = (["DISPLAY_FEEDBACK", "PRODUCT_BUG"] as Set<String>).asImmutable()

    static final Map ROUTER_SCHEMA = AgentToolExecutor.schemaResource("darpan/agent/schemas/router-items.json").asImmutable()
    static final Map EVIDENCE_SCHEMA = AgentToolExecutor.schemaResource("darpan/agent/schemas/evidence.json").asImmutable()

    static final int MAX_ITEMS = 10
    static final int MAX_FACTS = 100
    static final int MAX_ANSWER_CHARS = 4_000
    private static final Pattern FIELD_NAME = Pattern.compile(/^[A-Za-z][A-Za-z0-9_.]{0,63}$/)
    /** A JSONPath field expression ($.records[*].tranId): the one non-token value Evidence keeps. */
    private static final Pattern FIELD_EXPRESSION = Pattern.compile(/^\$(?:\.[A-Za-z0-9_]{1,64}|\[\*\]|\[\d{1,4}\]){1,12}$/)
    private static final String WHY = "agent turn: pinned tenant compared on the row"

    /** A test seam: the gateway and availability this thread's send#AgentTurn uses instead of the real ones. */
    private static final ThreadLocal<Map> THREAD_OVERRIDE = new ThreadLocal<>()

    final ModelGateway gateway
    /** ec -> ON, AGENT_OFF or AGENT_UNAVAILABLE. */
    final Closure<String> availability
    /** Milliseconds now; tests move it. */
    Closure<Long> clock = { System.currentTimeMillis() }
    long overallCapMs = OVERALL_CAP_MS

    AgentOrchestrator(ModelGateway gateway, Closure<String> availability) {
        this.gateway = gateway
        this.availability = availability
    }

    /** The orchestrator send#AgentTurn runs: the real Claude gateway and availability, unless a test set this thread's. */
    static AgentOrchestrator forRequest(def ec) {
        Map o = THREAD_OVERRIDE.get()
        if (o != null) return new AgentOrchestrator((ModelGateway) o.gateway, (Closure<String>) o.availability)
        return new AgentOrchestrator(AnthropicModelGateway.perRequest(ec), { e -> AgentAvailability.state(e) })
    }

    /** Runs body with this thread's send#AgentTurn using gateway and availability (tests only; restored after). */
    static <T> T overrideForThread(ModelGateway gateway, Closure<String> availability, Closure<T> body) {
        Map previous = THREAD_OVERRIDE.get()
        THREAD_OVERRIDE.set([gateway: gateway, availability: availability])
        try {
            return body.call()
        } finally {
            if (previous == null) THREAD_OVERRIDE.remove() else THREAD_OVERRIDE.set(previous)
        }
    }

    /** One role run; tests override it to watch the specs. */
    protected RoleResult runRole(def ec, RoleSpec spec, String pinnedTenantId, List<MessageParam> messages, String turnId) {
        return AgentRoleLoop.run(ec, spec, pinnedTenantId, messages, gateway, turnId)
    }

    // ---------------------------------------------------------------- the turn

    /**
     * The person's message, answered in this request. Null with an error on ec.message when the input is refused (no
     * tenant, an audit, finding or conversation that is not the tenant's, empty or overlong text).
     */
    Map send(def ec, String conversationId, String auditId, String findingId, String text) {
        String state = availability.call(ec)
        if (state != AgentSwitch.ON) return response(conversationId, state ?: AgentSwitch.AGENT_OFF, null)

        String pinned = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = ec.user.userId as String
        if (!pinned || !userId) { ec.message.addError("No active tenant."); return null }
        if (!text?.trim()) { ec.message.addError("Say what you want to know or change."); return null }
        if (text.length() > MAX_TEXT_CHARS) { ec.message.addError("The message is longer than ${MAX_TEXT_CHARS} characters; send it in parts.".toString()); return null }
        def audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, pinned, WHY)
        if (audit == null) { ec.message.addError("Audit ${auditId} was not found.".toString()); return null }
        if (findingId && !findingOfAudit(ec, pinned, auditId, findingId)) {
            ec.message.addError("Finding ${findingId} was not found in audit ${auditId}.".toString()); return null
        }
        def conversation = null
        if (conversationId) {
            conversation = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AgentConversation", "conversationId", conversationId, pinned, WHY)
            if (conversation == null || conversation.userId != userId || (conversation.auditId && conversation.auditId != auditId)) {
                ec.message.addError("Conversation ${conversationId} was not found.".toString()); return null
            }
        }
        if (turnsInLastHour(ec, userId) >= turnsPerHour())
            return response(conversationId, "RATE_LIMITED", "You have sent ${turnsPerHour()} messages to the agent in the last hour; try again later.".toString())
        if (overDailyCap(ec, pinned)) return response(conversationId, "OVER_BUDGET", "The agent's budget for this tenant is spent for today (UTC).")

        Map scrubbed = PasteScrubber.scrub(text)
        Turn turn = new Turn(ec: ec, pinned: pinned, auditId: auditId, auditCode: audit.auditCode as String, findingId: findingId,
                words: scrubbed.text as String, masked: (List<String>) scrubbed.masked, deadline: clock.call() + overallCapMs)
        turn.conversationId = conversation == null ? createConversation(ec, pinned, userId, auditId, findingId) : conversationId
        if (conversation != null && findingId && conversation.findingId != findingId) setOpenFinding(ec, pinned, conversationId, findingId)
        turn.transcript = new Transcript(ec, pinned, turn.conversationId)
        turn.earlier = turn.transcript.earlierUserTexts(EARLIER_TURNS)
        turn.lastQuestion = turn.transcript.lastQuestion()
        // The person's words are stored before any role runs: create#AuditChangeProposal reads them as the human's text.
        turn.transcript.write("USER", null, [[type: "text", text: turn.words]])

        try {
            runTurn(turn)
        } catch (Stop stop) {
            turn.outcome = stop.outcome
            if (stop.reply) turn.replyParts << stop.reply
        } catch (Exception e) {
            logger.error("Agent turn in conversation ${turn.conversationId} failed".toString(), e)
            ec.message.clearErrors()
            turn.outcome = "ERROR"
            turn.replyParts << "Something went wrong on our side; nothing was changed."
        }
        Map out = finish(turn)
        turn.transcript.write("ASSISTANT", null, [role: "REPLY", text: out.reply, question: out.question])
        turn.transcript.write("OPERATOR", null, [outcome: out.outcome, proposalIds: ((List<Map>) out.proposals)*.proposalId,
                ticketIds: ((List<Map>) out.tickets)*.ticketId, masked: turn.masked])
        return out
    }

    private void runTurn(Turn turn) {
        List<Map> items = route(turn)
        if (items == null) return
        items = items.findAll { it.kind != "OTHER" }
        if (!items) { turn.outcome = "DECLINED"; turn.replyParts << DECLINE; return }

        boolean brain = items.any { it.kind in BRAIN_KINDS }
        boolean answer = items.any { it.kind in ANSWER_KINDS }
        Map evidence = [facts: []]
        if (brain || answer) {
            Map found = investigate(turn, items, null, [])
            evidence = [facts: found.facts]
            if (answer && found.answer) turn.replyParts << (found.answer as String)
            // With brain items the author goes on with what was found; it can ask for more evidence itself.
            if (found.stopped && !brain) { turn.outcome = "INCOMPLETE"; turn.replyParts << stoppedReply(found.stopped as String); return }
        }
        if (brain) author(turn, items, evidence, RoleSpec.author())
        else if (items.any { it.kind in TICKET_KINDS }) author(turn, items.findAll { it.kind in TICKET_KINDS }, null, RoleSpec.author().copy(tools: ["raise_ticket"]))
    }

    // ---------------------------------------------------------------- router

    /** The router's items, or null when the turn already ended on its question. */
    private List<Map> route(Turn turn) {
        RoleResult r = role(turn, RoleSpec.router(ROUTER_SCHEMA), [MessageParam.ofUser(routerInput(turn))])
        if (r.outcome != "DONE") throw new Stop("INCOMPLETE", "I could not read that message; try again.")
        Object parsed = parseJson(r.text())
        if (!(parsed instanceof Map) || AgentToolExecutor.validate(ROUTER_SCHEMA, parsed)) {
            logger.warn("Router output in conversation ${turn.conversationId} is off its schema".toString())
            throw new Stop("INCOMPLETE", "I could not read that message; try again.")
        }
        List<Map> items = ((List<Map>) ((Map) parsed).items).take(MAX_ITEMS)
        String question = ((Map) parsed).question?.toString()?.trim()
        if (question || items.any { (it.confidence as BigDecimal) < MIN_CONFIDENCE }) {
            turn.question = [text: question ?: CLARIFY, options: [], from: RoleSpec.ROUTER]
            turn.outcome = "QUESTION"
            return null
        }
        return items
    }

    private String routerInput(Turn turn) {
        StringBuilder b = new StringBuilder()
        b << "Open finding: " << (turn.findingId ?: "none") << "\n"
        if (turn.earlier) {
            b << "The person's earlier messages, oldest first:\n"
            turn.earlier.each { b << "- " << oneLine(it, 500) << "\n" }
        }
        if (turn.lastQuestion) b << "Your last question to them: " << questionFor(turn.lastQuestion, [RoleSpec.ROUTER]) << "\n"
        b << "\nThe person's message:\n<<<MESSAGE\n" << turn.words << "\nMESSAGE>>>\n"
        return b.toString()
    }

    // ---------------------------------------------------------------- investigator

    /** [facts, answer, stopped (the role's outcome when it did not finish)]. */
    private Map investigate(Turn turn, List<Map> items, String need, List<Map> priorFacts) {
        RoleSpec spec = RoleSpec.investigator().copy(outputSchema: EVIDENCE_SCHEMA)
        RoleResult r = role(turn, spec, [MessageParam.ofUser(investigatorInput(turn, items, need, priorFacts))])
        if (r.outcome != "DONE") return [facts: priorFacts, answer: null, stopped: r.outcome]
        Map typed = typedEvidence(parseJson(r.text()))
        if (typed == null) return [facts: priorFacts, answer: null, stopped: "INVALID_EVIDENCE"]
        return [facts: mergeFacts(priorFacts, (List<Map>) typed.facts), answer: typed.answer, stopped: null]
    }

    private String investigatorInput(Turn turn, List<Map> items, String need, List<Map> priorFacts) {
        StringBuilder b = new StringBuilder()
        b << "Audit: " << (turn.auditCode ?: turn.auditId) << " (auditId " << turn.auditId << ")\n"
        b << "Open finding: " << (turn.findingId ?: "none") << "\n"
        b << "Items (index from 0): " << JsonOutput.toJson(itemsView(items)) << "\n"
        b << "\nThe person's words:\n<<<MESSAGE\n" << turn.words << "\nMESSAGE>>>\n"
        if (need) b << "\nThe author needs this before it can write a change or ticket: " << oneLine(need, 500) << "\n"
        if (priorFacts) b << "Evidence so far: " << JsonOutput.toJson([facts: priorFacts]) << "\n"
        return b.toString()
    }

    /**
     * The investigator's output reduced to what the author may read: facts with only the schema's keys, each value a
     * strict token (or a JSONPath field expression) and anything else "<populated>"; the answer kept apart. Null when the
     * output is not an object with a facts list.
     */
    static Map typedEvidence(Object raw) {
        if (!(raw instanceof Map) || !(((Map) raw).facts instanceof List)) return null
        List<Map> facts = []
        ((List) ((Map) raw).facts).each { Object f ->
            if (!(f instanceof Map) || facts.size() >= MAX_FACTS) return
            Map fact = (Map) f
            String field = fact.field instanceof String ? AgentRedaction.normalize((String) fact.field) : null
            String source = fact.source as String
            if (!field || !FIELD_NAME.matcher(field).matches() || !(source in ["HUMAN", "CONFIG", "TOOL"]) || fact.value == null) return
            Map kept = [:]
            if (fact.item instanceof Integer && (fact.item as int) >= 0) kept.item = fact.item
            if (fact.subject instanceof String) kept.subject = AgentRedaction.safeValue(fact.subject)
            kept.field = field
            kept.value = typedValue(fact.value)
            kept.source = source
            facts << kept
        }
        Object answer = ((Map) raw).answer
        String text = answer instanceof String && ((String) answer).trim() ? ((String) answer).trim() : null
        if (text != null && text.length() > MAX_ANSWER_CHARS) text = text.substring(0, MAX_ANSWER_CHARS)
        return [facts: facts, answer: text]
    }

    private static Object typedValue(Object v) {
        if (v instanceof Number || v instanceof Boolean) return v
        String s = AgentRedaction.normalize(v.toString()).trim()
        if (FIELD_EXPRESSION.matcher(s).matches()) return s
        return AgentRedaction.safeValue(s)
    }

    private static List<Map> mergeFacts(List<Map> prior, List<Map> fresh) {
        List<Map> out = new ArrayList<Map>(prior ?: [])
        (fresh ?: []).each { if (!(it in out) && out.size() < MAX_FACTS) out << it }
        return out
    }

    // ---------------------------------------------------------------- author

    private void author(Turn turn, List<Map> items, Map evidence, RoleSpec spec) {
        List<ContentBlockParam> blocks = []
        if (evidence != null) {
            // The catalog is the turn's cached prefix after tools and the system prompt: sorted and id-free, so it is
            // byte-identical across this tenant's turns; everything per-turn comes after its breakpoint.
            blocks << ContentBlockParam.ofText(TextBlockParam.builder().text("This tenant's brain catalog (conclusions, rules, statements):\n" +
                    JsonOutput.toJson(brainCatalog(turn.ec))).cacheControl(CacheControlEphemeral.builder().build()).build())
        }
        blocks << ContentBlockParam.ofText(TextBlockParam.builder().text(authorInput(turn, items, evidence)).build())
        List<MessageParam> messages = [MessageParam.ofUserOfContentBlockParams(blocks)]
        int rounds = 0
        while (true) {
            RoleResult r = role(turn, spec, messages)
            if (r.outcome == "DONE") {
                if (r.text()?.trim()) turn.replyParts << r.text().trim()
                return
            }
            if (r.outcome == "QUESTION") {
                Map input = (Map) r.control.input
                turn.question = [text: input.question as String, options: (input.options ?: []) as List, from: RoleSpec.AUTHOR]
                return
            }
            if (r.outcome != "MORE_EVIDENCE") {
                turn.outcome = "INCOMPLETE"
                turn.replyParts << stoppedReply(r.outcome)
                return
            }
            rounds++
            if (rounds > MAX_EVIDENCE_ROUNDS + 1) {
                // It was told no more rounds and asked again.
                turn.outcome = "INCOMPLETE"
                turn.replyParts << "I need more evidence than one message allows; tell me which finding or record to look at."
                return
            }
            String content
            boolean isError = false
            if (rounds > MAX_EVIDENCE_ROUNDS) {
                content = "NO_MORE_EVIDENCE: ${MAX_EVIDENCE_ROUNDS} rounds used; write with what you have, or ask the person.".toString()
                isError = true
            } else {
                Map found = investigate(turn, items, ((Map) r.control.input).need as String, (List<Map>) (evidence?.facts ?: []))
                evidence = [facts: found.facts]
                content = JsonOutput.toJson([facts: found.facts])
                if (found.stopped) { content = "PARTIAL (${found.stopped}): ".toString() + content; isError = true }
            }
            List<ContentBlockParam> answers = new ArrayList<ContentBlockParam>(r.pendingToolResults)
            answers << ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(r.control.toolUseId as String)
                    .content(content).isError(isError).build())
            messages = new ArrayList<MessageParam>(r.messages)
            messages << MessageParam.ofUserOfContentBlockParams(answers)
        }
    }

    private String authorInput(Turn turn, List<Map> items, Map evidence) {
        StringBuilder b = new StringBuilder()
        b << "Audit: " << (turn.auditCode ?: turn.auditId) << " (auditId " << turn.auditId << ")\n"
        b << "Open finding: " << (turn.findingId ?: "none") << "\n"
        if (turn.earlier) {
            b << "The person's earlier messages, oldest first:\n"
            turn.earlier.each { b << "- " << oneLine(it, 500) << "\n" }
        }
        if (turn.lastQuestion) b << "The last question asked of them: " << questionFor(turn.lastQuestion, [RoleSpec.ROUTER, RoleSpec.AUTHOR]) << "\n"
        b << "\nThe person's words, verbatim:\n<<<MESSAGE\n" << turn.words << "\nMESSAGE>>>\n"
        b << "\nItems of this turn (index from 0): " << JsonOutput.toJson(itemsView(items)) << "\n"
        if (evidence != null) {
            b << "Evidence (typed facts; source says where each value came from): " << JsonOutput.toJson([facts: evidence.facts]) << "\n"
            List<Map> open = turn.transcript.conversationProposals()
            if (open) b << "Proposals already in this conversation: " << JsonOutput.toJson(open) << "\n"
        } else {
            b << "Only raise_ticket is available in this step: raise a ticket for each item with the person's words verbatim.\n"
        }
        return b.toString()
    }

    private static Map brainCatalog(def ec) {
        ec.message.pushErrors()
        try {
            Map out = ec.service.sync().name("facade.AgentReadFacadeServices.get#AgentBrainCatalog").call()
            if (ec.message.hasError()) return [conclusions: [], rules: [], statements: []]
            return [conclusions: out?.conclusions ?: [], rules: out?.rules ?: [], statements: out?.statements ?: []]
        } finally {
            ec.message.clearErrors()
            ec.message.popErrors()
        }
    }

    // ---------------------------------------------------------------- one role run

    /**
     * Starts the role's turn row, runs it within what is left of the 240 s, records its messages and what it made. Ends
     * the whole turn (Stop) on a tenant switch, Claude being unavailable, the daily cap, or too little time left.
     */
    private RoleResult role(Turn turn, RoleSpec base, List<MessageParam> messages) {
        long left = turn.deadline - clock.call()
        if (left < MIN_ROLE_MS) throw new Stop("INCOMPLETE", "I ran out of time for this message; ask again for the rest.")
        if (overDailyCap(turn.ec, turn.pinned)) throw new Stop("OVER_BUDGET", "The agent's budget for this tenant is spent for today (UTC).")
        // The switch is read again before every role: turned off mid-turn, the next role does not start.
        String state = availability.call(turn.ec)
        if (state != AgentSwitch.ON) throw new Stop(state ?: AgentSwitch.AGENT_OFF, state == AgentSwitch.AGENT_UNAVAILABLE ?
                "The agent cannot reach Claude right now; try again later." : "The agent was switched off for this tenant.")
        RoleSpec spec = left < base.wallClockMs ? base.copy(wallClockMs: left) : base
        String turnId
        try {
            turnId = AgentUsageLedger.startTurn(turn.ec, turn.pinned, turn.conversationId, spec.role, spec.model)
        } catch (TenantChangedException ignored) {
            throw new Stop("TENANT_CHANGED", null)
        }
        RoleResult r = runRole(turn.ec, spec, turn.pinned, messages, turnId)
        record(turn, spec, r, turnId)
        if (r.outcome == "TENANT_CHANGED") throw new Stop("TENANT_CHANGED", null)
        if (r.outcome == "UNAVAILABLE") throw new Stop(AgentSwitch.AGENT_UNAVAILABLE, "The agent cannot reach Claude right now; try again later.")
        return r
    }

    private void record(Turn turn, RoleSpec spec, RoleResult r, String turnId) {
        turn.transcript.write("ASSISTANT", turnId, [role: spec.role, model: spec.model, outcome: r.outcome, text: r.text(),
                control: r.control == null ? null : [tool: r.control.tool, input: r.control.input]])
        r.toolCalls.each { Map call ->
            turn.transcript.write("TOOL_RESULT", turnId, [tool: call.name, toolUseId: call.toolUseId, input: call.input,
                    isError: call.isError, content: call.content])
            if (call.isError) return
            if (call.name == "propose_change") {
                String id = outValue(call.content as String, "proposalId")
                Map view = id ? proposalView(turn.ec, turn.pinned, id) : null
                if (view) turn.proposals[id] = view
            } else if (call.name == "raise_ticket") {
                String id = outValue(call.content as String, "ticketId")
                if (id) turn.tickets[id] = [ticketId: id, category: ((Map) call.input).category, duplicate: (call.content as String).contains('"duplicate":true')]
            }
        }
    }

    /** A string out-parameter of a tool result: parsed, or found in the text when a long result was cut. */
    private static String outValue(String content, String key) {
        Object out = parseJson(content)
        if (out instanceof Map) return ((Map) out).get(key) as String
        def m = (content ?: "") =~ ('"' + key + '":"([A-Za-z0-9_\\-]{1,64})"')
        return m.find() ? m.group(1) : null
    }

    /** The proposal as its row says now (the tool result may be cut), read on the pinned tenant. */
    private static Map proposalView(def ec, String pinned, String proposalId) {
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AuditChangeProposal", "proposalId", proposalId, pinned, WHY)
        if (row == null) return null
        Object blocked = parseJson(row.blockedIds as String)
        return [proposalId: row.proposalId, kind: row.kind, status: row.status, statusMessage: row.statusMessage,
                provenanceStatus: row.provenanceStatus, blockedIds: blocked instanceof List ? blocked : [],
                backtest: parseJson(row.backtestJson as String)]
    }

    // ---------------------------------------------------------------- the response

    private Map finish(Turn turn) {
        List<Map> proposals = new ArrayList<Map>(turn.proposals.values())
        List<String> blocked = proposals.findAll { it.provenanceStatus == "BLOCKED" }.collectMany { (List<String>) it.blockedIds }.unique()
        if (blocked && turn.question == null) turn.question = [text: provenanceQuestion(blocked), options: [], from: "PROVENANCE"]
        String outcome = turn.outcome ?: (turn.question != null ? "QUESTION" : "DONE")
        if (outcome == "DONE" && turn.question != null) outcome = "QUESTION"
        Map out = response(turn.conversationId, outcome, turn.replyParts.findAll { it }.join("\n\n") ?: null)
        out.proposals = proposals
        out.tickets = new ArrayList<Map>(turn.tickets.values())
        out.question = turn.question
        out.masked = turn.masked
        return out
    }

    static final int MAX_ECHOED_IDS = 5
    static final int MAX_ECHOED_ID_CHARS = 64

    /** The question a blocked proposal turns into: at most 5 of its ids, each cut at 64 characters. */
    static String provenanceQuestion(List<String> blocked) {
        List<String> shown = blocked.take(MAX_ECHOED_IDS).collect { String id -> id.length() <= MAX_ECHOED_ID_CHARS ? id : id.substring(0, MAX_ECHOED_ID_CHARS) + "..." }
        String more = blocked.size() > MAX_ECHOED_IDS ? " (and ${blocked.size() - MAX_ECHOED_IDS} more)".toString() : ""
        return "These values came only from system or customer data, not from you: ${shown.join(', ')}${more}. If you want them in the change, type them yourself.".toString()
    }

    private static Map response(String conversationId, String outcome, String reply) {
        return [conversationId: conversationId, outcome: outcome, reply: reply, proposals: [], tickets: [], question: null, masked: []]
    }

    private static String stoppedReply(String outcome) {
        return "I stopped before finishing (${outcome}); nothing was changed beyond what is listed.".toString()
    }

    // ---------------------------------------------------------------- limits

    /**
     * Router runs (one per accepted message) the person started in the last hour, in any tenant they may enter: one count
     * over the turn joined to its conversation's userId (darpan.agent.AgentTurnOfUser), with an explicit tenant condition.
     */
    static long turnsInLastHour(def ec, String userId) {
        // The limit spans tenants, but the read still names them: the tenants this person may enter, explicitly.
        List<String> tenants = TenantAccessSupport.listAvailableTenants(ec).collect { it.userGroupId as String }.findAll { it }
        if (!tenants) return 0L
        Timestamp since = new Timestamp(System.currentTimeMillis() - 3_600_000L)
        return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentTurnOfUser", "agent rate limit: the person's own turns in their tenants")
                .condition("companyUserGroupId", EntityCondition.IN, tenants).condition("userId", userId).condition("role", RoleSpec.ROUTER)
                .condition("startedDate", EntityCondition.GREATER_THAN_EQUAL_TO, since).useCache(false).count()
    }

    static int turnsPerHour() {
        try { return Math.max(1, Integer.parseInt(System.getProperty(TURNS_PER_HOUR_PROPERTY)?.trim() ?: "")) }
        catch (NumberFormatException ignored) { return DEFAULT_TURNS_PER_HOUR }
    }

    static BigDecimal dailyCapUsd() {
        try {
            BigDecimal v = new BigDecimal(System.getProperty(DAILY_CAP_PROPERTY)?.trim() ?: "")
            return v > 0 ? v : DEFAULT_DAILY_CAP_USD
        } catch (NumberFormatException ignored) {
            return DEFAULT_DAILY_CAP_USD
        }
    }

    static boolean overDailyCap(def ec, String pinned) {
        return AgentUsageLedger.todayCostMicros(ec, pinned) >= (dailyCapUsd() * 1_000_000G).longValue()
    }

    // ---------------------------------------------------------------- rows

    /** The finding is the tenant's and sits under one of this audit's statements. */
    private static boolean findingOfAudit(def ec, String pinned, String auditId, String findingId) {
        def finding = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, pinned, WHY)
        if (finding == null) return false
        def statement = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId", finding.statementId, pinned, WHY)
        return statement != null && statement.auditId == auditId
    }

    private static String createConversation(def ec, String pinned, String userId, String auditId, String findingId) {
        String id = null
        ec.transaction.runRequireNew(null, "Could not start the agent conversation") {
            id = TenantScopedFinder.mintId(ec, "darpan.agent.AgentConversation", "conversationId")
            ec.entity.makeValue("darpan.agent.AgentConversation").setAll([conversationId: id, companyUserGroupId: pinned, userId: userId,
                    auditId: auditId, findingId: findingId, createdDate: ec.user.nowTimestamp]).create()
        }
        return id
    }

    /** The conversation now opens on another finding, so provenance reads that finding as the open context. */
    private static void setOpenFinding(def ec, String pinned, String conversationId, String findingId) {
        ec.transaction.runRequireNew(null, "Could not update the agent conversation") {
            def row = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AgentConversation", "conversationId", conversationId, pinned, WHY)
            if (row == null) throw new IllegalStateException("Conversation ${conversationId} is not the pinned tenant's".toString())
            row.findingId = findingId
            row.update()
        }
    }

    /** The conversation's messages: every write carries the pinned tenant and the next sequence number, in its own transaction. */
    static class Transcript {
        /** Newest first; a duplicate sequence number (two sends at once) falls back to write time, then id. */
        static final List<String> NEWEST_FIRST = ["-sequenceNum", "-lastUpdatedStamp", "-messageId"].asImmutable()
        final def ec
        final String pinned
        final String conversationId
        long nextSeq

        Transcript(def ec, String pinned, String conversationId) {
            this.ec = ec
            this.pinned = pinned
            this.conversationId = conversationId
            def last = rows().orderBy(NEWEST_FIRST).limit(1).list()
            nextSeq = last ? (((last[0].sequenceNum ?: 0L) as long) + 1L) : 1L
        }

        private def rows() {
            return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentMessage", WHY)
                    .condition("companyUserGroupId", pinned).condition("conversationId", conversationId).useCache(false)
        }

        void write(String kind, String turnId, Object content) {
            String json = JsonOutput.toJson(content)
            long seq = nextSeq++
            ec.transaction.runRequireNew(null, "Could not write the agent transcript") {
                ec.entity.makeValue("darpan.agent.AgentMessage").setAll([messageId: TenantScopedFinder.mintId(ec, "darpan.agent.AgentMessage", "messageId"),
                        conversationId: conversationId, companyUserGroupId: pinned, turnId: turnId, sequenceNum: seq, kind: kind,
                        contentJson: json]).create()
            }
        }

        /** The text of the person's last n messages before this one, oldest first. */
        List<String> earlierUserTexts(int n) {
            List<String> out = []
            rows().condition("kind", "USER").orderBy(NEWEST_FIRST).limit(n).list().each { msg ->
                Object parsed = parseJson(msg.contentJson as String)
                List<String> texts = []
                (parsed instanceof List ? (List) parsed : [parsed]).each { Object b ->
                    if (b instanceof Map && ((Map) b).type == "text" && ((Map) b).text instanceof String) texts << (String) ((Map) b).text
                }
                if (texts) out << texts.join(" ")
            }
            return out.reverse()
        }

        /** The last question put to the person, [text, from], or null when the last reply asked none. */
        Map lastQuestion() {
            def last = rows().condition("kind", "ASSISTANT").orderBy(NEWEST_FIRST).limit(1).list()
            if (!last) return null
            Object parsed = parseJson(last[0].contentJson as String)
            if (!(parsed instanceof Map) || ((Map) parsed).role != "REPLY" || !(((Map) parsed).question instanceof Map)) return null
            Map q = (Map) ((Map) parsed).question
            return q.text ? [text: q.text as String, from: q.from as String] : null
        }

        /** The conversation's proposals so far: what the author drafted, for a CORRECTION. */
        List<Map> conversationProposals() {
            return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AuditChangeProposal", WHY)
                    .condition("companyUserGroupId", pinned).condition("conversationId", conversationId).orderBy("proposalId")
                    .useCache(false).list().collect { row ->
                // A blocked draft's values came from records, not the person: the author is told it is blocked, not what it held.
                Map p = [proposalId: row.proposalId, kind: row.kind, status: row.status, provenanceStatus: row.provenanceStatus,
                         target: parseJson(row.targetJson as String)]
                if (row.provenanceStatus != "BLOCKED") p.after = parseJson(row.afterJson as String)
                return p
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static final String OTHER_QUESTION = "(the assistant asked a follow-up question)"

    /**
     * The last question as a role may read it: its text when it came from a role that reads no record text the reader may
     * not; otherwise a placeholder. The router reads only its own questions (the author's may name ids from evidence); the
     * author reads its own and the router's; a provenance question names ids seen only in records, so no role reads it.
     */
    static String questionFor(Map question, List<String> readable) {
        return question.from in readable ? oneLine(question.text as String, 500) : OTHER_QUESTION
    }

    private static List<Map> itemsView(List<Map> items) {
        return items.withIndex().collect { Map it, int i -> [index: i, kind: it.kind, span: it.span, refs: it.refs] as Map }
    }

    static Object parseJson(String s) {
        if (!s?.trim()) return null
        try { return new JsonSlurper().parseText(s) } catch (Exception ignored) { return null }
    }

    private static String oneLine(String s, int max) {
        String t = (s ?: "").replaceAll(/\s+/, " ").trim()
        return t.length() <= max ? t : t.substring(0, max) + "..."
    }

    /** Ends the turn early with an outcome and an optional reply. */
    static class Stop extends RuntimeException {
        final String outcome
        final String reply
        Stop(String outcome, String reply) { super(outcome, null, false, false); this.outcome = outcome; this.reply = reply }
    }

    /** One message's state while its roles run. */
    static class Turn {
        def ec
        String pinned
        String conversationId
        String auditId
        String auditCode
        String findingId
        String words
        List<String> masked = []
        List<String> earlier = []
        /** [text, from] of the last question put to the person, or null. */
        Map lastQuestion
        long deadline
        Transcript transcript
        Map<String, Map> proposals = new LinkedHashMap<>()
        Map<String, Map> tickets = new LinkedHashMap<>()
        List<String> replyParts = []
        Map question
        String outcome
    }
}
