package darpan.agent.claude

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

import static org.junit.jupiter.api.Assertions.*

class AgentClientFactoryTests {
    static final String KEY_PROP = "darpan.agent.anthropicApiKey"
    static final String URL_PROP = "darpan.agent.anthropicBaseUrl"
    static final String SECRET = "sk-ant-test-SECRET123"

    @BeforeEach @AfterEach
    void clean() {
        System.clearProperty(KEY_PROP); System.clearProperty(URL_PROP); AgentClientFactory.reset()
    }

    @Test
    void "no key gives a null client and never throws"() {
        assumeNoEnvKey()
        assertNull(AgentClientFactory.client(null))
    }

    @Test
    void "a non-anthropic base URL is refused"() {
        System.setProperty(KEY_PROP, SECRET)
        System.setProperty(URL_PROP, "https://evil.example.com")
        def e = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
        assertFalse(e.message.contains("SECRET123"))
    }

    @Test
    void "a key builds a client that is cached per key and url"() {
        System.setProperty(KEY_PROP, SECRET)
        def a = AgentClientFactory.client(null)
        assertNotNull(a)
        assertSame(a, AgentClientFactory.client(null))
        System.setProperty(KEY_PROP, SECRET + "2")
        assertNotSame(a, AgentClientFactory.client(null))
    }

    @Test
    void "the key never appears in toString or logs"() {
        System.setProperty(KEY_PROP, SECRET)
        List<String> logged = []
        def appender = new AbstractAppender("keyLeakCapture", null, null, true, Property.EMPTY_ARRAY) {
            @Override void append(LogEvent e) { logged << (e.message.formattedMessage + " " + (e.thrown?.toString() ?: "")) }
        }
        appender.start()
        def ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false)
        ctx.configuration.rootLogger.addAppender(appender, null, null)
        ctx.updateLoggers()
        try {
            def client = AgentClientFactory.client(null)
            assertNotNull(client)
            String all = [AgentClientFactory.name, new AnthropicModelGateway(client).toString(), client.toString()].join("|")
            assertFalse(all.contains("SECRET123"), "toString leaked the key")
            // a bad URL must not echo the key either
            System.setProperty(URL_PROP, "https://evil.example.com")
            def ex = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
            assertFalse(ex.toString().contains("SECRET123"))
            logged.each { assertFalse(it.contains("SECRET123"), "log leaked the key: ${it}") }
        } finally { ctx.configuration.rootLogger.removeAppender("keyLeakCapture"); ctx.updateLoggers() }
    }

    @Test
    void "a refused base URL reads AGENT_UNAVAILABLE through the factory, and never leaks the key"() {
        System.setProperty(KEY_PROP, SECRET)
        System.setProperty(URL_PROP, "https://evil.example.com")
        def e = assertThrows(IllegalStateException) { AgentClientFactory.client(null) }
        assertTrue(e.message.contains("refused"))
        assertFalse(e.toString().contains("SECRET123"))
        // state() path: the refusal becomes AGENT_UNAVAILABLE, direct client() keeps throwing
        assertEquals("AGENT_UNAVAILABLE", darpan.agent.AgentAvailability.resolve("ON") { AgentClientFactory.client(null) })
        assertEquals("AGENT_OFF", darpan.agent.AgentAvailability.resolve("AGENT_OFF") { throw new IllegalStateException("x") })
    }

    @Test
    void "a replaced client is closed"() {
        System.setProperty(KEY_PROP, SECRET)
        def a = AgentClientFactory.client(null)
        System.setProperty(KEY_PROP, SECRET + "2")
        def b = AgentClientFactory.client(null)
        assertNotSame(a, b)
        // closing twice is harmless; the replaced client was already closed by the factory without error
        a.close()
    }

    @Test
    void "availability is AGENT_OFF when core says off and AGENT_UNAVAILABLE needs a missing client"() {
        // core switch is off without darpan.agent.enabled; the wrapper must pass that through untouched
        assertEquals("AGENT_OFF", darpan.agent.AgentAvailability.stateFor(darpan.agent.AgentSwitch.AGENT_OFF, null))
        assertEquals("AGENT_UNAVAILABLE", darpan.agent.AgentAvailability.stateFor(darpan.agent.AgentSwitch.ON, null))
        assertEquals("ON", darpan.agent.AgentAvailability.stateFor(darpan.agent.AgentSwitch.ON, new Object()))
    }

    private static void assumeNoEnvKey() {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getenv("ANTHROPIC_API_KEY"), "env key set")
    }
}
