package darpan.agent.loop

import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam

/**
 * DAR-BE-159. How one role run ended. outcome: DONE, BUDGET, LOOP, REFUSED, MAX_TOKENS, TENANT_CHANGED, UNAVAILABLE, or (a
 * control tool) QUESTION / MORE_EVIDENCE with {@link #control} = [tool, toolUseId, input]. When a control tool ends a round,
 * {@link #pendingToolResults} holds that round's other tool results, so the caller answers them with the control's own
 * tool_result in one user message.
 */
class RoleResult {
    String outcome
    /** The last model response, or null when none arrived. */
    Message finalMessage
    /** [name, input, isError, content, toolUseId] per tool call answered, in order. */
    List<Map> toolCalls = []
    /** The conversation as sent plus every assistant turn appended (thinking blocks unchanged). */
    List<MessageParam> messages = []
    Map control
    List<ContentBlockParam> pendingToolResults = []
    int requestCount
    String stopReason

    /** The final message's text blocks, joined. */
    String text() {
        if (finalMessage == null) return null
        return finalMessage.content().findAll { it.isText() }.collect { it.asText().text() }.join("")
    }
}
