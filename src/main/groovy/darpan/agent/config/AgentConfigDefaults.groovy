package darpan.agent.config

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-307. What the agent does with no edit rows: today's role models, prices and prompt files. These ship in the jar; a row in
 * the agent config tables is an edit over them (see AgentConfig). Copied verbatim from the code they replace.
 */
class AgentConfigDefaults {
    static final String ROUTER = "ROUTER"
    static final String INVESTIGATOR = "INVESTIGATOR"
    static final String AUTHOR = "AUTHOR"
    static final String AUTHOR_ESCALATION = "AUTHOR_ESCALATION"
    static final String EMAIL_SMILE = "EMAIL_SMILE"
    static final String AUDIT_RUN_WORDS = "AUDIT_RUN_WORDS"
    /** The roles that run as a turn (the preflight and the daily cap care about these). */
    static final List<String> TURN_ROLES = [ROUTER, INVESTIGATOR, AUTHOR, AUTHOR_ESCALATION].asImmutable()

    static final Map<String, String> ROLE_MODELS = [
            (ROUTER): "claude-sonnet-5-5", (INVESTIGATOR): "claude-sonnet-5-5", (AUTHOR): "claude-sonnet-5-5",
            (AUTHOR_ESCALATION): "claude-opus-5-5", (EMAIL_SMILE): "claude-haiku-5-5", (AUDIT_RUN_WORDS): "claude-haiku-5-5"].asImmutable()

    /** Dollars per million tokens: input, output, cache read, cache write (the 5-minute write, 1.25 x input, written out). */
    static final Map<String, ModelPrice> PRICES = [
            "claude-opus-5-5"  : new ModelPrice(4.0G, 20.0G, 0.20G, 5.0G),
            "claude-sonnet-5-5": new ModelPrice(2.0G, 10.0G, 0.20G, 2.5G),
            "claude-haiku-5-5" : new ModelPrice(0.10G, 0.50G, 0.01G, 0.125G)].asImmutable()

    static final Map<String, String> PROMPT_PATHS = [
            "router": "darpan/agent/prompts/router.md", "investigator": "darpan/agent/prompts/investigator.md",
            "author": "darpan/agent/prompts/author.md", "email-smile": "darpan/agent/prompts/email-smile.md",
            "audit-run-words": "darpan/agent/prompts/audit-run-words.md"].asImmutable()

    private static final Map<String, String> TEXTS = new ConcurrentHashMap<>()

    /** The text of a class-path file, cached for good. */
    static String classpathText(String path) {
        return TEXTS.computeIfAbsent(path) { String p ->
            InputStream stream = AgentConfigDefaults.class.classLoader.getResourceAsStream(p)
            if (stream == null) throw new IllegalStateException("Agent prompt ${p} is missing".toString())
            try { return stream.getText("UTF-8") } finally { stream.close() }
        }
    }
}
