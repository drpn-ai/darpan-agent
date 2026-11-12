package darpan.agent.claude

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-244: the agent's client authenticates by AWS workload identity federation when no key is configured. */
class WorkloadIdentityFederationTests {
    static final String FAKE_JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0YXNrLXJvbGUifQ.SIGNATURE-NOT-FOR-LOGS"

    Path dir
    Map<String, String> envVars
    int mints

    @BeforeEach
    void setUp() {
        dir = Files.createTempDirectory("wif-test")
        mints = 0
        envVars = [
                ANTHROPIC_FEDERATION_RULE_ID : "fdrl_test",
                ANTHROPIC_ORGANIZATION_ID    : "00000000-0000-0000-0000-000000000000",
                ANTHROPIC_SERVICE_ACCOUNT_ID : "svac_test",
                ANTHROPIC_IDENTITY_TOKEN_FILE: dir.resolve("anthropic/identity.jwt").toString(),
        ]
        AgentClientFactory.reset()
        WorkloadIdentityToken.reset()
        System.clearProperty(AgentClientFactory.KEY_PROPERTY)
        AgentClientFactory.env = { String name -> envVars[name] }
        WorkloadIdentityToken.minter = { -> mints++; FAKE_JWT }
    }

    @AfterEach
    void tearDown() {
        AgentClientFactory.reset()
        WorkloadIdentityToken.reset()
        dir.toFile().deleteDir()
    }

    Path tokenFile() { return Path.of(envVars.ANTHROPIC_IDENTITY_TOKEN_FILE) }

    @Test
    void "a complete federation setup builds a client and writes an owner-only token file"() {
        assertNotNull(AgentClientFactory.client(null))
        assertEquals(1, mints)
        assertEquals(FAKE_JWT, Files.readString(tokenFile()))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile().parent)))
    }

    @Test
    void "a fresh token is reused and a stale one is minted again"() {
        def first = AgentClientFactory.client(null)
        assertSame(first, AgentClientFactory.client(null))
        assertEquals(1, mints, "a token younger than the refresh age must not be minted again")

        long stale = System.currentTimeMillis() - WorkloadIdentityToken.REFRESH_AFTER_MS - 60_000L
        Files.setLastModifiedTime(tokenFile(), FileTime.fromMillis(stale))
        assertSame(first, AgentClientFactory.client(null), "the client is kept; only the file it reads is refreshed")
        assertEquals(2, mints)
    }

    @Test
    void "an incomplete setup is refused by variable name only, and reads AGENT_UNAVAILABLE"() {
        envVars.remove("ANTHROPIC_SERVICE_ACCOUNT_ID")
        def e = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
        assertTrue(e.message.contains("ANTHROPIC_SERVICE_ACCOUNT_ID"), e.message)
        assertFalse(e.message.contains("fdrl_test"), "values must not be echoed: ${e.message}")
        assertEquals(0, mints)
        assertEquals("AGENT_UNAVAILABLE", darpan.agent.AgentAvailability.resolve("ON") { AgentClientFactory.client(null) })
    }

    @Test
    void "a blank ANTHROPIC_API_KEY beside federation is refused, since it would outrank it in the SDK"() {
        envVars.ANTHROPIC_API_KEY = "  "
        def e = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
        assertTrue(e.message.contains("ANTHROPIC_API_KEY"), e.message)
        assertEquals(0, mints)
    }

    @Test
    void "a configured key still wins and mints nothing"() {
        envVars.ANTHROPIC_API_KEY = "sk-ant-test-KEY"
        assertNotNull(AgentClientFactory.client(null))
        assertEquals(0, mints)
        assertFalse(Files.exists(tokenFile()))
    }

    @Test
    void "nothing configured is a null client, not an error"() {
        envVars.clear()
        assertNull(AgentClientFactory.client(null))
        assertEquals(0, mints)
    }

    @Test
    void "a failed or empty mint is refused without leaking a token"() {
        WorkloadIdentityToken.minter = { -> throw new RuntimeException("AccessDenied: not authorized to perform sts:GetWebIdentityToken") }
        def denied = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
        assertTrue(denied.message.contains("could not be minted"), denied.message)
        assertEquals("AGENT_UNAVAILABLE", darpan.agent.AgentAvailability.resolve("ON") { AgentClientFactory.client(null) })

        WorkloadIdentityToken.minter = { -> "  " }
        def empty = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
        assertTrue(empty.message.contains("no identity token"), empty.message)
        assertFalse(Files.exists(tokenFile()), "nothing is written when no token came back")

        WorkloadIdentityToken.minter = { -> FAKE_JWT }
        assertNotNull(AgentClientFactory.client(null))
        assertFalse(denied.toString().contains("SIGNATURE-NOT-FOR-LOGS"))
    }
}
