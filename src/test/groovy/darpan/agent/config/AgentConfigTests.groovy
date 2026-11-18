package darpan.agent.config

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-307: the agent's own config tables over their code defaults, through a cached snapshot with a last-good fallback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentConfigTests {
    private ExecutionContext ec

    @BeforeAll
    void boot() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "agent-config")
    }

    @AfterAll
    void shutdown() {
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
        ec?.destroy()
    }

    @BeforeEach
    void fresh() {
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
        AgentConfigTestSupport.clear(ec)
    }

    @AfterEach
    void after() {
        AgentConfigTestSupport.clear(ec)
        AgentConfig.resetForTest()
        AgentConfigTestSupport.clearSettings()
    }

    private static void assertDec(Number expected, BigDecimal actual) {
        assertNotNull(actual)
        assertEquals(0, new BigDecimal(expected.toString()).compareTo(actual), "expected ${expected} but was ${actual}".toString())
    }

    @Test
    @DisplayName("with no rows every value is today's default")
    void defaultsWithNoRows() {
        AgentConfig c = AgentConfig.current(ec)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/router.md"), c.prompt("router"))
        assertEquals("claude-sonnet-5-5", c.model(AgentConfigDefaults.AUTHOR))
        assertEquals("claude-opus-5-5", c.model(AgentConfigDefaults.AUTHOR_ESCALATION))
        assertDec(5.0, c.price("claude-opus-5-5").cacheWrite)
        assertNull(c.tenantCap("ANY"))
    }

    @Test
    @DisplayName("an edited row wins; a deleted row falls back")
    void editedRowWinsDeletedFallsBack() {
        AgentConfigTestSupport.set(ec, [
                AgentRoleConfig: [[roleId: "AUTHOR", modelId: "claude-opus-5-5"]],
                AgentPrompt    : [[promptId: "router", promptText: "EDITED ROUTER"]],
                AgentModelPrice: [[modelId: "claude-opus-5-5", inputUsdPerMTok: 9, outputUsdPerMTok: 20, cacheReadUsdPerMTok: 1, cacheWriteUsdPerMTok: 11]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals("claude-opus-5-5", c.model("AUTHOR"))
        assertEquals("EDITED ROUTER", c.prompt("router"))
        assertEquals("EDITED ROUTER", c.promptForPath("darpan/agent/prompts/router.md"))
        assertDec(9, c.price("claude-opus-5-5").input)
        AgentConfigTestSupport.clear(ec)
        c = AgentConfig.current(ec)
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/router.md"), c.prompt("router"))
        assertDec(4, c.price("claude-opus-5-5").input)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/author.md"), c.promptForPath("darpan/agent/prompts/author.md"))
        assertNotNull(c.promptForPath("darpan/agent/prompts/tools-not-a-prompt.md".replace("tools-not-a-prompt", "router")))
        assertThrows(IllegalStateException) { c.promptForPath("darpan/agent/prompts/nope.md") }
    }

    @Test
    @DisplayName("an added price prices a new model")
    void addedPrice() {
        assertNull(AgentConfig.current(ec).price("claude-new-9"))
        AgentConfigTestSupport.set(ec, [AgentModelPrice: [[modelId: "claude-new-9", inputUsdPerMTok: 1, outputUsdPerMTok: 5, cacheReadUsdPerMTok: 0.1, cacheWriteUsdPerMTok: 1.25]]])
        assertDec(5, AgentConfig.current(ec).price("claude-new-9").output)
        assertDec(20, AgentConfig.current(ec).dearest().output)
    }

    @Test
    @DisplayName("a loaded price above the one ceiling (the admin save's) is dropped, at the ceiling it is kept")
    void priceCeiling() {
        assertDec(1000, AgentConfig.MAX_PRICE)
        AgentConfigTestSupport.set(ec, [AgentModelPrice: [
                [modelId: "claude-at-1", inputUsdPerMTok: 1000, outputUsdPerMTok: 5, cacheReadUsdPerMTok: 0, cacheWriteUsdPerMTok: 0],
                [modelId: "claude-over-1", inputUsdPerMTok: 1000.01, outputUsdPerMTok: 5, cacheReadUsdPerMTok: 0, cacheWriteUsdPerMTok: 0]]])
        assertDec(1000, AgentConfig.current(ec).price("claude-at-1").input)
        assertNull(AgentConfig.current(ec).price("claude-over-1"))
    }

    @Test
    @DisplayName("unpricedRoles lists a role whose model has no price and skips an escalation set to none")
    void unpricedRoles() {
        AgentConfigTestSupport.set(ec, [AgentRoleConfig: [[roleId: "ROUTER", modelId: "claude-unpriced-1"], [roleId: "AUTHOR_ESCALATION", modelId: "none"]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals(["ROUTER"], c.unpricedRoles(AgentConfigDefaults.TURN_ROLES))
    }

    @Test
    @DisplayName("none is accepted only for the escalation author; a raw none on another role keeps its default")
    void noneOnlyForEscalation() {
        AgentConfigTestSupport.set(ec, [AgentRoleConfig: [[roleId: "ROUTER", modelId: "none"], [roleId: "INVESTIGATOR", modelId: "none"],
                                                         [roleId: "AUTHOR", modelId: "none"], [roleId: "AUTHOR_ESCALATION", modelId: "none"]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals("claude-sonnet-5-5", c.model("ROUTER"))
        assertEquals("claude-sonnet-5-5", c.model("INVESTIGATOR"))
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertEquals("none", c.model("AUTHOR_ESCALATION"))
    }

    @Test
    @DisplayName("the tenant override is valid only inside the global cap's range")
    void tenantOverrideRange() {
        AgentConfigTestSupport.set(ec, [AgentTenantCap: [
                [tenantUserGroupId: "T_FIVE", dailyUsdCap: 5], [tenantUserGroupId: "T_ZERO", dailyUsdCap: 0],
                [tenantUserGroupId: "T_BIG", dailyUsdCap: 5000]]])
        AgentConfig c = AgentConfig.current(ec)
        assertDec(5, c.tenantCap("T_FIVE"))
        assertNull(c.tenantCap("T_ZERO"))
        assertNull(c.tenantCap("T_BIG"))
        assertNull(c.tenantCap("T_OTHER"), "another tenant's override never applies")
        assertNull(c.tenantCap(null))
    }

    @Test
    @DisplayName("an invalid row falls back to its default")
    void invalidRowFallsBack() {
        AgentConfigTestSupport.set(ec, [
                AgentRoleConfig: [[roleId: "AUTHOR", modelId: "bad id"], [roleId: "NOT_A_ROLE", modelId: "claude-opus-5-5"]],
                AgentModelPrice: [[modelId: "claude-sonnet-5-5", inputUsdPerMTok: -1, outputUsdPerMTok: 10, cacheReadUsdPerMTok: 0.2, cacheWriteUsdPerMTok: 2.5]],
                AgentPrompt    : [[promptId: "author", promptText: "  "], [promptId: "unknown", promptText: "x"]]])
        AgentConfig c = AgentConfig.current(ec)
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertNull(c.model("NOT_A_ROLE"))
        assertDec(2, c.price("claude-sonnet-5-5").input)
        assertEquals(AgentConfigDefaults.classpathText("darpan/agent/prompts/author.md"), c.prompt("author"))
    }

    @Test
    @DisplayName("a change shows after expire and after the TTL, not before")
    void changeShowsAfterExpireOrTtl() {
        long now = 1_000_000L
        AgentConfig.clock = { -> now }
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"))
        // a row written straight to the table (no expire) is not seen inside the TTL
        boolean was = ec.artifactExecution.disableAuthz()
        try { ec.entity.makeValue("darpan.agent.AgentRoleConfig").setAll([roleId: "AUTHOR", modelId: "claude-opus-5-5"]).createOrUpdate() } finally { if (!was) ec.artifactExecution.enableAuthz() }
        now += AgentConfig.TTL_MS - 1
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"))
        now += 1
        assertEquals("claude-opus-5-5", AgentConfig.current(ec).model("AUTHOR"))
        // and expire() shows an edit at once
        AgentConfigTestSupport.set(ec, [AgentRoleConfig: [[roleId: "AUTHOR", modelId: "claude-haiku-5-5"]]])
        assertEquals("claude-haiku-5-5", AgentConfig.current(ec).model("AUTHOR"))
    }

    @Test
    @DisplayName("a failed load keeps the last snapshot")
    void failedLoadKeepsLast() {
        long now = 5_000L
        AgentConfig.clock = { -> now }
        AgentConfig good = AgentConfig.defaults().with([models: [AUTHOR: "claude-opus-5-5"]])
        int calls = 0
        AgentConfig.loader = { e -> if (calls++ == 0) return good; throw new IllegalStateException("db down") }
        assertEquals("claude-opus-5-5", AgentConfig.current(ec).model("AUTHOR"))
        now += AgentConfig.TTL_MS
        assertEquals("claude-opus-5-5", AgentConfig.current(ec).model("AUTHOR"), "the failed reload keeps the last good snapshot")
        assertEquals(2, calls)
        now += AgentConfig.TTL_MS
        AgentConfig.loader = { e -> AgentConfig.defaults() }
        assertEquals("claude-sonnet-5-5", AgentConfig.current(ec).model("AUTHOR"), "a later good load replaces it")
    }

    @Test
    @DisplayName("without the framework current() is defaults")
    void noFramework() {
        AgentConfig.frameworkUp = { -> false }
        AgentConfig.loader = { e -> throw new AssertionError("must not load") }
        AgentConfig c = AgentConfig.current(null)
        assertEquals("claude-sonnet-5-5", c.model("AUTHOR"))
        assertNull(c.tenantCap("X"))
        AgentConfig custom = AgentConfig.defaults().with([models: [AUTHOR: "claude-haiku-5-5"]])
        assertEquals("claude-haiku-5-5", AgentConfig.withConfig(custom) { AgentConfig.current(null).model("AUTHOR") })
    }

    @Test
    @DisplayName("no data file seeds the agent config entities or an agent DarpanSetting row")
    void noDataFileSeedsAgentConfig() {
        List<Path> roots = [Paths.get("data"), Paths.get("../darpan/data")].collect { it.toAbsolutePath().normalize() }
        int scanned = 0
        List<String> hits = []
        roots.findAll { Files.isDirectory(it) }.each { Path root ->
            Files.walk(root).withCloseable { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".xml") && !it.toString().contains("/data/test/") }.each { Path f ->
                    scanned++
                    String text = f.toFile().getText("UTF-8")
                    if (text =~ /darpan\.agent\.Agent(Prompt|PromptVersion|RoleConfig|ModelPrice|TenantCap|Text)\b/ || text =~ /DarpanSetting[^>]*settingKey="darpan\.agent\./ ||
                            text =~ /settingKey="darpan\.agent\.[^"]*"[^>]*DarpanSetting/) hits << f.toString()
                }
            }
        }
        assertTrue(scanned > 0, "the scan must see the darpan data files")
        assertEquals([], hits)
    }

    // ------------------------------------------------------------------ texts (Task 5)

    /** Today's words, copied from the code at 0091851 (a122439 for the reasons): each {token} stands where an interpolation was. */
    private static final Map<String, String> TODAYS_WORDS = [
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
            "reply.NEED_MORE_EVIDENCE"    : "I need more evidence than one message allows; tell me which finding or record to look at.",
            "reply.OUT_OF_TIME"           : "I ran out of time for this message; ask again for the rest.",
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
            "notice.LAST_REQUEST"         : "This is your last request: no tool you call now will run. Return the evidence JSON now from what you have read, and say in it what you could not check."]

    @Test
    @DisplayName("every text default renders to today's string")
    void everyTextDefaultRendersToTodaysString() {
        AgentConfig c = AgentConfig.current(ec)
        TODAYS_WORDS.each { String id, String words -> assertEquals(words, AgentTextDefaults.TEXTS[id], id) }
        assertEquals("You have sent 20 messages to the agent in the last hour; try again later.", c.text("reply.RATE_LIMITED", [count: 20]))
        assertEquals("Your AI access ended on 2026-11-02.", c.text("reply.ACCESS_EXPIRED", [day: "2026-11-02"]))
        assertEquals("The agent is down for a fix on our side; Darpan staff have been alerted. Nothing was changed.", c.text("reply.AGENT_REFUSED"))
        // Every text is accounted for: today's words, the one new refusal reply, and the tools.
        Set<String> expected = new TreeSet<>(TODAYS_WORDS.keySet()); expected << "reply.AGENT_REFUSED"
        List<Map> tools = (List<Map>) new groovy.json.JsonSlurper().parse(AgentConfigTests.class.classLoader.getResourceAsStream("darpan/agent/tools.json"))
        assertEquals(15, tools.size())
        tools.each { Map t ->
            expected << ("tool." + t.name)
            assertEquals(t.description, c.text("tool." + t.name), "tool.${t.name}".toString())
        }
        assertEquals(expected, new TreeSet<>(AgentTextDefaults.TEXTS.keySet()))
        AgentTextDefaults.TEXTS.each { String id, String text -> assertNull(AgentTextDefaults.validTextError(id, text), "the default of ${id} is a valid text".toString()) }
    }

    @Test
    @DisplayName("every catalogue tool builds a definition with its name, strict flag and schema")
    void everyToolDefinitionBuilds() {
        List<String> names = darpan.agent.loop.AgentToolExecutor.TOOLS.keySet() as List
        def defs = darpan.agent.loop.AgentToolExecutor.toolDefinitions(names, AgentConfig.defaults())
        assertEquals(15, defs.size())
        assertEquals(names.toSorted(), defs*.name())
        defs.each { d ->
            Map t = darpan.agent.loop.AgentToolExecutor.TOOLS[d.name()]
            assertEquals(t.strict != false, d.strict().orElse(false), d.name())
            assertNotNull(d.inputSchema(), d.name())
            assertEquals(t.description, d.description().orElse(null), d.name())
        }
    }

    @Test
    @DisplayName("an invalid text row falls back to its default")
    void invalidTextRowFallsBack() {
        AgentConfigTestSupport.set(ec, [AgentText: [
                [textId: "reply.RATE_LIMITED", textValue: "Slow down."],                    // missing {count}
                [textId: "reply.DECLINE", textValue: "Try <b>again</b>."],                   // markup
                [textId: "reply.OUT_OF_TIME", textValue: "Out of time {count}."],            // a token the default has not
                [textId: "reply.FAILED", textValue: "   "],                                   // blank
                [textId: "reply.NOT_STORED", textValue: "Not stored { oops."],                // stray brace
                [textId: "tool.get_finding", textValue: "x" * 2001],                          // too long
                [textId: "reply.NOT_A_TEXT", textValue: "ignored"],                           // unknown id
                [textId: "reply.CLARIFY", textValue: "Which one do you mean?"]]])            // valid
        AgentConfig c = AgentConfig.current(ec)
        assertEquals("You have sent 5 messages to the agent in the last hour; try again later.", c.text("reply.RATE_LIMITED", [count: 5]))
        assertEquals(TODAYS_WORDS["reply.DECLINE"], c.text("reply.DECLINE"))
        assertEquals(TODAYS_WORDS["reply.OUT_OF_TIME"], c.text("reply.OUT_OF_TIME"))
        assertEquals(TODAYS_WORDS["reply.FAILED"], c.text("reply.FAILED"))
        assertEquals(TODAYS_WORDS["reply.NOT_STORED"], c.text("reply.NOT_STORED"))
        assertEquals(AgentTextDefaults.TEXTS["tool.get_finding"], c.text("tool.get_finding"))
        assertEquals("Which one do you mean?", c.text("reply.CLARIFY"), "a valid row beats the default")
        AgentConfigTestSupport.clear(ec)
        assertEquals(TODAYS_WORDS["reply.CLARIFY"], AgentConfig.current(ec).text("reply.CLARIFY"), "a reset brings the default back")
    }

    @Test
    @DisplayName("a text edit must keep the default's tokens and no markup, within the cap")
    void textRules() {
        assertNull(AgentTextDefaults.validTextError("reply.RATE_LIMITED", "{count} is the limit."))
        assertEquals("Unknown agent text nope.", AgentTextDefaults.validTextError("nope", "x"))
        assertEquals("The text is empty.", AgentTextDefaults.validTextError("reply.FAILED", " "))
        assertEquals("The text is longer than 1000 characters.", AgentTextDefaults.validTextError("reply.FAILED", "a" * 1001))
        assertNull(AgentTextDefaults.validTextError("tool.get_finding", "a" * 2000))
        assertEquals("The text is longer than 2000 characters.", AgentTextDefaults.validTextError("tool.get_finding", "a" * 2001))
        ["a <b>", "a > b", "use `code`", "a *bold*", "see [x](y)"].each {
            assertEquals("Plain text only: no markup.", AgentTextDefaults.validTextError("reply.FAILED", it), it)
        }
        assertEquals("The text must use exactly {why} — the same as the default.", AgentTextDefaults.validTextError("reply.STOPPED", "Stopped."))
        assertEquals("The text must use exactly {values}, {more} — the same as the default.", AgentTextDefaults.validTextError("reply.TYPED_VALUES", "{values}"))
        assertEquals("The text must use no {…} slots.", AgentTextDefaults.validTextError("reply.FAILED", "Failed {why}."))
        assertEquals("The text must use no {…} slots.", AgentTextDefaults.validTextError("reply.FAILED", "Failed }."))
        assertEquals(["count"] as Set, AgentTextDefaults.tokens("a {count} b {count}"))
    }

    @Test
    @DisplayName("tokens are replaced plainly: nothing is evaluated and a value is never scanned again")
    void plainSubstitution() {
        AgentConfig c = AgentConfig.defaults().with(texts: ["reply.RATE_LIMITED": "Sent {count}."])
        assertEquals('Sent ${1+1}.', c.text("reply.RATE_LIMITED", [count: '${1+1}']), "a Groovy expression in a value is only text")
        assertEquals('Sent $1 \\ {count}.', c.text("reply.RATE_LIMITED", [count: '$1 \\ {count}']), "regex and token characters in a value are only text")
        assertEquals("Sent {count}.", c.text("reply.RATE_LIMITED"), "a token with no value stays as written")
        assertThrows(IllegalArgumentException) { c.text("reply.NOPE") }
    }
}
