package darpan.agent

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import darpan.agent.claude.AnthropicModelGateway
import darpan.agent.claude.ModelGateway
import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentLimits
import darpan.agent.config.AgentTextDefaults
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
 *            the turnsPerUserPerHour setting per person; the tenant's daily dollar cap
 *   pin      the active tenant, once; every row this turn writes carries it, whatever another tab does
 *   scrub    keys, secrets, emails and phones out of the text (PasteScrubber) before it is stored or sent
 *   ROUTER   no thinking, no tools, never record data: items[] {kind, span, refs, confidence}, or one question
 *   OTHER    a fixed decline; no other model call
 *   INVESTIGATOR  read tools: typed Evidence (each value tagged HUMAN, CONFIG or TOOL) plus an answer for EXPLAIN
 *   AUTHOR   propose_* (one tool per proposal kind) / raise_ticket / ask_human / request_more_evidence (at most 2 rounds). It reads the verbatim
 *            words, the turn's items, the Evidence (validated, typed, pruned: never the investigator's raw tool text or its
 *            prose answer) and the brain catalog in its cached prefix. DISPLAY_FEEDBACK and PRODUCT_BUG go to it with
 *            raise_ticket only.
 *
 * Every role's model, prompt and price is data (AgentConfig), and its budgets are settings (AgentLimits); both are read once at the start of the turn.
 *
 * The whole turn is capped at the turnWallClockMs setting: each role's wall clock is the smaller of its own and what is left, and a role that
 * would start with less than {@link #MIN_ROLE_MS} left does not start (outcome INCOMPLETE).
 *
 * Outcomes: AGENT_OFF, AGENT_UNAVAILABLE, AI_NOT_GRANTED, AI_EXPIRED, AI_PAUSED (DAR-BE-194: the person has no live grant of
 * AI access in this tenant; nothing written, or, mid-turn, the next role does not start), RATE_LIMITED, OVER_BUDGET (nothing written), DECLINED, QUESTION (from the router,
 * the author's ask_human, or a proposal blocked by provenance), DONE, INCOMPLETE (a role stopped on a budget, a loop, a
 * refusal or the time cap; what it made is still returned), TENANT_CHANGED, ERROR.
 */
class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class)

    /** A role that could not send one request in time (AgentRoleLoop.MIN_REQUEST_TIMEOUT_MS) does not start. */
    static final long MIN_ROLE_MS = AgentRoleLoop.MIN_REQUEST_TIMEOUT_MS

    static final Set<String> BRAIN_KINDS = (["CAUSE_FEEDBACK", "RULE_NEW", "RULE_CHANGE", "CORRECTION", "SETUP"] as Set<String>).asImmutable()
    static final Set<String> ANSWER_KINDS = (["EXPLAIN", "INVESTIGATION_PASTE"] as Set<String>).asImmutable()
    static final Set<String> TICKET_KINDS = (["DISPLAY_FEEDBACK", "PRODUCT_BUG"] as Set<String>).asImmutable()

    static final Map ROUTER_SCHEMA = AgentToolExecutor.schemaResource("darpan/agent/schemas/router-items.json").asImmutable()
    static final Map EVIDENCE_SCHEMA = AgentToolExecutor.schemaResource("darpan/agent/schemas/evidence.json").asImmutable()

    private static final Pattern FIELD_NAME = Pattern.compile(/^[A-Za-z][A-Za-z0-9_.]{0,63}$/)
    /** A JSONPath field expression ($.records[*].tranId): the one non-token value Evidence keeps. */
    private static final Pattern FIELD_EXPRESSION = Pattern.compile(/^\$(?:\.[A-Za-z0-9_]{1,64}|\[\*\]|\[\d{1,4}\]){1,12}$/)
    private static final String WHY = "agent turn: pinned tenant compared on the row"

    /** A test seam: the gateway and availability this thread's send#AgentTurn uses instead of the real ones. */
    private static final ThreadLocal<Map> THREAD_OVERRIDE = new ThreadLocal<>()

    final ModelGateway gateway
    /** (ec, the turn's AgentConfig, the turn's AgentLimits) -> ON, AGENT_OFF, AGENT_UNAVAILABLE or AGENT_REFUSED. */
    final Closure<String> availability
    /** Milliseconds now; tests move it. */
    Closure<Long> clock = { System.currentTimeMillis() }

    AgentOrchestrator(ModelGateway gateway, Closure<String> availability) {
        this.gateway = gateway
        this.availability = availability
    }

    /** The orchestrator send#AgentTurn runs: the real Claude gateway and availability, unless a test set this thread's. */
    static AgentOrchestrator forRequest(def ec) {
        Map o = THREAD_OVERRIDE.get()
        if (o != null) return new AgentOrchestrator((ModelGateway) o.gateway, (Closure<String>) o.availability)
        return new AgentOrchestrator(AnthropicModelGateway.perRequest(ec), { e, c, l -> AgentAvailability.state(e, c, l) })
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
     * tenant, a person without edit access, an audit, finding or conversation that is not the tenant's, empty or overlong
     * text). DAR-BE-292: only an editor or tenant admin talks to the agent, so every draft it makes can be planned at once.
     */
    Map send(def ec, String conversationId, String auditId, String findingId, String text, String auditCode = null) {
        return sendTurn(ec, conversationId, auditId, findingId, text, auditCode, false)
    }

    /**
     * DAR-BE-334. One message from the queries page: the same gates as an audit turn (switch, pricing, write access, AI
     * access grant, rate and budget caps, masking), no audit, and one role (RoleSpec.queryAuthor) that reads the tenant's
     * query catalog and proposes a query that has already run; nothing is saved until the person saves it.
     */
    Map sendQuery(def ec, String conversationId, String text, String readSpecJson = null, String scriptBody = null) {
        return sendTurn(ec, conversationId, null, null, text, null, true, [readSpecJson: readSpecJson, scriptBody: scriptBody])
    }

    private Map sendTurn(def ec, String conversationId, String auditId, String findingId, String text, String auditCode, boolean query,
                         Map draft = null) {
        // DAR-BE-307: one config and one limits snapshot for the whole turn, taken first and never inside a lock; every check
        // and role below (availability included) reads from these two, so an edit made mid-turn waits for the next turn.
        AgentConfig cfg = AgentConfig.current(ec)
        AgentLimits lim = AgentLimits.read()
        String state = availability.call(ec, cfg, lim)
        if (state != AgentSwitch.ON) return response(conversationId, state ?: AgentSwitch.AGENT_OFF, unavailableReply(cfg, state))
        // A role whose model has no price never sends: its spend could not be counted. Checked on this turn's own snapshot.
        if (AgentAvailability.pricingRefusal(cfg)) return response(conversationId, AgentAvailability.AGENT_REFUSED, unavailableReply(cfg, AgentAvailability.AGENT_REFUSED))

        String pinned = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = ec.user.userId as String
        if (!pinned || !userId) { ec.message.addError("No active tenant."); return null }
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Only editors and tenant admins can talk to the agent.")) return null
        if (!text?.trim()) { ec.message.addError("Say what you want to know or change."); return null }
        if (text.length() > lim.maxMessageChars) { ec.message.addError("The message is longer than ${lim.maxMessageChars} characters; send it in parts.".toString()); return null }
        def audit = null
        if (!query) {
            if (!auditId == !auditCode) { ec.message.addError("Name the audit by its id or by its code, not both."); return null }
            audit = auditId ? TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, pinned, WHY)
                    : TenantScopedFinder.findTenantScoped(ec, "darpan.audit.Audit").condition("auditCode", auditCode).useCache(false).one()
            if (audit == null || audit.companyUserGroupId != pinned) { ec.message.addError("Audit ${auditId ?: auditCode} was not found.".toString()); return null }
            // DAR-UI-104: the audit page names its audit by code; everything below keys on the row id.
            auditId = audit.auditId as String
            if (findingId && !findingOfAudit(ec, pinned, auditId, findingId)) {
                ec.message.addError("Finding ${findingId} was not found in audit ${auditId}.".toString()); return null
            }
        }
        def conversation = null
        if (conversationId) {
            conversation = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AgentConversation", "conversationId", conversationId, pinned, WHY)
            // DAR-BE-334: a query conversation has no audit, and an audit conversation always has one; neither continues the other
            if (conversation == null || conversation.userId != userId || (query ? conversation.auditId : conversation.auditId != auditId)) {
                ec.message.addError("Conversation ${conversationId} was not found.".toString()); return null
            }
        }
        // DAR-BE-194: the person needs a live grant of AI access in this tenant (a Darpan admin gives it); nothing is written otherwise.
        Map access = AgentAccess.access(ec, pinned, userId)
        if (access.state != AgentAccess.LIVE) return response(conversationId, accessOutcome(access), accessReply(cfg, access))
        int turnLimit = lim.turnsPerUserPerHour
        if (turnsInLastHour(ec, userId) >= turnLimit)
            return response(conversationId, "RATE_LIMITED", cfg.text("reply.RATE_LIMITED", [count: turnLimit]))
        if (overDailyCap(ec, pinned, cfg, lim)) return response(conversationId, "OVER_BUDGET", cfg.text("reply.OVER_BUDGET"))

        Map scrubbed = PasteScrubber.scrub(text)
        Turn turn = new Turn(ec: ec, pinned: pinned, userId: userId, grantId: access.grantId as String, auditId: auditId, auditCode: audit?.auditCode as String, findingId: findingId, query: query,
                words: scrubbed.text as String, masked: (List<String>) scrubbed.masked, draft: query ? scrubDraft(draft) : null, deadline: clock.call() + lim.turnWallClockMs, cfg: cfg, limits: lim)
        boolean fresh = conversation == null
        turn.conversationId = fresh ? TenantScopedFinder.mintId(ec, "darpan.agent.AgentConversation", "conversationId") : conversationId
        turn.transcript = transcriptFor(ec, pinned, turn.conversationId)
        turn.earlier = turn.transcript.earlierUserTexts(lim.earlierTurns)
        turn.lastQuestion = turn.transcript.lastQuestion()

        try {
            // The person's words are stored before any role runs: create#AuditChangeProposal reads them as the human's text.
            // A new conversation, its open finding and the words land in one transaction, so a failed write leaves no
            // empty conversation behind and the turn ends ERROR before any model call.
            ec.transaction.runRequireNew(null, "Could not store the message to the agent") {
                if (fresh) createConversation(ec, turn.conversationId, pinned, userId, auditId, findingId)
                else if (findingId && conversation.findingId != findingId) setOpenFinding(ec, pinned, conversationId, findingId)
                turn.transcript.write("USER", null, [[type: "text", text: turn.words]])
            }
        } catch (Exception e) {
            logger.error("Agent message in conversation ${turn.conversationId} could not be stored".toString(), e)
            ec.message.clearErrors()
            return response(fresh ? null : conversationId, "ERROR", cfg.text("reply.NOT_STORED"))
        }

        turn.priorIds = turn.transcript.madeIds()
        try {
            if (query) runQueryTurn(turn) else runTurn(turn)
        } catch (Stop stop) {
            turn.outcome = stop.outcome
            if (stop.reply) turn.replyParts << stop.reply
        } catch (Exception e) {
            logger.error("Agent turn in conversation ${turn.conversationId} failed".toString(), e)
            ec.message.clearErrors()
            turn.outcome = "ERROR"
            adoptCommitted(turn)
            turn.replyParts << failedReply(turn)
        }
        Map out = finish(turn)
        try {
            turn.transcript.write("ASSISTANT", null, [role: "REPLY", text: out.reply, question: out.question])
            Map operator = [outcome: out.outcome, proposalIds: ((List<Map>) out.proposals)*.proposalId,
                            ticketIds: ((List<Map>) out.tickets)*.ticketId, masked: turn.masked]
            // The turn's typed facts and the finding they were found for, so a correction that follows can reuse them.
            if (turn.facts != null) operator.putAll([findingId: turn.findingId, facts: turn.facts])
            turn.transcript.write("OPERATOR", null, operator)
        } catch (Exception e) {
            // What the turn made is already committed: the person still gets the reply and its proposal and ticket ids.
            logger.error("Agent reply in conversation ${turn.conversationId} could not be stored".toString(), e)
            ec.message.clearErrors()
        }
        return out
    }

    // ---------------------------------------------------------------- the panel's reads

    /**
     * DAR-BE-223. What the panel asks before it shows the box: state (ON, AGENT_OFF, AGENT_UNAVAILABLE or AGENT_REFUSED),
     * grantState (the person's AI access here: LIVE, NOT_GRANTED, EXPIRED or PAUSED) and canApprove (the person may save the
     * audit's checks, so may approve a draft). Null with an error when there is no active tenant.
     */
    Map status(def ec) {
        String pinned = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = ec.user.userId as String
        if (!pinned || !userId) { ec.message.addError("No active tenant."); return null }
        return [state: availability.call(ec, AgentConfig.current(ec), AgentLimits.read()) ?: AgentSwitch.AGENT_OFF, grantState: AgentAccess.access(ec, pinned, userId).state,
                canApprove: TenantAccessSupport.canEditActiveTenantData(ec)]
    }

    /**
     * DAR-BE-223. The person's own conversation: auditId, findingId, messages {messageId, kind USER or ASSISTANT, text,
     * question} and its proposals as send#AgentTurn lists them, with their status now. Another person's or another
     * tenant's conversation reads as not found, like a missing one.
     */
    Map conversation(def ec, String conversationId) {
        String pinned = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = ec.user.userId as String
        if (!pinned || !userId) { ec.message.addError("No active tenant."); return null }
        def row = conversationId ? TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AgentConversation", "conversationId", conversationId, pinned, WHY) : null
        if (row == null || row.userId != userId) { ec.message.addError("Conversation ${conversationId} was not found.".toString()); return null }
        Transcript transcript = transcriptFor(ec, pinned, conversationId)
        List<Map> proposals = transcript.madeIds().proposals.collect { proposalView(ec, pinned, it) }.findAll { it != null }
        return [auditId: row.auditId, findingId: row.findingId, messages: transcript.messages(), proposals: proposals]
    }

    /** The conversation's transcript; tests override it to make a write fail. */
    protected Transcript transcriptFor(def ec, String pinned, String conversationId) {
        return new Transcript(ec, pinned, conversationId)
    }

    /**
     * A turn that failed may have made proposals or tickets the turn never took in (a role threw after its tool ran): the
     * conversation's rows this turn added are taken in, so the reply and the response list them.
     */
    private static void adoptCommitted(Turn turn) {
        try {
            Map<String, List<String>> now = turn.transcript.madeIds()
            now.proposals.findAll { !(it in turn.priorIds.proposals) && !turn.proposals.containsKey(it) }.each { String id ->
                Map view = proposalView(turn.ec, turn.pinned, id)
                if (view) turn.proposals[id] = view
            }
            now.tickets.findAll { !(it in turn.priorIds.tickets) && !turn.tickets.containsKey(it) }.each { String id ->
                turn.tickets[id] = [ticketId: id, category: null, duplicate: false]
            }
        } catch (Exception e) {
            logger.error("Agent turn in conversation ${turn.conversationId}: could not read what it made".toString(), e)
            turn.ec.message.clearErrors()
        }
    }

    /**
     * The reply of a turn that failed: what it had already drafted is committed (a tool runs in its own transaction), so
     * the reply lists it instead of claiming nothing was changed. Drafts apply only once a person approves them.
     */
    static String failedReply(Turn turn) {
        if (!turn.proposals && !turn.tickets) return turn.cfg.text("reply.FAILED")
        List<String> made = []
        if (turn.proposals) made << "proposals ${turn.proposals.keySet().join(', ')}".toString()
        if (turn.tickets) made << "tickets ${turn.tickets.keySet().join(', ')}".toString()
        return turn.cfg.text("reply.FAILED_AFTER_DRAFTING", [made: made.join(' and ')])
    }

    private void runTurn(Turn turn) {
        List<Map> items = route(turn)
        if (items == null) return
        items = items.findAll { it.kind != "OTHER" }
        if (!items) { turn.outcome = "DECLINED"; turn.replyParts << turn.cfg.text("reply.DECLINE"); return }

        boolean brain = items.any { it.kind in BRAIN_KINDS }
        boolean answer = items.any { it.kind in ANSWER_KINDS }
        Map evidence = [facts: []]
        // A turn of corrections only, on the finding the last facts were found for, reuses them: the investigator would
        // re-read the same records. The author can still ask for more (request_more_evidence).
        List<Map> carried = items.every { it.kind == "CORRECTION" } ? turn.transcript.lastFacts(turn.findingId, turn.limits) : null
        if (carried) {
            evidence = [facts: carried, carried: true]
            turn.facts = carried
        } else if (brain || answer) {
            // A follow-up on the same finding starts from the facts already found, and reads only what is missing.
            List<Map> earlier = turn.findingId ? turn.transcript.lastFacts(turn.findingId, turn.limits) : null
            Map found = investigate(turn, items, null, earlier ?: [])
            // A question deserves an answer: one more request from the facts already found, before any reply goes out empty.
            if (answer && !found.answer && !found.stopped) {
                Map again = investigate(turn, items, turn.cfg.text("notice.ANSWER_MISSING"), (List<Map>) found.facts)
                found = again.stopped ? found : again
            }
            evidence = [facts: found.facts]
            turn.facts = (List<Map>) found.facts
            if (answer && found.answer) turn.replyParts << (found.answer as String)
            // With brain items the author goes on with what was found; it can ask for more evidence itself.
            if (found.stopped && !brain) { turn.outcome = "INCOMPLETE"; turn.replyParts << stoppedReply(turn.cfg, found.stopped as String); return }
        }
        if (brain) authorWithEscalation(turn, items, evidence)
        else if (items.any { it.kind in TICKET_KINDS }) author(turn, items.findAll { it.kind in TICKET_KINDS }, null, RoleSpec.author(turn.cfg, turn.limits).copy(tools: ["raise_ticket"]))
    }

    // ---------------------------------------------------------------- queries (DAR-BE-334)

    /**
     * One query-author run: it reads the catalog, proposes, sees how the proposal ran (typed, no rows) and may fix and
     * propose again; the last proposal that ran is the turn's. Its reply is its own words; a question goes to the person.
     */
    private void runQueryTurn(Turn turn) {
        StringBuilder b = new StringBuilder()
        if (turn.earlier) {
            b << "The person's earlier messages, oldest first:\n"
            turn.earlier.each { b << "- " << oneLine(it, 500) << "\n" }
        }
        if (turn.lastQuestion) b << "The last question asked of them: " << questionFor(turn.lastQuestion, [RoleSpec.QUERY_AUTHOR]) << "\n"
        b << "\nThe person's words, verbatim:\n<<<MESSAGE\n" << turn.words << "\nMESSAGE>>>\n"
        if (turn.draft?.readSpecJson || turn.draft?.scriptBody) {
            // The person's own draft (they may have edited it by hand): build on it rather than starting again.
            b << "\nThe query on the page now, as the person left it:\n<<<READ\n" << (turn.draft.readSpecJson ?: "") << "\nREAD>>>\n"
            b << "<<<SCRIPT\n" << (turn.draft.scriptBody ?: "") << "\nSCRIPT>>>\n"
        }
        RoleResult r = role(turn, RoleSpec.queryAuthor(turn.cfg, turn.limits), [MessageParam.ofUser(b.toString())])
        turn.queryProposal = lastQueryProposal(r.toolCalls)
        if (r.outcome == "DONE") {
            if (r.text()?.trim()) turn.replyParts << r.text().trim()
        } else if (r.outcome == "QUESTION") {
            Map input = (Map) r.control.input
            turn.question = [text: input.question as String, options: (input.options ?: []) as List, from: RoleSpec.QUERY_AUTHOR]
        } else {
            turn.outcome = "INCOMPLETE"
            turn.replyParts << stoppedReply(turn.cfg, r.outcome)
        }
    }

    /** The page's draft, masked like the message and cut to 8,000 characters a part. */
    private static Map scrubDraft(Map draft) {
        if (!draft) return null
        Closure<String> part = { Object v ->
            String s = v?.toString()
            if (!s?.trim()) return null
            String masked = PasteScrubber.scrub(s).text as String
            return masked.length() > 8_000 ? masked.take(8_000) : masked
        }
        return [readSpecJson: part(draft.readSpecJson), scriptBody: part(draft.scriptBody)]
    }

    /** The last propose_query call that ran (status PROPOSED): {proposal, preview}; null when none did. */
    static Map lastQueryProposal(List<Map> toolCalls) {
        Map found = null
        (toolCalls ?: []).each { Map call ->
            if (call.name != "propose_query" || call.isError) return
            Object out
            try { out = new groovy.json.JsonSlurper().parseText((call.full ?: call.content ?: "") as String) } catch (Exception ignored) { out = null }
            if (out instanceof Map && ((Map) out).status == "PROPOSED") found = [proposal: ((Map) out).proposal, preview: ((Map) out).preview]
        }
        return found
    }

    // ---------------------------------------------------------------- router

    /** The router's items, or null when the turn already ended on its question. */
    private List<Map> route(Turn turn) {
        RoleResult r = role(turn, RoleSpec.router(ROUTER_SCHEMA, turn.cfg, turn.limits), [MessageParam.ofUser(routerInput(turn))])
        if (r.outcome != "DONE") throw new Stop("INCOMPLETE", "I could not read that message; try again.")
        Object parsed = parseJson(r.text())
        if (!(parsed instanceof Map) || AgentToolExecutor.validate(ROUTER_SCHEMA, parsed)) {
            logger.warn("Router output in conversation ${turn.conversationId} is off its schema".toString())
            throw new Stop("INCOMPLETE", "I could not read that message; try again.")
        }
        List<Map> items = ((List<Map>) ((Map) parsed).items).take(turn.limits.maxItems)
        String question = ((Map) parsed).question?.toString()?.trim()
        // With a finding open the person's "it" or "this" has its referent, so a low score alone is no reason to ask which
        // finding they mean (the first paid eval ended a plain exclusion on that question); a question the router wrote
        // itself is still asked.
        boolean unsure = !turn.findingId && items.any { (it.confidence as BigDecimal) < turn.limits.minConfidence }
        if (question || unsure) {
            turn.question = [text: question ?: turn.cfg.text("reply.CLARIFY"), options: [], from: RoleSpec.ROUTER]
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
        RoleSpec spec = RoleSpec.investigator(turn.cfg, turn.limits).copy(outputSchema: EVIDENCE_SCHEMA)
        // A why-question needs a short look, not the full read budget.
        if (items && items.every { it.kind == "EXPLAIN" }) spec = spec.copy(maxRequests: Math.min(spec.maxRequests, turn.limits.explainMaxRequests))
        RoleResult r = role(turn, spec, [MessageParam.ofUser(investigatorInput(turn, items, need, priorFacts))])
        if (r.outcome != "DONE") return [facts: priorFacts, answer: null, stopped: r.outcome]
        Map typed = typedEvidence(parseJson(r.text()), turn.limits)
        if (typed == null) return [facts: priorFacts, answer: null, stopped: "INVALID_EVIDENCE"]
        return [facts: mergeFacts(priorFacts, (List<Map>) typed.facts, turn.limits.maxFacts), answer: typed.answer, stopped: null]
    }

    private String investigatorInput(Turn turn, List<Map> items, String need, List<Map> priorFacts) {
        StringBuilder b = new StringBuilder()
        b << "Audit: " << (turn.auditCode ?: turn.auditId) << " (auditId " << turn.auditId << ")\n"
        b << "Open finding: " << (turn.findingId ?: "none") << "\n"
        b << "Items (index from 0): " << JsonOutput.toJson(itemsView(items)) << "\n"
        b << "\nThe person's words:\n<<<MESSAGE\n" << turn.words << "\nMESSAGE>>>\n"
        if (need) b << "\nThe author needs this before it can write a change or ticket: " << oneLine(need, 500) << "\n"
        if (priorFacts) {
            b << "Evidence so far: " << JsonOutput.toJson([facts: priorFacts]) << "\n"
            b << "These were found earlier in this conversation. Read only what is missing from them; do not read the same record again.\n"
        }
        return b.toString()
    }

    /**
     * The investigator's output reduced to what the author may read: facts with only the schema's keys, each value a
     * strict token (or a JSONPath field expression) and anything else "<populated>"; the answer kept apart. Null when the
     * output is not an object with a facts list.
     */
    static Map typedEvidence(Object raw, AgentLimits lim) {
        if (!(raw instanceof Map) || !(((Map) raw).facts instanceof List)) return null
        List<Map> facts = []
        ((List) ((Map) raw).facts).each { Object f ->
            if (!(f instanceof Map) || facts.size() >= lim.maxFacts) return
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
        if (text != null && text.length() > lim.maxAnswerChars) text = text.substring(0, lim.maxAnswerChars)
        return [facts: facts, answer: text]
    }

    private static Object typedValue(Object v) {
        if (v instanceof Number || v instanceof Boolean) return v
        String s = AgentRedaction.normalize(v.toString()).trim()
        if (FIELD_EXPRESSION.matcher(s).matches()) return s
        return AgentRedaction.safeValue(s)
    }

    private static List<Map> mergeFacts(List<Map> prior, List<Map> fresh, int maxFacts) {
        List<Map> out = new ArrayList<Map>(prior ?: [])
        (fresh ?: []).each { if (!(it in out) && out.size() < maxFacts) out << it }
        return out
    }

    // ---------------------------------------------------------------- author

    /**
     * The author on its default model; when it would end the turn on a question to the person and drafted nothing, the same
     * turn is written again on the escalation model first, and only that one's question (if it still asks) goes out.
     * Eval 2026-11-10: on a new shared cause Sonnet asked in 2 of 3 runs where Opus raised the ticket every time.
     */
    private void authorWithEscalation(Turn turn, List<Map> items, Map evidence) {
        int made = turn.proposals.size() + turn.tickets.size()
        author(turn, items, evidence, RoleSpec.author(turn.cfg, turn.limits))
        RoleSpec stronger = RoleSpec.escalationAuthor(turn.cfg, turn.limits)
        boolean doubtful = turn.outcome == null && turn.question?.from == RoleSpec.AUTHOR && turn.proposals.size() + turn.tickets.size() == made
        if (!doubtful || stronger == null) return
        turn.question = null
        Map latest = turn.facts != null ? [facts: turn.facts, carried: evidence?.carried] : evidence
        author(turn, items, latest, stronger)
    }

    private void author(Turn turn, List<Map> items, Map evidence, RoleSpec spec) {
        List<ContentBlockParam> blocks = []
        if (evidence != null) {
            // The catalog is the turn's cached prefix after tools and the system prompt: sorted and id-free, so it is
            // byte-identical across this tenant's turns; everything per-turn comes after its breakpoint.
            blocks << ContentBlockParam.ofText(TextBlockParam.builder().text("This tenant's brain catalog (conclusions, rules, statements):\n" +
                    JsonOutput.toJson(brainCatalog(turn.ec, turn.findingId))).cacheControl(CacheControlEphemeral.builder().build()).build())
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
                turn.replyParts << stoppedReply(turn.cfg, r.outcome)
                return
            }
            rounds++
            if (rounds > turn.limits.maxEvidenceRounds + 1) {
                // It was told no more rounds and asked again.
                turn.outcome = "INCOMPLETE"
                turn.replyParts << turn.cfg.text("reply.NEED_MORE_EVIDENCE")
                return
            }
            String content
            boolean isError = false
            if (rounds > turn.limits.maxEvidenceRounds) {
                content = "NO_MORE_EVIDENCE: ${turn.limits.maxEvidenceRounds} rounds used; write with what you have, or ask the person.".toString()
                isError = true
            } else {
                Map found = investigate(turn, items, ((Map) r.control.input).need as String, (List<Map>) (evidence?.facts ?: []))
                evidence = [facts: found.facts]
                turn.facts = (List<Map>) found.facts
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
            if (evidence.carried) b << "These facts are from this conversation's earlier investigation of the same finding; nothing was re-read " <<
                    "for this correction. Call request_more_evidence if it needs a record they do not cover.\n"
            List<Map> open = turn.transcript.conversationProposals()
            if (open) b << "Proposals already in this conversation: " << JsonOutput.toJson(open) << "\n"
        } else {
            b << "Only raise_ticket is available in this step: raise a ticket for each item with the person's words verbatim.\n"
        }
        return b.toString()
    }

    private Map brainCatalog(def ec, String findingId) {
        ec.message.pushErrors()
        try {
            Map out = readBrainCatalog(ec, findingId ? [findingId: findingId] : [:])
            if (ec.message.hasError()) return [conclusions: [], rules: [], statements: []]
            return [conclusions: out?.conclusions ?: [], rules: out?.rules ?: [], statements: out?.statements ?: []]
        } finally {
            ec.message.clearErrors()
            ec.message.popErrors()
        }
    }

    /** The catalog service call; with the open finding's id it reads only that finding's causes and audit (DAR-BE-216). */
    protected Map readBrainCatalog(def ec, Map params) {
        return (Map) ec.service.sync().name("facade.AgentReadFacadeServices.get#AgentBrainCatalog").parameters(params).call()
    }

    // ---------------------------------------------------------------- one role run

    /**
     * Starts the role's turn row, runs it within what is left of the turn's time, records its messages and what it made. Ends
     * the whole turn (Stop) on a tenant switch, Claude being unavailable, the daily cap, or too little time left.
     */
    private RoleResult role(Turn turn, RoleSpec base, List<MessageParam> messages) {
        long left = turn.deadline - clock.call()
        if (left < MIN_ROLE_MS) throw new Stop("INCOMPLETE", turn.cfg.text("reply.OUT_OF_TIME"))
        if (overDailyCap(turn.ec, turn.pinned, turn.cfg, turn.limits)) throw new Stop("OVER_BUDGET", turn.cfg.text("reply.OVER_BUDGET"))
        // The grant is read again before every role: one that reached its limit, was paused or revoked mid-turn stops the next role.
        Map access = AgentAccess.access(turn.ec, turn.pinned, turn.userId)
        if (access.state != AgentAccess.LIVE) throw new Stop(accessOutcome(access), accessReply(turn.cfg, access))
        turn.grantId = access.grantId as String
        // The switch is read again before every role: turned off mid-turn, the next role does not start.
        String state = availability.call(turn.ec, turn.cfg, turn.limits)
        if (state != AgentSwitch.ON) throw new Stop(state ?: AgentSwitch.AGENT_OFF, state == AgentSwitch.AGENT_UNAVAILABLE ?
                turn.cfg.text("reply.UNAVAILABLE_MID_TURN") : turn.cfg.text("reply.SWITCHED_OFF_MID_TURN"))
        RoleSpec spec = left < base.wallClockMs ? base.copy(wallClockMs: left) : base
        String turnId
        try {
            turnId = AgentUsageLedger.startTurn(turn.ec, turn.pinned, turn.conversationId, spec.role, spec.model, turn.userId, turn.grantId)
        } catch (TenantChangedException ignored) {
            throw new Stop("TENANT_CHANGED", null)
        }
        RoleResult r = runRole(turn.ec, spec, turn.pinned, messages, turnId)
        record(turn, spec, r, turnId)
        if (r.outcome == "TENANT_CHANGED") throw new Stop("TENANT_CHANGED", null)
        if (r.outcome == "UNAVAILABLE") throw new Stop(AgentSwitch.AGENT_UNAVAILABLE, turn.cfg.text("reply.UNAVAILABLE_MID_TURN"))
        if (r.outcome == "NO_ACCESS") {
            // The grant reached its limit (or was paused, revoked or expired) mid-role: the role stopped before its next request.
            Map now = AgentAccess.access(turn.ec, turn.pinned, turn.userId)
            if (now.state != AgentAccess.LIVE) throw new Stop(accessOutcome(now), accessReply(turn.cfg, now))
            throw new Stop("INCOMPLETE", stoppedReply(turn.cfg, "NO_ACCESS"))
        }
        return r
    }

    private void record(Turn turn, RoleSpec spec, RoleResult r, String turnId) {
        // What the tools made is already committed (each runs in its own transaction), so it is taken into the turn before
        // any transcript write: a failed write then ends the turn ERROR with these ids, never "nothing was changed".
        r.toolCalls.each { Map call ->
            if (call.isError) return
            if (AgentToolExecutor.isProposalTool(call.name as String)) {
                String id = outValue(call.content as String, "proposalId")
                Map view = id ? proposalView(turn.ec, turn.pinned, id) : null
                if (view) turn.proposals[id] = view
            } else if (call.name == "raise_ticket") {
                String id = outValue(call.content as String, "ticketId")
                if (id) turn.tickets[id] = [ticketId: id, category: ((Map) call.input).category, duplicate: (call.content as String).contains('"duplicate":true')]
            }
        }
        turn.transcript.write("ASSISTANT", turnId, [role: spec.role, model: spec.model, outcome: r.outcome, text: r.text(),
                control: r.control == null ? null : [tool: r.control.tool, input: r.control.input]])
        r.toolCalls.each { Map call ->
            turn.transcript.write("TOOL_RESULT", turnId, [tool: call.name, toolUseId: call.toolUseId, input: call.input,
                    isError: call.isError, content: call.content])
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
        if (blocked && turn.question == null) turn.question = [text: provenanceQuestion(turn.cfg, blocked), options: [], from: "PROVENANCE"]
        String outcome = turn.outcome ?: (turn.question != null ? "QUESTION" : "DONE")
        if (outcome == "DONE" && turn.question != null) outcome = "QUESTION"
        String reply = turn.replyParts.findAll { it }.join("\n\n") ?: null
        // Never an empty answer: a turn that made nothing, asked nothing and says nothing tells the person so.
        if (!reply && turn.question == null && !proposals && !turn.tickets && turn.queryProposal == null) {
            reply = turn.cfg.text(outcome == "TENANT_CHANGED" ? "reply.TENANT_CHANGED" : "reply.NOTHING_CAME_OUT")
            if (outcome == "DONE") outcome = "INCOMPLETE"
        }
        Map out = response(turn.conversationId, outcome, reply)
        out.proposals = proposals
        if (turn.query) out.queryProposal = turn.queryProposal
        out.tickets = new ArrayList<Map>(turn.tickets.values())
        out.question = turn.question
        out.masked = turn.masked
        return out
    }

    static final int MAX_ECHOED_IDS = 5
    static final int MAX_ECHOED_ID_CHARS = 64

    /** The question a blocked proposal turns into: at most 5 of its ids, each cut at 64 characters. */
    static String provenanceQuestion(AgentConfig cfg, List<String> blocked) {
        List<String> shown = blocked.take(MAX_ECHOED_IDS).collect { String id -> id.length() <= MAX_ECHOED_ID_CHARS ? id : id.substring(0, MAX_ECHOED_ID_CHARS) + "..." }
        String more = blocked.size() > MAX_ECHOED_IDS ? " (and ${blocked.size() - MAX_ECHOED_IDS} more)".toString() : ""
        return cfg.text("reply.TYPED_VALUES", [values: shown.join(', '), more: more])
    }

    private static Map response(String conversationId, String outcome, String reply) {
        return [conversationId: conversationId, outcome: outcome, reply: reply, proposals: [], tickets: [], question: null, masked: []]
    }

    /** Why a role stopped, in words (reason.* text); the code itself stays in the turn row for staff. */
    static String stoppedReply(AgentConfig cfg, String outcome) {
        String id = "reason." + outcome
        String why = cfg.text(AgentTextDefaults.TEXTS.containsKey(id) ? id : "reason.ERROR")
        return cfg.text("reply.STOPPED", [why: why])
    }

    /** What the person reads when the agent cannot run before the turn starts. */
    static String unavailableReply(AgentConfig cfg, String state) {
        if (state == AgentAvailability.AGENT_REFUSED) return cfg.text("reply.AGENT_REFUSED")
        return cfg.text(state == AgentSwitch.AGENT_UNAVAILABLE ? "reply.AGENT_UNAVAILABLE" : "reply.AGENT_OFF")
    }

    // ---------------------------------------------------------------- limits

    /** The outcome of a turn refused for its grant (AgentAccess state NOT_GRANTED, EXPIRED or PAUSED). */
    static String accessOutcome(Map access) {
        switch (access.state) {
            case AgentAccess.EXPIRED: return "AI_EXPIRED"
            case AgentAccess.PAUSED: return "AI_PAUSED"
            default: return "AI_NOT_GRANTED"
        }
    }

    /** What the person reads when their grant does not let the turn run; an end date is shown as yyyy-MM-dd (UTC). */
    static String accessReply(AgentConfig cfg, Map access) {
        switch (access.state) {
            case AgentAccess.EXPIRED:
                String day = (access.thruDate as Timestamp).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                return cfg.text("reply.ACCESS_EXPIRED", [day: day])
            case AgentAccess.PAUSED:
                return cfg.text(access.pauseReason == AgentAccess.REASON_ADMIN ? "reply.ACCESS_PAUSED_ADMIN" : "reply.ACCESS_PAUSED_LIMIT")
            default: return cfg.text("reply.ACCESS_NOT_GRANTED")
        }
    }

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

    /**
     * The one read of the daily cap: the tenant's override in cfg, else the global setting in lim (DAR-BE-304's key). A turn
     * passes its own cfg and lim, so every role is compared against one cap.
     */
    static BigDecimal dailyCapUsd(String tenantId, AgentConfig cfg, AgentLimits lim) {
        return cfg.tenantCap(tenantId) ?: lim.dailyUsdCapPerTenant
    }

    static boolean overDailyCap(def ec, String pinned, AgentConfig cfg, AgentLimits lim) {
        return AgentUsageLedger.todayCostMicros(ec, pinned) >= (dailyCapUsd(pinned, cfg, lim) * 1_000_000G).longValue()
    }

    // ---------------------------------------------------------------- rows

    /** The finding is the tenant's and sits under one of this audit's statements. */
    private static boolean findingOfAudit(def ec, String pinned, String auditId, String findingId) {
        def finding = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, pinned, WHY)
        if (finding == null) return false
        def statement = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId", finding.statementId, pinned, WHY)
        return statement != null && statement.auditId == auditId
    }

    /** In the caller's transaction (with the first message). */
    private static void createConversation(def ec, String id, String pinned, String userId, String auditId, String findingId) {
        ec.entity.makeValue("darpan.agent.AgentConversation").setAll([conversationId: id, companyUserGroupId: pinned, userId: userId,
                auditId: auditId, findingId: findingId, createdDate: ec.user.nowTimestamp]).create()
    }

    /** The conversation now opens on another finding, so provenance reads that finding as the open context. In the caller's transaction. */
    private static void setOpenFinding(def ec, String pinned, String conversationId, String findingId) {
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AgentConversation", "conversationId", conversationId, pinned, WHY)
        if (row == null) throw new IllegalStateException("Conversation ${conversationId} is not the pinned tenant's".toString())
        row.findingId = findingId
        row.update()
    }

    /** The conversation's messages: every write carries the pinned tenant and the next sequence number, in its own transaction. */
    static class Transcript {
        /** Newest first; a duplicate sequence number (two sends at once) falls back to write time, then id. */
        static final List<String> NEWEST_FIRST = ["-sequenceNum", "-lastUpdatedStamp", "-messageId"].asImmutable()
        /** DAR-BE-224: the most OPERATOR rows lastFacts reads, so a long run of approval notes stays a bounded read. */
        static final int OPERATOR_ROWS_READ = 50
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
            return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentMessage", AgentOrchestrator.WHY)
                    .condition("companyUserGroupId", pinned).condition("conversationId", conversationId).useCache(false)
        }

        /** In its own transaction, except the USER message, which joins the transaction that may create the conversation. */
        void write(String kind, String turnId, Object content) {
            String json = JsonOutput.toJson(content)
            long seq = nextSeq++
            Closure insert = {
                ec.entity.makeValue("darpan.agent.AgentMessage").setAll([messageId: TenantScopedFinder.mintId(ec, "darpan.agent.AgentMessage", "messageId"),
                        conversationId: conversationId, companyUserGroupId: pinned, turnId: turnId, sequenceNum: seq, kind: kind,
                        contentJson: json]).create()
            }
            if (kind == "USER") insert.call()
            else ec.transaction.runRequireNew(null, "Could not write the agent transcript", insert)
        }

        /** What the person sees, oldest first: their messages and the replies. Tool results and operator notes stay out. */
        List<Map> messages() {
            List<Map> out = []
            rows().condition("kind", EntityCondition.IN, ["USER", "ASSISTANT"]).orderBy(["sequenceNum", "lastUpdatedStamp", "messageId"]).list().each { msg ->
                Object parsed = AgentOrchestrator.parseJson(msg.contentJson as String)
                if (msg.kind == "USER") {
                    List<String> texts = []
                    (parsed instanceof List ? (List) parsed : [parsed]).each { Object blk ->
                        if (blk instanceof Map && ((Map) blk).type == "text" && ((Map) blk).text instanceof String) texts << (String) ((Map) blk).text
                    }
                    out << [messageId: msg.messageId, kind: "USER", text: texts.join(" "), question: null]
                } else if (parsed instanceof Map && ((Map) parsed).role == "REPLY") {
                    out << [messageId: msg.messageId, kind: "ASSISTANT", text: ((Map) parsed).text, question: ((Map) parsed).question]
                }
            }
            return out
        }

        /** The text of the person's last n messages before this one, oldest first. */
        List<String> earlierUserTexts(int n) {
            List<String> out = []
            rows().condition("kind", "USER").orderBy(NEWEST_FIRST).limit(n).list().each { msg ->
                Object parsed = AgentOrchestrator.parseJson(msg.contentJson as String)
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
            Object parsed = AgentOrchestrator.parseJson(last[0].contentJson as String)
            if (!(parsed instanceof Map) || ((Map) parsed).role != "REPLY" || !(((Map) parsed).question instanceof Map)) return null
            Map q = (Map) ((Map) parsed).question
            return q.text ? [text: q.text as String, from: q.from as String] : null
        }

        /**
         * The typed facts the conversation's latest investigated turn ended with, when that turn was about this finding;
         * null otherwise (facts about another finding are not this one's). Typed again on the way out (typedEvidence), so
         * a stored row never reaches the author unfiltered.
         */
        List<Map> lastFacts(String findingId, AgentLimits lim) {
            // DAR-BE-224: approvals, rejects and reverts write plain-text OPERATOR notes too; only a turn's own record (a Map)
            // counts toward the earlier turns looked at, so a run of notes cannot push the facts out of reach.
            int turnsSeen = 0
            for (def msg : rows().condition("kind", "OPERATOR").orderBy(NEWEST_FIRST).limit(OPERATOR_ROWS_READ).list()) {
                Object parsed = AgentOrchestrator.parseJson(msg.contentJson as String)
                if (!(parsed instanceof Map)) continue
                if (++turnsSeen > lim.earlierTurns) return null
                if (!(((Map) parsed).facts instanceof List)) continue
                if (((Map) parsed).findingId != findingId) return null
                List<Map> facts = (List<Map>) AgentOrchestrator.typedEvidence([facts: ((Map) parsed).facts], lim)?.facts
                return facts ?: null
            }
            return null
        }

        /** The ids of the conversation's proposals and tickets, [proposals, tickets]. */
        Map<String, List<String>> madeIds() {
            List<String> proposals = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AuditChangeProposal", AgentOrchestrator.WHY)
                    .condition("companyUserGroupId", pinned).condition("conversationId", conversationId).orderBy("proposalId")
                    .useCache(false).list()*.proposalId.collect { it as String }
            List<String> tickets = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.BrainChangeTicket", AgentOrchestrator.WHY)
                    .condition("companyUserGroupId", pinned).condition("conversationId", conversationId).orderBy("ticketId")
                    .useCache(false).list()*.ticketId.collect { it as String }
            return [proposals: proposals, tickets: tickets]
        }

        /** The conversation's proposals so far: what the author drafted, for a CORRECTION. */
        List<Map> conversationProposals() {
            return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AuditChangeProposal", AgentOrchestrator.WHY)
                    .condition("companyUserGroupId", pinned).condition("conversationId", conversationId).orderBy("proposalId")
                    .useCache(false).list().collect { row ->
                // A blocked draft's values came from records, not the person: the author is told it is blocked, not what it held.
                Map p = [proposalId: row.proposalId, kind: row.kind, status: row.status, provenanceStatus: row.provenanceStatus,
                         target: AgentOrchestrator.parseJson(row.targetJson as String)]
                if (row.provenanceStatus != "BLOCKED") p.after = AgentOrchestrator.parseJson(row.afterJson as String)
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
        /** The turn's one config and limits snapshot (DAR-BE-307); every role and check of the turn reads these. */
        AgentConfig cfg
        AgentLimits limits
        String pinned
        /** The person and the AI access grant this turn spends against (DAR-BE-194). */
        String userId
        String grantId
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
        /** DAR-BE-334: a turn from the queries page, and the query it proposed ({proposal, preview}) or null. */
        boolean query
        Map queryProposal
        /** The query on the page when the person asked ({readSpecJson, scriptBody}, masked), or null. */
        Map draft
        /** The conversation's proposal and ticket ids before this turn's roles ran. */
        Map<String, List<String>> priorIds = [proposals: [], tickets: []]
        /** The typed facts the turn ended with, or null when it investigated nothing. */
        List<Map> facts
        List<String> replyParts = []
        Map question
        String outcome
    }
}
