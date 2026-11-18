package darpan.agent.loop

import darpan.agent.config.AgentConfig
import darpan.agent.config.AgentConfigDefaults
import darpan.agent.config.AgentLimits
import groovy.transform.CompileStatic

/**
 * DAR-BE-159. One agent role's model, prompt, tools and budgets. Immutable; {@link #copy} makes a variant (a narrower tool
 * list, a test prompt). The tool list is checked against the tool catalog, so a role can never name a tool that approves,
 * rejects, reverts or plans a change.
 */
@CompileStatic
final class RoleSpec {
    static final String ROUTER = "ROUTER"
    static final String INVESTIGATOR = "INVESTIGATOR"
    static final String AUTHOR = "AUTHOR"

    final String role
    final String model
    /** LOW, MEDIUM or HIGH (OutputConfig.effort). */
    final String effort
    final String systemPromptPath
    /** The system prompt's text: the edit when there is one, else the shipped file (DAR-BE-307). */
    final String systemPrompt
    /** The turn's one config and limits snapshot, carried so a role never reads a second one. */
    final AgentConfig config
    final AgentLimits limits
    final List<String> tools
    final int maxRequests
    final int maxToolCalls
    final long wallClockMs
    final long maxTokens
    /** The router's structured output (a JSON schema); null for the other roles. */
    final Map outputSchema

    RoleSpec(Map args) {
        role = args.role as String
        model = args.model as String
        effort = args.effort as String
        systemPromptPath = args.systemPromptPath as String
        config = (args.config ?: AgentConfig.current(null)) as AgentConfig
        limits = (args.limits ?: AgentLimits.read()) as AgentLimits
        tools = (((args.tools ?: []) as List).collect { it as String }).asImmutable()
        maxRequests = args.maxRequests as int
        maxToolCalls = args.maxToolCalls as int
        wallClockMs = args.wallClockMs as long
        maxTokens = args.maxTokens as long
        outputSchema = args.outputSchema == null ? null : ((Map) args.outputSchema).asImmutable()
        if (!(role in [ROUTER, INVESTIGATOR, AUTHOR])) throw new IllegalArgumentException("Unknown role: ${role}".toString())
        systemPrompt = (args.systemPrompt ?: (systemPromptPath ? config.promptForPath(systemPromptPath) : null)) as String
        if (!model || !effort || !systemPromptPath) throw new IllegalArgumentException("model, effort and systemPromptPath are required")
        tools.each { String t ->
            if (!AgentToolExecutor.TOOLS.containsKey(t)) throw new IllegalArgumentException("Tool ${t} is not in the agent's tool catalog".toString())
        }
        // The API refuses a request past this (a 400 on every request of the role), so the spec is refused instead.
        AgentToolExecutor.requireWithinStrictLimits(role, tools, outputSchema)
    }

    /**
     * Sonnet, not Haiku: the router's kind decides the path (DISPLAY_FEEDBACK goes straight to a ticket), and in three
     * evals Haiku kept fix-text changes as display and split closing words off however the definitions were worded.
     * One schema-bound request with no thinking, so it adds about $0.002 a message.
     * DAR-BE-307: the model and prompt come from cfg, the budgets from lim, both read once per turn.
     */
    static RoleSpec router(Map outputSchema = null, AgentConfig cfg = AgentConfig.current(null), AgentLimits lim = AgentLimits.read()) {
        return new RoleSpec(role: ROUTER, model: cfg.model(AgentConfigDefaults.ROUTER), effort: "LOW", systemPromptPath: "darpan/agent/prompts/router.md",
                tools: [], maxRequests: lim.routerMaxRequests, maxToolCalls: 0, wallClockMs: lim.routerWallClockMs, maxTokens: lim.routerMaxTokens,
                outputSchema: outputSchema, config: cfg, limits: lim)
    }

    static RoleSpec investigator(AgentConfig cfg = AgentConfig.current(null), AgentLimits lim = AgentLimits.read()) {
        return new RoleSpec(role: INVESTIGATOR, model: cfg.model(AgentConfigDefaults.INVESTIGATOR), effort: "MEDIUM", systemPromptPath: "darpan/agent/prompts/investigator.md",
                // R43: inspect_record is left out until AgentReadCatalog rows ship together with a way for the model to
                // learn a readId (follow-up item); until then every call failed "Unknown read" and only spent rounds.
                // The tool definition (tools.json) and the core service inspect#AgentRecord stay.
                tools: ["get_finding", "find_lookalikes", "search_knowledge", "get_audit_settings"],
                maxRequests: lim.investigatorMaxRequests, maxToolCalls: lim.investigatorMaxToolCalls, wallClockMs: lim.investigatorWallClockMs,
                maxTokens: lim.investigatorMaxTokens, config: cfg, limits: lim)
    }

    static RoleSpec author(AgentConfig cfg = AgentConfig.current(null), AgentLimits lim = AgentLimits.read()) {
        return new RoleSpec(role: AUTHOR, model: cfg.model(AgentConfigDefaults.AUTHOR), effort: "HIGH", systemPromptPath: "darpan/agent/prompts/author.md",
                tools: ["propose_exclusion", "propose_statement_narrowing", "propose_expected_causes", "propose_fix_text", "propose_conclusion_rows", "propose_audit_setup",
                        "propose_statement_edit", "raise_ticket",
                        "ask_human", "request_more_evidence"],
                maxRequests: lim.authorMaxRequests, maxToolCalls: lim.authorMaxToolCalls, wallClockMs: lim.authorWallClockMs,
                maxTokens: lim.authorMaxTokens, config: cfg, limits: lim)
    }

    /**
     * The author spec a doubtful turn is rerun on, or null when escalation is off ("none") or would be the same model as the author.
     * Eval 2026-11-10: Sonnet matched Opus on 12 of 13 cases at 29% lower cost; on a new shared cause it asked where Opus
     * drafted, so a turn whose author would ask the person runs the escalation model first (AgentOrchestrator).
     */
    static RoleSpec escalationAuthor(AgentConfig cfg = AgentConfig.current(null), AgentLimits lim = AgentLimits.read()) {
        String model = cfg.model(AgentConfigDefaults.AUTHOR_ESCALATION)
        if (!model || model == "none" || model == cfg.model(AgentConfigDefaults.AUTHOR)) return null
        return author(cfg, lim).copy(model: model)
    }

    /** This spec with some fields replaced, checked again. A new systemPromptPath alone drops the text read for the old one. */
    RoleSpec copy(Map changes) {
        Map args = [role: role, model: model, effort: effort, systemPromptPath: systemPromptPath, tools: tools, maxRequests: maxRequests,
                    maxToolCalls: maxToolCalls, wallClockMs: wallClockMs, maxTokens: maxTokens, outputSchema: outputSchema,
                    config: config, limits: limits, systemPrompt: systemPrompt]
        if (changes.containsKey("systemPromptPath") && !changes.containsKey("systemPrompt")) args.remove("systemPrompt")
        args.putAll(changes)
        return new RoleSpec(args)
    }

    /** The investigator and author think adaptively; the router (one schema-bound sort) and Haiku send no thinking config. */
    boolean adaptiveThinking() { return role != ROUTER && !model.startsWith("claude-haiku") }

    /** Server-side refusal fallbacks exist for Opus and Sonnet only; the router's one cheap request never takes one. */
    boolean serverFallbacks() { return role != ROUTER && (model.startsWith("claude-opus") || model.startsWith("claude-sonnet")) }

    @Override
    String toString() { return "RoleSpec(${role}, ${model})" }
}
