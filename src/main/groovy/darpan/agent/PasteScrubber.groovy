package darpan.agent

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-BE-159. What a person types or pastes is scrubbed before it is stored or sent to the model: API keys, bearer tokens,
 * "password: ..." style secrets, email addresses and phone numbers are replaced by a marker naming what was there. The text
 * is normalised first (AgentRedaction.normalize: invisible characters out, then NFKC), so a zero-width or fullwidth variant
 * cannot slip a key past the patterns. Only the kinds masked are reported, never the values.
 */
class PasteScrubber {

    /** A token in a header or a key: base64url and friends, so JSON punctuation around it ("…", , } ]) is left alone. */
    private static final String TOKEN = /[A-Za-z0-9\-._~+\/]+=*/

    /**
     * A secret's value after its key and separator: a double- or single-quoted string, masked through its closing quote
     * (backslash escapes honoured; an unclosed quote runs to the end), or an unquoted run of non-space characters without a
     * trailing , ; } ] ) or quote.
     */
    private static final Pattern SECRET = Pattern.compile(
            /(?i)(?<![A-Za-z])(password|passwd|token|secret|api[_-]?key)(["']?\s*[:=]\s*)/ +
            /(?:"(?:[^"\\]|\\.)*(?:"|$)|'(?:[^'\\]|\\.)*(?:'|$)|(?!\[masked)([^\s"']*[^\s,;}\])"']))/)

    /** [kind, pattern, replacement (a String, or a Closure of the Matcher)], applied in order. */
    private static final List<List> RULES = [
            ["API_KEY", Pattern.compile(/sk-ant-[^\s"'`,;}\])]+/), "[masked API key]"],
            ["AWS_KEY", Pattern.compile(/AKIA[0-9A-Z]{16}/), "[masked access key]"],
            ["BEARER", Pattern.compile(/(?i)\bBearer\s+(?!\[masked)/ + TOKEN), "Bearer [masked token]"],
            // An HTTP Basic credential; "Basic" capitalised as in the header, so a plain "basic check" is left alone.
            ["BASIC", Pattern.compile(/\bBasic\s+(?!\[masked)/ + TOKEN), "Basic [masked credentials]"],
            // Not \b: the key may end a longer name (access_token, client_secret, token_secret); the key may be quoted
            // ("password": ...), and a quoted value keeps its quotes around the marker.
            ["SECRET", SECRET, { Matcher m ->
                String value = m.group(0).substring(m.end(2) - m.start())
                String quote = value.startsWith('"') ? '"' : value.startsWith("'") ? "'" : ""
                return m.group(1) + m.group(2) + quote + "[masked secret]" + quote
            }],
            // The customer-data shapes the knowledge leak check uses (darpan/knowledge/private-shapes.json).
            ["EMAIL", Pattern.compile(/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/), "[masked email]"],
            ["PHONE", Pattern.compile(/(?<![\w.])(?:\+\d[\d\s().-]{7,}\d|(?:\(\d{3}\)[\s.-]?|\d{3}[\s.-])\d{3}[\s.-]\d{4})(?!\w)/), "[masked phone]"],
    ].asImmutable()

    /** [text: the scrubbed text, masked: the kinds masked, in rule order, each once]. */
    static Map scrub(String input) {
        if (input == null) return [text: null, masked: []]
        String text = AgentRedaction.normalize(input)
        List<String> masked = []
        RULES.each { List rule ->
            Matcher m = ((Pattern) rule[1]).matcher(text)
            if (!m.find()) return
            masked << (rule[0] as String)
            if (rule[2] instanceof Closure) {
                Closure replace = (Closure) rule[2]
                StringBuilder out = new StringBuilder()
                m.reset()
                while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(replace.call(m) as String))
                m.appendTail(out)
                text = out.toString()
            } else {
                text = m.replaceAll(Matcher.quoteReplacement(rule[2] as String))
            }
        }
        return [text: text, masked: masked]
    }
}
