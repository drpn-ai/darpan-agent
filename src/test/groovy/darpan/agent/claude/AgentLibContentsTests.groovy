package darpan.agent.claude

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** Moqui puts every component's lib/*.jar on one classloader and keeps the first duplicate class. */
class AgentLibContentsTests {
    @Test
    void "lib holds the SDK and no framework, darpan or jackson core jars"() {
        File lib = new File("lib")
        assertTrue(lib.isDirectory(), "run after :jar; lib dir missing at ${lib.absolutePath}")
        List<String> names = lib.listFiles().collect { it.name }
        // The component's own jar, named from component.xml so a version bump cannot turn this red.
        String ownJar = "darpan-agent-" + new XmlSlurper().parse(new File("component.xml")).@version + ".jar"
        assertTrue(names.contains(ownJar), "own jar ${ownJar} missing: ${names}")
        assertTrue(names.any { it == "anthropic-java-3.2.0.jar" }, "SDK jar missing: ${names}")
        // DAR-BE-244: STS for workload identity federation, on the JDK URLConnection client only.
        ["sts-2.58.0.jar", "url-connection-client-2.58.0.jar"].each { String jar ->
            assertTrue(names.contains(jar), "${jar} missing: ${names}")
        }
        ["apache5-client-", "apache-client-", "netty-nio-client-", "httpclient5-", "reactive-streams-"].each { String prefix ->
            assertFalse(names.any { it.startsWith(prefix) }, "${prefix}* must not be in lib (darpan ships reactive-streams): ${names}")
        }
        ["jackson-core-", "jackson-databind-", "jackson-annotations-", "darpan-", "moqui-framework-"].each { String prefix ->
            assertFalse(names.any { it.startsWith(prefix) && it != ownJar }, "${prefix}* must not be in lib: ${names}")
        }
    }
}
