package darpan.agent.claude

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** Moqui puts every component's lib/*.jar on one classloader and keeps the first duplicate class. */
class AgentLibContentsTests {
    @Test
    void "lib holds the SDK and no framework, darpan or jackson core jars"() {
        File lib = new File("lib")
        assertTrue(lib.isDirectory(), "run after :jar; lib dir missing at ${lib.absolutePath}")
        List<String> names = lib.listFiles().collect { it.name }
        assertTrue(names.any { it == "anthropic-java-3.2.0.jar" }, "SDK jar missing: ${names}")
        ["jackson-core-", "jackson-databind-", "jackson-annotations-", "darpan-", "moqui-framework-"].each { String prefix ->
            assertFalse(names.any { it.startsWith(prefix) && it != "darpan-agent-0.1.0.jar" }, "${prefix}* must not be in lib: ${names}")
        }
    }
}
