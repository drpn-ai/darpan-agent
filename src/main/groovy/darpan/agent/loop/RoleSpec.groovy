package darpan.agent.loop

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
        tools = (((args.tools ?: []) as List).collect { it as String }).asImmutable()
        maxRequests = args.maxRequests as int
        maxToolCalls = args.maxToolCalls as int
        wallClockMs = args.wallClockMs as long
        maxTokens = args.maxTokens as long
        outputSchema = args.outputSchema == null ? null : ((Map) args.outputSchema).asImmutable()
        if (!(role in [ROUTER, INVESTIGATOR, AUTHOR])) throw new IllegalArgumentException("Unknown role: ${role}".toString())
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
     */
    static RoleSpec router(Map outputSchema = null) {
        return new RoleSpec(role: ROUTER, model: "claude-sonnet-5-5", effort: "LOW", systemPromptPath: "darpan/agent/prompts/router.md",
                tools: [], maxRequests: 1, maxToolCalls: 0, wallClockMs: 20_000L, maxTokens: 2_000L, outputSchema: outputSchema)
    }

    static RoleSpec investigator() {
        return new RoleSpec(role: INVESTIGATOR, model: "claude-sonnet-5-5", effort: "MEDIUM", systemPromptPath: "darpan/agent/prompts/investigator.md",
                // R43: inspect_record is left out until AgentReadCatalog rows ship together with a way for the model to
                // learn a readId (follow-up item); until then every call failed "Unknown read" and only spent rounds.
                // The tool definition (tools.json) and the core service inspect#AgentRecord stay.
                tools: ["get_finding", "find_lookalikes", "search_knowledge", "get_audit_settings"],
                maxRequests: 12, maxToolCalls: 30, wallClockMs: 90_000L, maxTokens: 16_000L)
    }

    static RoleSpec author() {
        return new RoleSpec(role: AUTHOR, model: "claude-opus-5-5", effort: "HIGH", systemPromptPath: "darpan/agent/prompts/author.md",
                tools: ["propose_exclusion", "propose_statement_narrowing", "propose_fix_text", "propose_conclusion_rows", "raise_ticket",
                        "ask_human", "request_more_evidence"],
                maxRequests: 8, maxToolCalls: 6, wallClockMs: 120_000L, maxTokens: 32_000L)
    }

    /** This spec with some fields replaced, checked again. */
    RoleSpec copy(Map changes) {
        Map args = [role: role, model: model, effort: effort, systemPromptPath: systemPromptPath, tools: tools, maxRequests: maxRequests,
                    maxToolCalls: maxToolCalls, wallClockMs: wallClockMs, maxTokens: maxTokens, outputSchema: outputSchema]
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
