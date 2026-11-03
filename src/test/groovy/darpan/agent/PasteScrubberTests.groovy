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
        assertEquals("(secret=[masked secret] done", PasteScrubber.scrub("(secret=zz11); done").text, "; and ) may belong to the secret")
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
    void "a 20000-character quoted value is masked without an exception"() {
        String value = "q" * 20_000
        Map out = PasteScrubber.scrub('{"access_token": "' + value + '", "n": 1}')
        assertEquals('{"access_token": "[masked secret]", "n": 1}', out.text)
    }

    @Test
    void "a 20000-character unquoted run is masked quickly"() {
        long t0 = System.nanoTime()
        Map out = PasteScrubber.scrub("password=" + ("u7" * 10_000) + " tail")
        assertEquals("password=[masked secret] tail", out.text)
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "took ${(System.nanoTime() - t0) / 1_000_000} ms")
        long t1 = System.nanoTime()
        PasteScrubber.scrub("x" * 20_000)
        assertTrue(System.nanoTime() - t1 < 2_000_000_000L, "a plain long run took ${(System.nanoTime() - t1) / 1_000_000} ms")
    }

    @Test
    void "a quoted value ending in a backslash, or escaping a newline, is masked to its end"() {
        assertEquals('password: "[masked secret]', PasteScrubber.scrub('password: "abc\\').text)
        Map out = PasteScrubber.scrub('password: "abc\\\ndef" tail')
        assertEquals('password: "[masked secret]" tail', out.text)
        assertFalse((out.text as String).contains("abc") || (out.text as String).contains("def"), out.text as String)
    }

    @Test
    void "an unquoted value keeps its quotes, brackets and semicolons inside the mask"() {
        Map q = PasteScrubber.scrub('token=ab"cd next')
        assertEquals("token=[masked secret] next", q.text)
        assertEquals("secret=[masked secret]", PasteScrubber.scrub("secret=abc)").text)
        assertEquals("secret=[masked secret]", PasteScrubber.scrub("secret=abc]x").text)
        assertEquals("secret=[masked secret]] next", PasteScrubber.scrub("secret=abc] next").text, "a trailing ] before space is JSON structure")
    }

    @Test
    void "a quoted value with a comma and a brace inside is masked and the next key survives"() {
        Map out = PasteScrubber.scrub('{"token":"x,y}z", "next":1}')
        assertEquals('{"token":"[masked secret]", "next":1}', out.text)
    }

    @Test
    void "camelCase secret keys are masked"() {
        assertEquals("accessToken=[masked secret]", PasteScrubber.scrub("accessToken=zzz").text)
        assertEquals("clientSecret: [masked secret]", PasteScrubber.scrub("clientSecret: zzz").text)
        assertEquals('apiKey="[masked secret]"', PasteScrubber.scrub('apiKey="zzz"').text)
        assertEquals("dbPassword=[masked secret] and privateKey: [masked secret]", PasteScrubber.scrub("dbPassword=p1 and privateKey: k2").text)
    }

    @Test
    void "a bearer token is masked through any character up to whitespace"() {
        assertEquals("Bearer [masked token],", PasteScrubber.scrub("Bearer ab%cd!ef,").text)
        assertEquals("Bearer [masked token]", PasteScrubber.scrub("Bearer ab%cd!ef").text)
    }

    @Test
    void "a key with no value masks nothing more and does not throw"() {
        assertEquals("password:", PasteScrubber.scrub("password:").text)
        assertEquals('token: ""', PasteScrubber.scrub('token: ""').text)
        assertEquals("password=   ", PasteScrubber.scrub("password=   ").text)
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
    void "a secret in JSON escaped inside a string is masked"() {
        Map out = PasteScrubber.scrub('{\\"password\\":\\"hunter2\\"}')
        assertFalse((out.text as String).contains("hunter2"), out.text as String)
        assertEquals('{\\"password\\":\\"[masked secret]\\"}', out.text)
        Map spaced = PasteScrubber.scrub('"{\\"token\\": \\"abc def\\"}"')
        String t = spaced.text as String
        assertFalse(t.contains("abc") || t.contains("def"), t)
        assertEquals('"{\\"token\\": \\"[masked secret]\\"}"', t)
        assertEquals(["SECRET"], spaced.masked)
    }

    @Test
    void "a secret in double-escaped JSON is masked and the structure survives"() {
        Map out = PasteScrubber.scrub('{\\\\\\"client_secret\\\\\\":\\\\\\"s3 cr3t\\\\\\",\\\\\\"n\\\\\\":1}')
        String t = out.text as String
        assertFalse(t.contains("s3") || t.contains("cr3t"), t)
        assertEquals('{\\\\\\"client_secret\\\\\\":\\\\\\"[masked secret]\\\\\\",\\\\\\"n\\\\\\":1}', t)
    }

    @Test
    void "a curly-quoted secret is masked through its closing quote"() {
        Map out = PasteScrubber.scrub("password: “abc def” and more")
        String t = out.text as String
        assertFalse(t.contains("abc") || t.contains("def"), t)
        assertEquals("password: “[masked secret]” and more", t)
        assertEquals("token = ‘[masked secret]’", PasteScrubber.scrub("token = ‘x y’").text)
        assertEquals("secret: «[masked secret]»", PasteScrubber.scrub("secret: «x y»").text)
        assertEquals("“password”: “[masked secret]", PasteScrubber.scrub("“password”: “no close here").text)
    }

    @Test
    void "passcode and passphrase keys are masked"() {
        assertEquals("passcode=[masked secret]", PasteScrubber.scrub("passcode=1234").text)
        assertEquals("passphrase: [masked secret] then", PasteScrubber.scrub("passphrase: open-sesame then").text)
    }

    @Test
    void "a value that only starts like a marker is masked"() {
        Map out = PasteScrubber.scrub("password=[maskedhunter2")
        assertFalse((out.text as String).contains("hunter2"), out.text as String)
        assertEquals("password=[masked secret]", out.text)
        assertEquals("token=[masked secret]", PasteScrubber.scrub("token=[masked secret]xyz").text)
        assertEquals('{"token": "[masked secret]"}', PasteScrubber.scrub('{"token": "[masked secret] xyz"}').text)
        assertEquals("Authorization: [masked secret]", PasteScrubber.scrub("Authorization: basic dXNlcjpwYXNz").text)
    }

    @Test
    void "scrubbing an already scrubbed string returns it unchanged"() {
        String input = "password: hunter2, {\"token\":\"x,y}z\"} Authorization: Bearer abc.def and Basic dXNlcjpw " +
                "api_key=sk-ant-api03-zzz key AKIAABCDEFGHIJKLMNOP {\\\"password\\\":\\\"p q\\\"} passcode=1234 " +
                "password: “abc def” (secret=zz11); done secret=abc] next {token: abc99} " +
                "{\"Authorization\":\"Bearer eyJ0.x\"} jo@example.org +1 (555) 010-2030"
        Map once = PasteScrubber.scrub(input)
        Map twice = PasteScrubber.scrub(once.text as String)
        assertEquals(once.text, twice.text)
        assertEquals([], twice.masked)
        ["hunter2", "x,y}z", "abc.def", "dXNlcjpw", "zzz", "AKIAABCDEFGHIJKLMNOP", "p q", "1234", "abc def", "zz11", "abc99",
         "eyJ0", "example.org", "010-2030"].each { assertFalse((once.text as String).contains(it), "${it} survived: ${once.text}") }
    }

    @Test
    void "a 20000-character escaped-quote value is masked quickly"() {
        long t0 = System.nanoTime()
        Map out = PasteScrubber.scrub('{\\"access_token\\": \\"' + ("q" * 20_000) + '\\", \\"n\\": 1}')
        assertEquals('{\\"access_token\\": \\"[masked secret]\\", \\"n\\": 1}', out.text)
        Map open = PasteScrubber.scrub('password: \\"' + ('a\\\\\\"' * 5_000))
        assertEquals('password: \\"[masked secret]', open.text)
        Map slashes = PasteScrubber.scrub('password: \\"' + ('\\' * 20_000))
        assertEquals('password: \\"[masked secret]', slashes.text)
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "took ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    @Test
    void "plain text is unchanged and nothing is reported"() {
        Map out = PasteScrubber.scrub("Why is F-20761 open? The return was received.")
        assertEquals("Why is F-20761 open? The return was received.", out.text)
        assertEquals([], out.masked)
    }
}
