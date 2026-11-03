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

    /** [kind, pattern, replacement], applied in order: a key inside "api_key=..." is masked as a key, then left alone. */
    private static final List<List> RULES = [
            ["API_KEY", Pattern.compile(/sk-ant-\S+/), "[masked API key]"],
            ["AWS_KEY", Pattern.compile(/AKIA[0-9A-Z]{16}/), "[masked access key]"],
            ["BEARER", Pattern.compile(/(?i)\bBearer\s+(?!\[masked)\S+/), "Bearer [masked token]"],
            ["SECRET", Pattern.compile(/(?i)\b(password|token|secret|api[_-]?key)(\s*[:=]\s*)(?!\[masked)\S+/), '$1$2[masked secret]'],
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
            text = m.replaceAll(rule[2] as String)
        }
        return [text: text, masked: masked]
    }
}
