package darpan.agent.loop

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.Tool
import darpan.agent.AgentCallMarker
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-159. Runs the model's tool calls as the signed-in user, each through one Darpan facade service (R3). The tool
 * catalog is data (darpan/agent/tools.json): name, service, description and the strict input schema the model sees.
 * Before every call the active tenant must still be the pinned one, the tool must be in the role's list, and the input
 * must match its schema; every call runs inside {@link AgentCallMarker#run} (R28) and its own transaction. No tool maps to
 * a service that approves, rejects, reverts or plans a change.
 */
class AgentToolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(AgentToolExecutor.class)

    static final String CATALOG = "darpan/agent/tools.json"
    static final String NOT_PERMITTED = "TOOL_NOT_PERMITTED"
    static final String INVALID_INPUT = "INVALID_INPUT"
    static final String TOOL_FAILED = "TOOL_FAILED"
    /** A tool result longer than this is cut, so one large read cannot fill the context. */
    static final int MAX_RESULT_CHARS = 60_000
    /**
     * The only service verbs a tool may map to (compared case-insensitively). Anything else, apply, reject, revert, plan,
     * update, delete, does not load, so a catalog edit cannot hand the agent a person-only service.
     */
    static final List<String> ALLOWED_VERBS = ["get", "find", "inspect", "search", "create"].asImmutable()
    private static final java.util.regex.Pattern VERB = java.util.regex.Pattern.compile(/\.([A-Za-z]+)#[A-Za-z0-9_]+$/)

    /** name -> [name, service, control, conversationScoped, description, input_schema], in catalog order. */
    static final Map<String, Map> TOOLS = loadCatalog()
    private static final Map<String, Tool> DEFINITIONS = TOOLS.collectEntries { String name, Map t -> [(name): definition(t)] }.asImmutable()

    final RoleSpec spec
    final String conversationId

    AgentToolExecutor(RoleSpec spec, String conversationId) {
        this.spec = spec
        this.conversationId = conversationId
    }

    static String serviceOf(String toolName) { return TOOLS[toolName]?.service as String }

    /** The SDK tool definitions for these names, sorted by name, strict, with the cache breakpoint on the last one. */
    static List<Tool> toolDefinitions(List<String> names) {
        List<String> sorted = names.toSorted()
        return sorted.withIndex().collect { String name, int i ->
            Tool t = DEFINITIONS[name]
            if (t == null) throw new IllegalArgumentException("Unknown tool ${name}".toString())
            i == sorted.size() - 1 ? t.toBuilder().cacheControl(com.anthropic.models.messages.CacheControlEphemeral.builder().build()).build() : t
        }
    }

    /**
     * One tool call. Returns [isError, content] and, for a control tool (ask_human, request_more_evidence), [control:
     * QUESTION|MORE_EVIDENCE] with the input left for the caller. Throws {@link TenantChangedException} before anything runs
     * when the active tenant is not the pinned one.
     */
    Map execute(def ec, String pinnedTenantId, String toolName, Map input) {
        String active = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!pinnedTenantId || active != pinnedTenantId) throw new TenantChangedException()
        if (!(toolName in spec.tools)) return [isError: true, content: NOT_PERMITTED]
        Map tool = TOOLS[toolName]
        List<String> problems = validate((Map) tool.input_schema, input)
        if (problems) return [isError: true, content: "${INVALID_INPUT}: ${problems.join('; ')}".toString()]
        if (tool.control) return [isError: false, control: tool.control as String, content: null]

        Map params = new LinkedHashMap(input)
        if (tool.conversationScoped) params.conversationId = conversationId
        String service = tool.service as String
        return AgentCallMarker.run {
            // Errors already on ec.message are set aside for the call and put back after it, so the call is not refused
            // because of them, and only the errors this call added are reported (and then cleared).
            ec.message.pushErrors()
            try {
                Map out
                try {
                    out = invoke(ec, service, params)
                } catch (Exception e) {
                    // A failing tool is the model's to read and work around, not the turn's end; the stack goes to the log only.
                    logger.warn("Agent tool ${toolName} (${service}) failed".toString(), e)
                    return [isError: true, content: TOOL_FAILED]
                }
                if (ec.message.hasError()) return [isError: true, content: cap("ERROR: " + ec.message.getErrorsString())]
                return [isError: false, content: cap(JsonOutput.toJson(out ?: [:]))]
            } finally {
                ec.message.clearErrors()
                ec.message.popErrors()
            }
        }
    }

    /** The service call, as the signed-in user, in its own transaction so a failed tool cannot roll back the turn's writes. */
    protected Map invoke(def ec, String serviceName, Map params) {
        return (Map) ec.service.sync().name(serviceName).parameters(params).requireNewTransaction(true).call()
    }

    private static String cap(String s) {
        return s.length() <= MAX_RESULT_CHARS ? s : s.substring(0, MAX_RESULT_CHARS) + " [cut at ${MAX_RESULT_CHARS} characters]"
    }

    // ---------------------------------------------------------------- input schema check

    /**
     * The model's input against the tool's schema (the subset the catalog uses: object, array, string, integer, number,
     * boolean, properties, required, additionalProperties false, enum, items). Empty when it matches. strict:true makes
     * the API guarantee this; it is checked again so nothing off-schema ever reaches a service.
     */
    static List<String> validate(Map schema, Object value) {
        List<String> problems = []
        check(schema, value, "input", problems)
        return problems
    }

    private static void check(Map s, Object v, String path, List<String> out) {
        if (v == null) { out << "${path} is null".toString(); return }
        switch (s.type) {
            case "object":
                if (!(v instanceof Map)) { out << "${path} must be an object".toString(); return }
                // s.get("properties"), never s.properties: on a Map, Groovy 4 resolves .properties to getProperties().
                Map props = (Map) (s.get("properties") ?: [:])
                ((List) (s.required ?: [])).each { Object k -> if (!((Map) v).containsKey(k)) out << "${path}.${k} is required".toString() }
                ((Map) v).each { Object k, Object val ->
                    if (props.containsKey(k)) check((Map) props[k], val, "${path}.${k}".toString(), out)
                    else if (s.additionalProperties == false) out << "${path}.${k} is not allowed".toString()
                }
                break
            case "array":
                if (!(v instanceof List)) { out << "${path} must be an array".toString(); return }
                ((List) v).eachWithIndex { Object item, int i -> if (s.items) check((Map) s.items, item, "${path}[${i}]".toString(), out) }
                break
            case "string":
                if (!(v instanceof CharSequence)) out << "${path} must be a string".toString()
                break
            case "integer":
                if (!(v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof BigInteger)) out << "${path} must be an integer".toString()
                break
            case "number":
                if (!(v instanceof Number)) out << "${path} must be a number".toString()
                break
            case "boolean":
                if (!(v instanceof Boolean)) out << "${path} must be true or false".toString()
                break
            default:
                out << "${path} has an unsupported schema type ${s.type}".toString()
        }
        if (s.enum != null && !(v in (List) s.enum)) out << "${path} must be one of ${((List) s.enum).join(', ')}".toString()
    }

    // ---------------------------------------------------------------- catalog

    private static Map<String, Map> loadCatalog() {
        InputStream stream = AgentToolExecutor.class.classLoader.getResourceAsStream(CATALOG)
        if (stream == null) throw new IllegalStateException("Agent tool catalog ${CATALOG} is missing".toString())
        try {
            return parseCatalog((List<Map>) ObjectMappers.jsonMapper().readValue(stream, List))
        } finally {
            stream.close()
        }
    }

    /** Checks catalog entries: a name, and either a control or a service whose verb is in {@link #ALLOWED_VERBS}. */
    static Map<String, Map> parseCatalog(List<Map> entries) {
        Map<String, Map> out = new LinkedHashMap<>()
        entries.each { Map t ->
            String name = t.name as String
            String service = t.service as String
            if (!name || (!service && !t.control) || (service && t.control))
                throw new IllegalStateException("Agent tool entry needs a name and exactly one of service or control: ${t}".toString())
            if (service) {
                def m = VERB.matcher(service)
                if (!m.find() || !(m.group(1).toLowerCase(Locale.ROOT) in ALLOWED_VERBS))
                    throw new IllegalStateException("Agent tool ${name} maps to ${service}; a tool may only map to a ${ALLOWED_VERBS.join('/')} service".toString())
            }
            if (out.containsKey(name)) throw new IllegalStateException("Agent tool ${name} is listed twice".toString())
            out[name] = t.asImmutable()
        }
        return out.asImmutable()
    }

    /** name, description and input_schema as the SDK Tool, strict:true; additionalProperties:false comes from the schema. */
    private static Tool definition(Map t) {
        String json = JsonOutput.toJson([name: t.name, description: t.description, input_schema: t.input_schema])
        return ObjectMappers.jsonMapper().readValue(json, Tool).toBuilder().strict(true).build()
    }
}
