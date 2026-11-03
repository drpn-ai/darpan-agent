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
 *
 * A quote is a straight quote, a curly or low-9 quote, or a guillemet, and any run of backslashes before it (JSON escaped
 * inside a string, once or twice) is part of it. A value is left alone only when it is exactly one of this class's own
 * markers, so scrubbing is idempotent and a value that merely starts like a marker is still masked.
 */
class PasteScrubber {

    static final String SECRET_MARKER = "[masked secret]"
    static final String TOKEN_MARKER = "[masked token]"
    static final String CREDENTIALS_MARKER = "[masked credentials]"
    static final String API_KEY_MARKER = "[masked API key]"
    static final String ACCESS_KEY_MARKER = "[masked access key]"
    static final String EMAIL_MARKER = "[masked email]"
    static final String PHONE_MARKER = "[masked phone]"
    /** Every marker this class writes. A value that is exactly one of these (after an optional scheme word) is not masked again. */
    private static final List<String> OWN_MARKERS = [SECRET_MARKER, TOKEN_MARKER, CREDENTIALS_MARKER, API_KEY_MARKER,
            ACCESS_KEY_MARKER, EMAIL_MARKER, PHONE_MARKER].asImmutable()

    /**
     * Opening quotes, and at the same index in CLOSERS the characters that close each: straight quotes close themselves,
     * “ ‘ « ‹ close at their partner, „ ‚ at either curly partner, and ” ’ » › (opening in some languages) at themselves or
     * their partner. NFKC folds none of these.
     */
    private static final String OPENERS = "\"'\u201C\u201D\u2018\u2019\u201E\u201A\u00AB\u00BB\u2039\u203A"
    private static final List<String> CLOSERS = ["\"", "'", "\u201D", "\u201D", "\u2019", "\u2019", "\u201C\u201D",
            "\u2018\u2019", "\u00BB", "\u00AB\u00BB", "\u203A", "\u2039\u203A"].asImmutable()
    private static final String QUOTE_CLASS = "[" + OPENERS + "]"

    /**
     * A key that names a secret, anywhere in an identifier (accessToken, client_secret, x-api-key, dbPassword, passcode),
     * then an optional closing quote, ":" or "=", optional space, and an optional opening quote (group 1). Either quote may
     * carry a run of backslashes ({\"password\":\"x\"}); group 1 keeps them, as the value's close must repeat them.
     */
    private static final Pattern SECRET_KEY = Pattern.compile(
            /(?i)(?:token|secret|password|passwd|passcode|passphrase|pwd|api[_-]?key|auth|credential|private[_-]?key)[A-Za-z0-9_\-]*+(?:\\*+${QUOTE_CLASS})?\s*[:=]\s*(\\*+${QUOTE_CLASS})?/)
    private static final Pattern BEARER = Pattern.compile(/(?i)\bBearer\s+/)
    /** "Basic" capitalised as in the header, so a plain "basic check" is left alone. */
    private static final Pattern BASIC = Pattern.compile(/\bBasic\s+/)

    /** [kind, Closure(String text) -> String], applied in order. */
    private static final List<List> RULES = [
            ["API_KEY", regex(Pattern.compile(/sk-ant-[^\s"'`,;}\])]+/), API_KEY_MARKER)],
            ["AWS_KEY", regex(Pattern.compile(/AKIA[0-9A-Z]{16}/), ACCESS_KEY_MARKER)],
            ["BEARER", scan(BEARER, TOKEN_MARKER, false)],
            ["BASIC", scan(BASIC, CREDENTIALS_MARKER, false)],
            ["SECRET", scan(SECRET_KEY, SECRET_MARKER, true)],
            // The customer-data shapes the knowledge leak check uses (darpan/knowledge/private-shapes.json); the email local
            // part only starts at a run boundary and is possessive, so a long run without "@" is read once, not once per start.
            ["EMAIL", regex(Pattern.compile(/(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/), EMAIL_MARKER)],
            ["PHONE", regex(Pattern.compile(/(?<![\w.])(?:\+\d[\d\s().-]{7,}\d|(?:\(\d{3}\)[\s.-]?|\d{3}[\s.-])\d{3}[\s.-]\d{4})(?!\w)/), PHONE_MARKER)],
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
     * quote; for a scheme word, when the word itself sits right after a quote (a JSON "Bearer ..." string). Backslashes
     * before that quote are counted: the value then closes only at a quote with the same run before it.
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
                int escapes = 0
                if (keyed) {
                    String open = m.group(1)
                    if (open) {
                        quote = open.charAt(open.length() - 1)
                        escapes = open.length() - 1
                    }
                } else if (m.start() > 0 && OPENERS.indexOf((int) text.charAt(m.start() - 1)) >= 0) {
                    int q = m.start() - 1
                    quote = text.charAt(q)
                    int b = q
                    while (b > 0 && text.charAt(b - 1) == '\\' as char) b--
                    escapes = q - b
                }
                int walkFrom = keyed ? afterScheme(text, valueStart) : valueStart
                int markerEnd = ownMarkerEnd(text, walkFrom)
                if (markerEnd >= 0) {
                    boolean exact = quote != (char) 0 ? closesAt(text, markerEnd, quote, escapes) : endsUnquotedAt(text, markerEnd)
                    if (exact) continue
                    walkFrom = markerEnd
                }
                int valueEnd = valueEnd(text, walkFrom, quote, escapes)
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

    /** Past a leading "Bearer " or "Basic " (any case, any whitespace) at i, or i itself when there is none. */
    private static int afterScheme(String text, int i) {
        int n = text.length()
        int p
        if (text.regionMatches(true, i, "bearer", 0, 6)) p = i + 6
        else if (text.regionMatches(true, i, "basic", 0, 5)) p = i + 5
        else return i
        int j = p
        while (j < n && Character.isWhitespace(text.charAt(j))) j++
        return j > p ? j : i
    }

    /** Where one of this class's own markers starting at i ends, or -1 when none starts there. */
    private static int ownMarkerEnd(String text, int i) {
        for (String own : OWN_MARKERS) if (text.startsWith(own, i)) return i + own.length()
        return -1
    }

    /** Whether the quoted value closes at e: exactly `escapes` backslashes, then a closer of quote. */
    private static boolean closesAt(String text, int e, char quote, int escapes) {
        int n = text.length()
        int k = e
        while (k < n && text.charAt(k) == '\\' as char) k++
        return k - e == escapes && k < n && closersFor(quote).indexOf((int) text.charAt(k)) >= 0
    }

    /** Whether an unquoted value ends at e, by the same rule as {@link #valueEnd}: the end, whitespace, or one , } ] before either. */
    private static boolean endsUnquotedAt(String text, int e) {
        int n = text.length()
        if (e == n || Character.isWhitespace(text.charAt(e))) return true
        char c = text.charAt(e)
        return (c == ',' as char || c == '}' as char || c == ']' as char) && (e + 1 == n || Character.isWhitespace(text.charAt(e + 1)))
    }

    private static String closersFor(char quote) {
        int k = OPENERS.indexOf((int) quote)
        return k < 0 ? String.valueOf(quote) : CLOSERS[k]
    }

    static int valueEnd(String text, int i, char quote) {
        return valueEnd(text, i, quote, 0)
    }

    /**
     * Where a value starting at i ends (exclusive), by a plain walk.
     * Quoted (quote != 0) with no escapes: up to a closer of quote, which stays; a backslash escapes the next character,
     * newline included; no closer, or a backslash at the end, masks to the end of the input.
     * Quoted with escapes (the quote opened as \" or \\\"): up to the first run of exactly that many backslashes followed by a
     * closer, which stays; any other run, and any bare quote, is part of the value; none masks to the end of the input.
     * Unquoted: the whole run of non-space characters, except one trailing , } or ] kept when whitespace or the end follows
     * it (JSON structure); everything else in the run, ) ; " ' included, is part of the value.
     */
    static int valueEnd(String text, int i, char quote, int escapes) {
        int n = text.length()
        if (quote != (char) 0) {
            String closers = closersFor(quote)
            int j = i
            while (j < n) {
                char c = text.charAt(j)
                if (c == '\\' as char) {
                    if (escapes == 0) { j += 2; continue }
                    int k = j
                    while (k < n && text.charAt(k) == '\\' as char) k++
                    if (k < n && closers.indexOf((int) text.charAt(k)) >= 0) {
                        if (k - j == escapes) return j
                        k++
                    }
                    j = k
                    continue
                }
                if (escapes == 0 && closers.indexOf((int) c) >= 0) return j
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
