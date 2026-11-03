package darpan.agent

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-BE-159. What a person types or pastes is scrubbed before it is stored or sent to the model: API keys, bearer and basic
 * credentials, "password: ..." style secrets, email addresses and phone numbers are replaced by a marker naming what was
 * there. The text is normalised first (AgentRedaction.normalize: invisible characters out, then NFKC), so a zero-width or
 * fullwidth variant cannot slip a key past the patterns. Only the kinds masked are reported, never the values.
 *
 * Regexes only find where a secret starts (a key and its separator, or an auth scheme word); the value itself is walked by a
 * plain loop ({@link #valueEnd}), linear and without recursion, so a long or odd value can neither overflow the regex
 * engine nor end early. Over-masking is accepted; under-masking is not.
 */
class PasteScrubber {

    static final String SECRET_MARKER = "[masked secret]"

    /**
     * A key that names a secret, anywhere in an identifier (accessToken, client_secret, x-api-key, dbPassword), then an
     * optional closing quote, ":" or "=", optional space, and an optional opening quote (group 1).
     */
    private static final Pattern SECRET_KEY = Pattern.compile(
            /(?i)(?:token|secret|password|passwd|pwd|api[_-]?key|auth|credential|private[_-]?key)[A-Za-z0-9_\-]*+["']?\s*[:=]\s*(["']?)/)
    private static final Pattern BEARER = Pattern.compile(/(?i)\bBearer\s+/)
    /** "Basic" capitalised as in the header, so a plain "basic check" is left alone. */
    private static final Pattern BASIC = Pattern.compile(/\bBasic\s+/)
    /** A value already masked by an earlier rule ("[masked ...]", or a scheme word before one) is left as it is. */
    private static final Pattern ALREADY_MASKED = Pattern.compile(/(?i)^(?:(?:bearer|basic)\s+)?\[masked/)

    /** [kind, Closure(String text) -> String], applied in order. */
    private static final List<List> RULES = [
            ["API_KEY", regex(Pattern.compile(/sk-ant-[^\s"'`,;}\])]+/), "[masked API key]")],
            ["AWS_KEY", regex(Pattern.compile(/AKIA[0-9A-Z]{16}/), "[masked access key]")],
            ["BEARER", scan(BEARER, "[masked token]", false)],
            ["BASIC", scan(BASIC, "[masked credentials]", false)],
            ["SECRET", scan(SECRET_KEY, SECRET_MARKER, true)],
            // The customer-data shapes the knowledge leak check uses (darpan/knowledge/private-shapes.json); the email local
            // part only starts at a run boundary and is possessive, so a long run without "@" is read once, not once per start.
            ["EMAIL", regex(Pattern.compile(/(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/), "[masked email]")],
            ["PHONE", regex(Pattern.compile(/(?<![\w.])(?:\+\d[\d\s().-]{7,}\d|(?:\(\d{3}\)[\s.-]?|\d{3}[\s.-])\d{3}[\s.-]\d{4})(?!\w)/), "[masked phone]")],
    ].asImmutable()

    /** [text: the scrubbed text, masked: the kinds masked, in rule order, each once]. */
    static Map scrub(String input) {
        if (input == null) return [text: null, masked: []]
        String text = AgentRedaction.normalize(input)
        List<String> masked = []
        RULES.each { List rule ->
            String next = ((Closure<String>) rule[1]).call(text)
            if (next != text) masked << (rule[0] as String)
            text = next
        }
        return [text: text, masked: masked]
    }

    private static Closure<String> regex(Pattern pattern, String marker) {
        return { String text -> pattern.matcher(text).replaceAll(Matcher.quoteReplacement(marker)) }
    }

    /**
     * Masks the value after each match of start. For a key (keyed), the value is quoted when the match ended on an opening
     * quote; for a scheme word, when the word itself sits right after a quote (a JSON "Bearer ..." string).
     */
    private static Closure<String> scan(Pattern start, String marker, boolean keyed) {
        return { String text ->
            Matcher m = start.matcher(text)
            StringBuilder out = null
            int copied = 0
            int from = 0
            while (from < text.length() && m.find(from)) {
                int valueStart = m.end()
                from = valueStart
                char quote = 0
                if (keyed && m.group(1)) quote = m.group(1).charAt(0)
                if (!keyed && m.start() > 0 && (text.charAt(m.start() - 1) == '"' as char || text.charAt(m.start() - 1) == "'" as char))
                    quote = text.charAt(m.start() - 1)
                String head = text.substring(valueStart, Math.min(text.length(), valueStart + 24))
                if (ALREADY_MASKED.matcher(head).find()) continue
                int valueEnd = valueEnd(text, valueStart, quote)
                if (valueEnd <= valueStart) continue
                if (out == null) out = new StringBuilder(text.length())
                out.append(text, copied, valueStart).append(marker)
                copied = valueEnd
                from = valueEnd
            }
            if (out == null) return text
            out.append(text, copied, text.length())
            return out.toString()
        }
    }

    /**
     * Where a value starting at i ends (exclusive), by a plain walk.
     * Quoted (quote != 0): up to the matching closing quote, which stays; a backslash escapes the next character, newline
     * included; no closing quote, or a backslash at the end, masks to the end of the input.
     * Unquoted: the whole run of non-space characters, except one trailing , } or ] kept when whitespace or the end follows
     * it (JSON structure); everything else in the run, ) ; " ' included, is part of the value.
     */
    static int valueEnd(String text, int i, char quote) {
        int n = text.length()
        if (quote != (char) 0) {
            int j = i
            while (j < n) {
                char c = text.charAt(j)
                if (c == '\\' as char) { j += 2; continue }
                if (c == quote) return j
                j++
            }
            return n
        }
        int j = i
        while (j < n && !Character.isWhitespace(text.charAt(j))) j++
        if (j > i + 1) {
            char last = text.charAt(j - 1)
            if ((last == ',' as char || last == '}' as char || last == ']' as char) && (j == n || Character.isWhitespace(text.charAt(j)))) j--
        }
        return j
    }
}
