package darpan.agent

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-159 Task 9. Secrets and personal data never leave the person's text unmasked. Values are synthetic. */
class PasteScrubberTests {

    @Test
    void "an Anthropic key, an access key and a bearer token are masked"() {
        Map out = PasteScrubber.scrub("key sk-ant-api03-AbC_dEf-123 and AKIAABCDEFGHIJKLMNOP; header Authorization: Bearer eyJhbGciOi.x.y")
        assertFalse((out.text as String).contains("sk-ant-"), out.text as String)
        assertFalse((out.text as String).contains("AKIAABCDEFGHIJKLMNOP"))
        assertFalse((out.text as String).contains("eyJhbGciOi"))
        assertEquals(["API_KEY", "AWS_KEY", "BEARER"], out.masked)
    }

    @Test
    void "password, token, secret and api key assignments are masked"() {
        Map out = PasteScrubber.scrub("password: hunter2\ntoken=abc123 Secret : s3cr3t api_key=k-1 API-KEY:k-2")
        String t = out.text as String
        ["hunter2", "abc123", "s3cr3t", "k-1", "k-2"].each { assertFalse(t.contains(it), "${it} survived: ${t}") }
        assertTrue(t.startsWith("password: [masked secret]"), t)
        assertEquals(["SECRET"], out.masked)
    }

    @Test
    void "a secret at the end of a longer name, or quoted as JSON or config, is masked"() {
        [
                "access_token=abc111"                      : "abc111",
                "token_secret=abc222"                      : "abc222",
                "client_secret: abc333"                    : "abc333",
                '{"password": "hunter2"}'                  : "hunter2",
                '"apiKey":"shpat_x444"'                    : "shpat_x444",
                "{'api-key': 'k555', 'next': 1}"           : "k555",
                "Authorization: Basic dXNlcjpwYXNzNjY2"    : "dXNlcjpwYXNzNjY2",
        ].each { String input, String secret ->
            Map out = PasteScrubber.scrub(input)
            assertFalse((out.text as String).contains(secret), "${secret} survived in ${out.text}")
            assertFalse((out.masked as List).isEmpty(), input)
        }
        assertEquals('{"password": "[masked secret]"}', PasteScrubber.scrub('{"password": "hunter2"}').text)
        assertEquals(["BASIC"], PasteScrubber.scrub("Authorization: Basic dXNlcjpwYXNz").masked)
        assertEquals("A basic check on the tokens", PasteScrubber.scrub("A basic check on the tokens").text)
    }

    @Test
    void "a quoted secret is masked through its closing quote and the JSON around it survives"() {
        [
                ['{"client_secret": "ab,c}d\\"e", "next": 1}', '{"client_secret": "[masked secret]", "next": 1}', ["ab", "c}d", "\\\"e"]],
                ['{"password": "x,y"}', '{"password": "[masked secret]"}', ["x,y", ",y\""]],
                ["{'token': 'p}q', 'n': 2}", "{'token': '[masked secret]', 'n': 2}", ["p}q", "p", "}q'"]],
                ['{"access_token":"tok-77.88","scope":"read"}', '{"access_token":"[masked secret]","scope":"read"}', ["tok-77", "77.88"]],
        ].each { List c ->
            Map out = PasteScrubber.scrub(c[0] as String)
            assertEquals(c[1], out.text, "for ${c[0]}")
            ((List<String>) c[2]).each { String fragment -> assertFalse((out.text as String).contains(fragment), "${fragment} survived in ${out.text}") }
            assertEquals(["SECRET"], out.masked)
        }
    }

    @Test
    void "an unquoted secret stops before trailing punctuation"() {
        assertEquals("password=[masked secret], next", PasteScrubber.scrub("password=hunter9, next").text)
        assertEquals("{token: [masked secret]}", PasteScrubber.scrub("{token: abc99}").text)
        assertEquals("(secret=[masked secret]); done", PasteScrubber.scrub("(secret=zz11); done").text)
        assertEquals("password=[masked secret] then", PasteScrubber.scrub("password=ab,cd then").text, "a comma inside the value is masked with it")
    }

    @Test
    void "a Basic or Bearer credential keeps the punctuation after it"() {
        Map basic = PasteScrubber.scrub("Authorization: Basic dXNlcjpwYXNz, next")
        assertEquals("Authorization: Basic [masked credentials], next", basic.text)
        Map json = PasteScrubber.scrub('{"Authorization":"Bearer eyJ0.eyJ1.sig","x":1}')
        assertEquals('{"Authorization":"Bearer [masked token]","x":1}', json.text)
        Map key = PasteScrubber.scrub('{"k":"sk-ant-api03-zz9","x":1}')
        assertEquals('{"k":"[masked API key]","x":1}', key.text)
    }

    @Test
    void "a key inside an assignment is masked once, as a key"() {
        Map out = PasteScrubber.scrub("api_key=sk-ant-api03-zzz")
        assertEquals("api_key=[masked API key]", out.text)
        assertEquals(["API_KEY"], out.masked)
    }

    @Test
    void "email and phone shapes are masked and ids, dates and amounts are kept"() {
        Map out = PasteScrubber.scrub("Customer jo.doe+x@example.org called +1 (555) 010-2030 and 555-010-2040 about M1088731 on 2026-10-30, refund 12.50, #ORD10442")
        String t = out.text as String
        assertFalse(t.contains("example.org"), t)
        assertFalse(t.contains("010-2030") || t.contains("010-2040"), t)
        ["M1088731", "2026-10-30", "12.50", "#ORD10442"].each { assertTrue(t.contains(it), "${it} was lost: ${t}") }
        assertEquals(["EMAIL", "PHONE"], out.masked)
    }

    @Test
    void "a zero-width or fullwidth variant of a key is still masked"() {
        Map out = PasteScrubber.scrub("sk-​ant-api03-hidden and ｓｋ-ant-api03-wide")
        assertFalse((out.text as String).contains("hidden"), out.text as String)
        assertFalse((out.text as String).contains("wide"), out.text as String)
        assertEquals(["API_KEY"], out.masked)
    }

    @Test
    void "plain text is unchanged and nothing is reported"() {
        Map out = PasteScrubber.scrub("Why is F-20761 open? The return was received.")
        assertEquals("Why is F-20761 open? The return was received.", out.text)
        assertEquals([], out.masked)
    }
}
