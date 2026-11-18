package darpan.agent.config

import darpan.agent.loop.AgentToolExecutor

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-BE-307. The agent's words as data: tool descriptions (read from tools.json, which keeps each tool's name, schema and service),
 * the replies a person reads, and the notices the model reads. These are today's strings, copied verbatim, with each interpolation
 * written as a {token}. An AgentText row is an edit over them (AgentConfig.text); a reset deletes the row. validTextError says what an
 * edit may be: the same tokens as the default, plain text, within a length cap.
 */
class AgentTextDefaults {
    static final int MAX_TOOL_CHARS = 2000
    static final int MAX_OTHER_CHARS = 1000
    private static final Pattern TOKEN = Pattern.compile(/\{([a-z]+)\}/)

    static final Map<String, String> TEXTS = build()

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>()
        AgentToolExecutor.TOOLS.each { String name, Map tool -> m.put("tool." + name, tool.description as String) }
        m.putAll([
                "reply.DECLINE"               : "That is outside what I can do here. I create and maintain this tenant's audit checks: setting up an audit, its statements and checks, why a finding is open or closed, and changes to their rules, exclusions, fix wording and causes. Ask about one of those.",
                "reply.NOTHING_CAME_OUT"      : "I could not put an answer together this time; nothing was changed. Ask again, or name the finding, record or statement you mean.",
                "reply.CLARIFY"               : "Which finding, record or statement do you mean?",
                "reply.TENANT_CHANGED"        : "You switched tenant while I was working, so I stopped; nothing was changed in the tenant you switched to.",
                "reply.STOPPED"               : "I stopped before finishing: {why}. Nothing was changed beyond what is listed; ask again, or narrow the question.",
                "reason.BUDGET"               : "I reached my limit of steps for one message",
                "reason.LOOP"                 : "I kept asking for the same data",
                "reason.MAX_TOKENS"           : "my answer ran past its length limit",
                "reason.REFUSED"              : "the model declined to work on this message",
                "reason.NO_ACCESS"            : "your AI access changed while I was working",
                "reason.INVALID_EVIDENCE"     : "I could not read back what I had found",
                "reason.ERROR"                : "something went wrong on our side",
                "reply.RATE_LIMITED"          : "You have sent {count} messages to the agent in the last hour; try again later.",
                "reply.OVER_BUDGET"           : "The agent's budget for this tenant is spent for today (UTC).",
                "reply.NOT_STORED"            : "Your message could not be stored; nothing was changed. Try again.",
                "reply.FAILED"                : "Something went wrong on our side; nothing was changed.",
                "reply.FAILED_AFTER_DRAFTING" : "Something went wrong on our side after drafting {made}. A proposal changes nothing until a person approves it; nothing else was changed.",
                "reply.OUT_OF_TIME"           : "I ran out of time for this message; ask again for the rest.",
                "reply.NEED_MORE_EVIDENCE"    : "I need more evidence than one message allows; tell me which finding or record to look at.",
                "reply.AGENT_REFUSED"         : "The agent is down for a fix on our side; Darpan staff have been alerted. Nothing was changed.",
                "reply.AGENT_UNAVAILABLE"     : "The agent cannot reach Claude right now; try again in a few minutes. Nothing was changed.",
                "reply.AGENT_OFF"             : "The AI agent is off for this tenant; a Darpan admin can turn it on. Nothing was changed.",
                "reply.UNAVAILABLE_MID_TURN"  : "The agent cannot reach Claude right now; try again later.",
                "reply.SWITCHED_OFF_MID_TURN" : "The agent was switched off for this tenant.",
                "reply.ACCESS_EXPIRED"        : "Your AI access ended on {day}.",
                "reply.ACCESS_PAUSED_ADMIN"   : "Your AI access is paused; a Darpan admin can lift it.",
                "reply.ACCESS_PAUSED_LIMIT"   : "Your AI access is paused at its spending limit; a Darpan admin can lift it.",
                "reply.ACCESS_NOT_GRANTED"    : "AI is not on for you in this tenant; a Darpan admin can turn it on.",
                "reply.TYPED_VALUES"          : "These values came only from system or customer data, not from you: {values}{more}. If you want them in the change, type them yourself.",
                "notice.ANSWER_MISSING"       : "Your evidence has no answer, and the person asked a question. Write the short answer from the evidence so far, citing ids; read again only what it lacks.",
                "notice.SAME_AS_EARLIER"      : "SAME_AS_EARLIER: you made this exact call earlier in this turn; its result is above and has not changed. Work from it.",
                "notice.REPEAT"               : "You are repeating a read you already have, so no more tool calls will run. Return the evidence JSON now from what you have read, and say in it what you could not see.",
                "notice.LAST_REQUEST"         : "This is your last request: no tool you call now will run. Return the evidence JSON now from what you have read, and say in it what you could not check."])
        return Collections.unmodifiableMap(m)
    }

    /** The {tokens} of a text, in order of first appearance. */
    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>()
        if (text == null) return out
        Matcher m = TOKEN.matcher(text)
        while (m.find()) out.add(m.group(1))
        return out
    }

    /** Null when the text is a valid edit of this id; otherwise the one reason it is not (shown to the admin). */
    static String validTextError(String textId, String text) {
        String base = textId == null ? null : TEXTS.get(textId)
        if (base == null) return "Unknown agent text ${textId}.".toString()
        if (text == null || !text.trim()) return "The text is empty."
        int cap = textId.startsWith("tool.") ? MAX_TOOL_CHARS : MAX_OTHER_CHARS
        if (text.length() > cap) return "The text is longer than ${cap} characters.".toString()
        if (text.contains("<") || text.contains(">") || text.contains("`") || text.contains("*") || text.contains("](")) return "Plain text only: no markup."
        Set<String> want = tokens(base)
        String stray = TOKEN.matcher(text).replaceAll("")
        if (tokens(text) != want || stray.contains("{") || stray.contains("}")) {
            return want ? "The text must use exactly ${want.collect { '{' + it + '}' }.join(', ')} — the same as the default.".toString()
                    : "The text must use no {…} slots."
        }
        return null
    }

    /** The text with each {token} replaced by its value in one pass: plain substitution, nothing evaluated, values never re-scanned. A token with no value stays as written. */
    static String substitute(String text, Map tokens) {
        if (!tokens) return text
        Matcher m = TOKEN.matcher(text)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            Object v = tokens.get(m.group(1))
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v.toString()))
        }
        m.appendTail(sb)
        return sb.toString()
    }
}
