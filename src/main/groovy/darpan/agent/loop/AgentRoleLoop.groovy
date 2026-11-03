package darpan.agent.loop

import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.core.ObjectMappers
import darpan.agent.claude.ModelGateway
import darpan.agent.claude.ModelUnavailableException
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-159. The Messages API tool loop one role runs: send, append the response whole, run its tool calls as the user,
 * return every result of the round in one user message, and stop on the first of: end_turn (DONE), a control tool
 * (QUESTION, MORE_EVIDENCE), a budget (BUDGET), the same call a third time (LOOP), refusal (REFUSED), max_tokens
 * (MAX_TOKENS), a tenant switch (TENANT_CHANGED) or Anthropic unavailable after the SDK's retries (UNAVAILABLE).
 * Every request is priced onto the turn's AgentTurn row.
 */
class AgentRoleLoop {
    private static final Logger logger = LoggerFactory.getLogger(AgentRoleLoop.class)

    /** System property (conf default-property) darpan.agent.fallbacks; "false" stops sending server-side fallbacks. */
    static final String FALLBACKS_PROPERTY = "darpan.agent.fallbacks"
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01"
    static final int SAME_CALL_LIMIT = 3

    private static final Map<String, String> PROMPTS = new ConcurrentHashMap<>()

    static RoleResult run(def ec, RoleSpec spec, String pinnedTenantId, List<MessageParam> messages, ModelGateway gateway, String turnId) {
        return new AgentRoleLoop().runRole(ec, spec, pinnedTenantId, messages, gateway, turnId)
    }

    /** The executor for one run; tests override it to watch the calls. */
    protected AgentToolExecutor executor(RoleSpec spec, String conversationId) {
        return new AgentToolExecutor(spec, conversationId)
    }

    RoleResult runRole(def ec, RoleSpec spec, String pinnedTenantId, List<MessageParam> messages, ModelGateway gateway, String turnId) {
        RoleResult result = new RoleResult(messages: new ArrayList<MessageParam>(messages))
        Map turn = AgentUsageLedger.turnOf(ec, turnId)
        if (!tenantStill(ec, pinnedTenantId) || turn == null || turn.companyUserGroupId != pinnedTenantId)
            return finish(ec, turnId, result, "TENANT_CHANGED")
        AgentToolExecutor tools = executor(spec, turn.conversationId as String)
        long deadline = System.currentTimeMillis() + spec.wallClockMs
        Map<String, Integer> seen = [:]
        int toolCallsRun = 0

        while (true) {
            if (result.requestCount >= spec.maxRequests || System.currentTimeMillis() > deadline) return finish(ec, turnId, result, "BUDGET")
            if (!tenantStill(ec, pinnedTenantId)) return finish(ec, turnId, result, "TENANT_CHANGED")

            Message response
            try {
                response = gateway.send(buildParams(spec, result.messages))
            } catch (RateLimitException | UnauthorizedException | PermissionDeniedException | SseException e) {
                return unavailable(ec, turnId, result, e)
            } catch (AnthropicServiceException e) {
                if (e.statusCode() >= 500) return unavailable(ec, turnId, result, e)
                throw e // a 4xx is a request this code built wrong: never hidden as UNAVAILABLE
            } catch (AnthropicIoException | AnthropicRetryableException | ModelUnavailableException e) {
                return unavailable(ec, turnId, result, e)
            }
            result.requestCount++
            result.finalMessage = response
            result.stopReason = response.stopReason().map { it.asString() }.orElse(null)
            AgentUsageLedger.record(ec, turnId, response.usage(), response.model().asString() ?: spec.model)

            StopReason stop = response.stopReason().orElse(null)
            if (stop == StopReason.TOOL_USE) {
                result.messages.add(response.toParam())
                List<ContentBlockParam> answers = []
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue
                    ToolUseBlock use = block.asToolUse()
                    Map input = inputOf(use)
                    String key = use.name() + "\u0000" + canonical(input)
                    int times = (seen[key] ?: 0) + 1
                    if (times >= SAME_CALL_LIMIT) return finish(ec, turnId, result, "LOOP")
                    if (toolCallsRun >= spec.maxToolCalls) return finish(ec, turnId, result, "BUDGET")
                    if (System.currentTimeMillis() > deadline) return finish(ec, turnId, result, "BUDGET")
                    Map done
                    try {
                        done = input == null ? [isError: true, content: AgentToolExecutor.INVALID_INPUT + ": input must be an object"]
                                : tools.execute(ec, pinnedTenantId, use.name(), input)
                    } catch (TenantChangedException ignored) {
                        return finish(ec, turnId, result, "TENANT_CHANGED")
                    }
                    seen[key] = times
                    toolCallsRun++
                    if (done.control) {
                        result.control = [tool: use.name(), toolUseId: use.id(), input: input]
                        result.pendingToolResults = answers
                        return finish(ec, turnId, result, done.control as String)
                    }
                    result.toolCalls << [name: use.name(), input: input, isError: done.isError as boolean, content: done.content, toolUseId: use.id()]
                    answers << ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id())
                            .content(done.content as String).isError(done.isError as boolean).build())
                }
                result.messages.add(MessageParam.ofUserOfContentBlockParams(answers))
                continue
            }
            if (stop == StopReason.PAUSE_TURN) {
                // Resent as is: the paused assistant content goes back with no new user message.
                result.messages.add(response.toParam())
                continue
            }
            if (stop == StopReason.MAX_TOKENS || stop == StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED) {
                // Partial tool calls are dropped: nothing in a cut-off response runs.
                return finish(ec, turnId, result, "MAX_TOKENS")
            }
            if (stop == StopReason.REFUSAL) return finish(ec, turnId, result, "REFUSED")
            result.messages.add(response.toParam())
            return finish(ec, turnId, result, "DONE")
        }
    }

    /**
     * One request. The prefix (tools sorted by name, cache breakpoint on the last; the system prompt as one cached text
     * block; model, thinking and effort) depends only on the spec, so it is byte-identical across turns and caches.
     */
    static MessageCreateParams buildParams(RoleSpec spec, List<MessageParam> messages) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(spec.model)
                .maxTokens(spec.maxTokens)
                .systemOfTextBlockParams([TextBlockParam.builder().text(systemPrompt(spec.systemPromptPath))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()])
                .messages(messages)
        if (spec.tools) b.tools(AgentToolExecutor.toolDefinitions(spec.tools).collect { ToolUnion.ofTool(it) })
        if (spec.adaptiveThinking()) b.thinking(ThinkingConfigAdaptive.builder().build())
        Map output = [effort: spec.effort.toLowerCase(Locale.ROOT)]
        if (spec.outputSchema != null) output.format = [type: "json_schema", schema: spec.outputSchema]
        b.outputConfig(ObjectMappers.jsonMapper().convertValue(output, OutputConfig))
        if (spec.serverFallbacks() && fallbacksOn()) {
            // MessageCreateParams 3.2.0 (non-beta) has no typed fallbacks field; the beta builder's fallbacksDefault()
            // writes the same body. Sent as the documented wire shape: the beta header plus fallbacks: "default".
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b.build()
    }

    static boolean fallbacksOn() { return System.getProperty(FALLBACKS_PROPERTY)?.trim()?.toLowerCase(Locale.ROOT) != "false" }

    static String systemPrompt(String path) {
        return PROMPTS.computeIfAbsent(path) { String p ->
            InputStream stream = AgentRoleLoop.class.classLoader.getResourceAsStream(p)
            if (stream == null) throw new IllegalStateException("Agent prompt ${p} is missing".toString())
            try { return stream.getText("UTF-8") } finally { stream.close() }
        }
    }

    private static boolean tenantStill(def ec, String pinnedTenantId) {
        return pinnedTenantId && TenantAccessSupport.currentActiveTenantUserGroupId(ec) == pinnedTenantId
    }

    /** The tool input as a Map, or null when it is not a JSON object. */
    private static Map inputOf(ToolUseBlock use) {
        try {
            Object v = use._input().convert(Object)
            return v instanceof Map ? (Map) v : null
        } catch (RuntimeException ignored) {
            return null
        }
    }

    /** Key-sorted JSON, so the same input in another key order is the same call. */
    private static String canonical(Object v) {
        return JsonOutput.toJson(sorted(v))
    }

    private static Object sorted(Object v) {
        if (v instanceof Map) return new TreeMap(((Map) v).collectEntries { k, x -> [(k as String): sorted(x)] })
        if (v instanceof List) return ((List) v).collect { sorted(it) }
        return v
    }

    private static RoleResult unavailable(def ec, String turnId, RoleResult result, Exception e) {
        // SDK error messages carry the status and the API's error text, never the key.
        logger.warn("Claude unavailable for turn ${turnId}: ${e.class.simpleName}: ${e.message}".toString())
        return finish(ec, turnId, result, "UNAVAILABLE")
    }

    private static RoleResult finish(def ec, String turnId, RoleResult result, String outcome) {
        result.outcome = outcome
        AgentUsageLedger.finish(ec, turnId, result.stopReason, outcome)
        return result
    }
}
