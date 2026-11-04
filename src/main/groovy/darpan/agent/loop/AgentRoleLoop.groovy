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
import darpan.agent.claude.StreamInterruptedException
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-159. The Messages API tool loop one role runs: send, append the response whole, run its tool calls as the user,
 * return every result of the round in one user message, and stop on the first of: end_turn (DONE), a control tool
 * (QUESTION, MORE_EVIDENCE), a budget (BUDGET), the same call a third time (LOOP), refusal (REFUSED), max_tokens
 * (MAX_TOKENS), a tenant switch (TENANT_CHANGED), the person's AI access grant no longer live (NO_ACCESS, DAR-BE-194: read
 * before every request, so a grant that reaches its limit stops the role before its next request), Anthropic unavailable after the SDK's retries (UNAVAILABLE), or a
 * stop reason this code does not know, or a 4xx it caused (ERROR; the 4xx is rethrown after the turn is closed).
 * Every request is priced onto the turn's AgentTurn row, under the pinned tenant.
 *
 * wallClockMs is checked between steps: before each request and before each tool. The orchestrator gives a role at most
 * what is left of the turn's 240 s, so this is also the turn cap. Each attempt's timeout is all that is left (it bounds
 * the whole call, streamed body included), so a long streamed answer keeps the role's time. The SDK does not retry; this
 * loop retries a request at most {@link #MAX_RETRIES} times, only for a failure before any streamed byte (a connection
 * error, 429, 5xx, 529), after its Retry-After or a backoff, and only when at least {@link #MIN_REQUEST_TIMEOUT_MS} is
 * still left after the wait. A stream that fails after it started is never retried; the usage it reported is recorded.
 */
class AgentRoleLoop {
    private static final Logger logger = LoggerFactory.getLogger(AgentRoleLoop.class)

    /** System property (conf default-property) darpan.agent.fallbacks; "false" stops sending server-side fallbacks. */
    static final String FALLBACKS_PROPERTY = "darpan.agent.fallbacks"
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01"
    static final int SAME_CALL_LIMIT = 3
    /** Less than this left and no request (or retry) starts. */
    static final long MIN_REQUEST_TIMEOUT_MS = 5_000L
    /**
     * Sent after the tool results when the investigator's next request is its last: a tool it calls then never runs, so
     * without this a wide read ends BUDGET with nothing, though what it already read usually answers the person.
     */
    static final String LAST_REQUEST_NOTICE = "This is your last request: no tool you call now will run. Return the evidence JSON now from what you have read, and say in it what you could not check."
    /** Retries of one request, each only before any streamed byte and inside the deadline. */
    static final int MAX_RETRIES = 2
    static final long BACKOFF_BASE_MS = 500L
    static final long BACKOFF_MAX_MS = 8_000L

    private static final Map<String, String> PROMPTS = new ConcurrentHashMap<>()

    static RoleResult run(def ec, RoleSpec spec, String pinnedTenantId, List<MessageParam> messages, ModelGateway gateway, String turnId) {
        return new AgentRoleLoop().runRole(ec, spec, pinnedTenantId, messages, gateway, turnId)
    }

    /** Milliseconds now; tests move it. */
    protected long now() { return System.currentTimeMillis() }

    /** Waits before a retry; tests record it instead. */
    protected void pause(long ms) { if (ms > 0) Thread.sleep(ms) }

    /**
     * One request with this loop's own retries: each attempt is given all that is left before the deadline; a failure before
     * any streamed byte that may pass (connection error, 429, 5xx, 529) is retried after its Retry-After (or 0.5 s x 2^n,
     * at most 8 s), at most MAX_RETRIES times, and only while MIN_REQUEST_TIMEOUT_MS is still left after the wait.
     * Anything else is thrown as it came, including StreamInterruptedException.
     */
    protected Message sendWithRetries(ModelGateway gateway, MessageCreateParams params, long deadline) {
        int retries = 0
        while (true) {
            try {
                return gateway.send(params, Duration.ofMillis(Math.max(1L, deadline - now())))
            } catch (RuntimeException e) {
                if (!retryable(e) || retries >= MAX_RETRIES) throw e
                Long after = retryAfterMs(e)
                long wait = after != null ? after : Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L << retries))
                if (deadline - now() - wait < MIN_REQUEST_TIMEOUT_MS) throw e
                logger.info("Claude request failed before streaming (${e.class.simpleName}); retry ${retries + 1} in ${wait} ms".toString())
                pause(wait)
                retries++
            }
        }
    }

    /** A failure before any streamed byte that a later attempt may not hit: a connection error, 429, 5xx or 529. */
    static boolean retryable(Throwable e) {
        if (e instanceof StreamInterruptedException || e instanceof SseException) return false
        if (e instanceof AnthropicIoException) return true
        if (e instanceof AnthropicServiceException) {
            int status = ((AnthropicServiceException) e).statusCode()
            return status == 429 || status >= 500
        }
        return false
    }

    /** The API's Retry-After-Ms, or Retry-After in seconds, in milliseconds; null when it sent neither as a number. */
    static Long retryAfterMs(Throwable e) {
        if (!(e instanceof AnthropicServiceException)) return null
        def headers = ((AnthropicServiceException) e).headers()
        if (headers == null) return null
        String ms = headers.values("retry-after-ms")?.find()
        if (ms?.trim()?.isNumber()) return Math.max(0L, new BigDecimal(ms.trim()).longValue())
        String seconds = headers.values("retry-after")?.find()
        if (seconds?.trim()?.isNumber()) return Math.max(0L, (new BigDecimal(seconds.trim()) * 1000G).longValue())
        return null
    }

    /** The executor for one run; tests override it to watch the calls. */
    protected AgentToolExecutor executor(RoleSpec spec, String conversationId) {
        return new AgentToolExecutor(spec, conversationId)
    }

    RoleResult runRole(def ec, RoleSpec spec, String pinnedTenantId, List<MessageParam> messages, ModelGateway gateway, String turnId) {
        RoleResult result = new RoleResult(messages: new ArrayList<MessageParam>(messages))
        Map turn = AgentUsageLedger.turnOf(ec, turnId, pinnedTenantId)
        if (turn == null) throw new IllegalArgumentException("Turn ${turnId} is not the pinned tenant's".toString())
        if (!tenantStill(ec, pinnedTenantId)) return finish(ec, pinnedTenantId, turnId, result, "TENANT_CHANGED")
        AgentToolExecutor tools = executor(spec, turn.conversationId as String)
        long deadline = now() + spec.wallClockMs
        Map<String, Integer> seen = [:]
        int toolCallsRun = 0

        while (true) {
            // The cap is enforced before each request: with too little left, none is sent.
            if (result.requestCount >= spec.maxRequests || deadline - now() < MIN_REQUEST_TIMEOUT_MS) return finish(ec, pinnedTenantId, turnId, result, "BUDGET")
            if (!tenantStill(ec, pinnedTenantId)) return finish(ec, pinnedTenantId, turnId, result, "TENANT_CHANGED")
            if (turn.grantId && darpan.agent.AgentAccess.access(ec, pinnedTenantId, turn.userId as String).state != darpan.agent.AgentAccess.LIVE)
                return finish(ec, pinnedTenantId, turnId, result, "NO_ACCESS")

            Message response
            try {
                response = sendWithRetries(gateway, buildParams(spec, result.messages), deadline)
            } catch (StreamInterruptedException e) {
                // Billed: the stream had started. What it reported counts toward the turn and the tenant's daily cap.
                if (e.usage != null) {
                    result.requestCount++
                    AgentUsageLedger.record(ec, pinnedTenantId, turnId, e.usage, e.model ?: spec.model)
                }
                return unavailable(ec, pinnedTenantId, turnId, result, e)
            } catch (RateLimitException | UnauthorizedException | PermissionDeniedException | SseException e) {
                return unavailable(ec, pinnedTenantId, turnId, result, e)
            } catch (AnthropicServiceException e) {
                if (e.statusCode() >= 500) return unavailable(ec, pinnedTenantId, turnId, result, e)
                // A 4xx is a request this code built wrong: never hidden as UNAVAILABLE, and the turn is closed first.
                finish(ec, pinnedTenantId, turnId, result, "ERROR")
                throw e
            } catch (AnthropicIoException | AnthropicRetryableException | ModelUnavailableException e) {
                return unavailable(ec, pinnedTenantId, turnId, result, e)
            }
            result.requestCount++
            result.finalMessage = response
            result.stopReason = response.stopReason().map { it.asString() }.orElse(null)
            AgentUsageLedger.record(ec, pinnedTenantId, turnId, response.usage(), response.model().asString() ?: spec.model)

            StopReason stop = response.stopReason().orElse(null)
            if (stop == StopReason.TOOL_USE) {
                result.messages.add(response.toParam())
                List<ContentBlockParam> answers = []
                Map control = null
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue
                    ToolUseBlock use = block.asToolUse()
                    if (control != null) {
                        // The step already ended on a control tool: every later call of the round is answered, not run,
                        // so the caller can resume with a tool_result for each tool_use id.
                        answers << toolResult(use.id(), "NOT_RUN: step ended on ${control.tool}".toString(), true)
                        continue
                    }
                    Map input = inputOf(use)
                    String key = use.name() + "\u0000" + canonical(input)
                    int times = (seen[key] ?: 0) + 1
                    if (times >= SAME_CALL_LIMIT) return finish(ec, pinnedTenantId, turnId, result, "LOOP")
                    if (toolCallsRun >= spec.maxToolCalls) return finish(ec, pinnedTenantId, turnId, result, "BUDGET")
                    if (now() > deadline) return finish(ec, pinnedTenantId, turnId, result, "BUDGET")
                    Map done
                    try {
                        done = input == null ? [isError: true, content: AgentToolExecutor.INVALID_INPUT + ": input must be an object"]
                                : tools.execute(ec, pinnedTenantId, use.name(), input)
                    } catch (TenantChangedException ignored) {
                        return finish(ec, pinnedTenantId, turnId, result, "TENANT_CHANGED")
                    }
                    seen[key] = times
                    toolCallsRun++
                    if (done.control) {
                        control = [tool: use.name(), toolUseId: use.id(), input: input, outcome: done.control]
                        continue
                    }
                    // content is the full result (the transcript's); the model reads modelContent when the executor projected it.
                    String shown = (done.modelContent ?: done.content) as String
                    result.toolCalls << [name: use.name(), input: input, isError: done.isError as boolean, content: done.content,
                                         modelContent: shown, toolUseId: use.id()]
                    answers << toolResult(use.id(), shown, done.isError as boolean)
                }
                if (control != null) {
                    result.control = [tool: control.tool, toolUseId: control.toolUseId, input: control.input]
                    result.pendingToolResults = answers
                    return finish(ec, pinnedTenantId, turnId, result, control.outcome as String)
                }
                if (spec.role == RoleSpec.INVESTIGATOR && result.requestCount + 1 >= spec.maxRequests)
                    answers << ContentBlockParam.ofText(TextBlockParam.builder().text(LAST_REQUEST_NOTICE).build())
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
                return finish(ec, pinnedTenantId, turnId, result, "MAX_TOKENS")
            }
            if (stop == StopReason.REFUSAL) return finish(ec, pinnedTenantId, turnId, result, "REFUSED")
            if (stop == StopReason.END_TURN || stop == StopReason.STOP_SEQUENCE) {
                result.messages.add(response.toParam())
                return finish(ec, pinnedTenantId, turnId, result, "DONE")
            }
            // No stop reason, or one this code does not know: never read as a finished answer.
            logger.warn("Turn ${turnId} ended on an unknown stop reason ${result.stopReason}".toString())
            return finish(ec, pinnedTenantId, turnId, result, "ERROR")
        }
    }

    /**
     * One request. The prefix (tools sorted by name; the system prompt as one text block whose cache marker caches tools and
     * system together; model, thinking and effort) depends only on the spec, so it is byte-identical across turns and caches.
     * A role that may send more than one request also turns on automatic caching (top-level cache_control): the API moves
     * that breakpoint to the end of the conversation, so each tool round reads everything before it from the cache instead
     * of paying full input for it again. The router sends one request, where a cached tail would be written at 1.25 x input
     * and never read. Explicit markers stay at most 3 (system, plus the author's brain catalog), so with this one the
     * request is within the API's 4.
     */
    static MessageCreateParams buildParams(RoleSpec spec, List<MessageParam> messages) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(spec.model)
                .maxTokens(spec.maxTokens)
                .systemOfTextBlockParams([TextBlockParam.builder().text(systemPrompt(spec.systemPromptPath))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()])
                .messages(messages)
        if (spec.tools) b.tools(AgentToolExecutor.toolDefinitions(spec.tools).collect { ToolUnion.ofTool(it) })
        if (spec.maxRequests > 1) b.cacheControl(CacheControlEphemeral.builder().build())
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

    private static ContentBlockParam toolResult(String toolUseId, String content, boolean isError) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(toolUseId).content(content).isError(isError).build())
    }

    private static RoleResult unavailable(def ec, String pinnedTenantId, String turnId, RoleResult result, Exception e) {
        // SDK error messages carry the status and the API's error text, never the key.
        logger.warn("Claude unavailable for turn ${turnId}: ${e.class.simpleName}: ${e.message}".toString())
        return finish(ec, pinnedTenantId, turnId, result, "UNAVAILABLE")
    }

    private static RoleResult finish(def ec, String pinnedTenantId, String turnId, RoleResult result, String outcome) {
        result.outcome = outcome
        AgentUsageLedger.finish(ec, pinnedTenantId, turnId, result.stopReason, outcome)
        return result
    }
}
