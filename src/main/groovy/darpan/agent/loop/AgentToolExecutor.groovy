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
    /** What the author reads when a write tool's service reported an error (its text may quote record data). */
    static final String TOOL_ERROR = "TOOL_ERROR"
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

    /**
     * The SDK tool definitions for these names, sorted by name, strict. No cache marker: tools render before the system
     * prompt, whose marker caches both (AgentRoleLoop.buildParams).
     */
    static List<Tool> toolDefinitions(List<String> names) {
        return names.toSorted().collect { String name ->
            Tool t = DEFINITIONS[name]
            if (t == null) throw new IllegalArgumentException("Unknown tool ${name}".toString())
            t
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

        Map params = tool.proposal != null ? proposalParams((Map) tool.proposal, input) : new LinkedHashMap(input)
        if (tool.conversationScoped) params.conversationId = conversationId
        String service = tool.service as String
        Map done = AgentCallMarker.run {
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
        if (spec.role == RoleSpec.AUTHOR) done.modelContent = authorView(toolName, done.isError as boolean, done.content as String)
        return done
    }

    /**
     * What the author's model reads of a write tool's result (spec 10.3: the author never reads raw tool text). The full
     * result stays in content, for the transcript; the model gets typed fields only: a proposal tool {proposalId, status,
     * statusCode (STATUS_CODES, never the free-text statusMessage), provenanceStatus, blockedCount, backtest {supported, reason, closedCount, openedCount, limits}} (no
     * finding ids, record keys or exception detail), raise_ticket {ticketId, duplicate}, and a fixed TOOL_ERROR for any
     * error the service reported.
     */
    static String authorView(String toolName, boolean isError, String content) {
        if (isError) return content == TOOL_FAILED ? TOOL_FAILED : TOOL_ERROR
        Object out
        try { out = new groovy.json.JsonSlurper().parseText(content ?: "") } catch (Exception ignored) { out = null }
        if (!(out instanceof Map)) return TOOL_ERROR
        Map o = (Map) out
        if (isProposalTool(toolName)) {
            Map bt = o.backtest instanceof Map ? (Map) o.backtest : null
            return JsonOutput.toJson([proposalId: token(o.proposalId), status: token(o.status), statusCode: statusCode(o),
                    provenanceStatus: token(o.provenanceStatus), blockedCount: o.blockedIds instanceof List ? ((List) o.blockedIds).size() : 0,
                    backtest: bt == null ? null : [supported: bt.supported == true, reason: token(bt.reason),
                            closedCount: bt.closed instanceof List ? ((List) bt.closed).size() : 0,
                            openedCount: bt.opened instanceof List ? ((List) bt.opened).size() : 0,
                            limits: bt.limits instanceof List ? ((List) bt.limits).collect { token(it) } : []]])
        }
        if (toolName == "raise_ticket") return JsonOutput.toJson([ticketId: token(o.ticketId), duplicate: o.duplicate == true])
        return TOOL_ERROR
    }

    /** The closed set the author reads in place of a proposal's free-text statusMessage. */
    static final List<String> STATUS_CODES = ["EDITOR_MESSAGE", "STALE", "UNSAFE_TREE", "NO_PRIMARY_ID", "NO_CHANGE", "EXISTING_FILTER_OPERATOR",
            "INVALID"].asImmutable()

    /**
     * A proposal's status as a code: from its status, plus, for an INVALID one, whether the pipeline marked it with its own
     * unsafe-tree constant (an identity check against Darpan's constant, not a reading of the text). PLANNED has none;
     * anything else is INVALID.
     */
    static String statusCode(Map out) {
        switch (out.status as String) {
            case "PLANNED": return null
            case "DRAFT": return "EDITOR_MESSAGE"
            case "STALE": return "STALE"
            case "INVALID":
                if (out.statusMessage == darpan.agent.pipeline.AuditChangeLevers.UNSAFE_TREE_MESSAGE) return "UNSAFE_TREE"
                // R44: the exclusion named no field and the run side has no single primary id to default to.
                if (out.statusMessage == darpan.agent.pipeline.AuditChangeLevers.NO_PRIMARY_ID_MESSAGE) return "NO_PRIMARY_ID"
                // R51: every value already excluded, or the field holds an include scope an exclusion may not change.
                if (out.statusMessage == darpan.agent.pipeline.AuditChangeLevers.NO_CHANGE_MESSAGE) return "NO_CHANGE"
                String message = out.statusMessage instanceof String ? (String) out.statusMessage : ""
                return message.startsWith(darpan.agent.pipeline.AuditChangeLevers.EXISTING_FILTER_OPERATOR_PREFIX) ? "EXISTING_FILTER_OPERATOR" : "INVALID"
            default: return "INVALID"
        }
    }

    private static String token(Object v) {
        String s = v?.toString()
        return s != null && s ==~ /[A-Za-z0-9_\-.]{1,64}/ ? s : null
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
            return parseCatalog(((List<Map>) ObjectMappers.jsonMapper().readValue(stream, List)).collect { Map t -> withSchema(t) })
        } finally {
            stream.close()
        }
    }

    /**
     * An entry's input_schema, inline or (input_schema_resource) read from a schema file, so a schema the orchestrator also
     * validates against exists once (darpan/agent/schemas/*.json).
     */
    private static Map withSchema(Map t) {
        String path = t.input_schema_resource as String
        if (!path) return t
        if (t.input_schema != null) throw new IllegalStateException("Agent tool ${t.name} has both input_schema and input_schema_resource".toString())
        Map out = new LinkedHashMap(t)
        out.remove("input_schema_resource")
        out.input_schema = schemaResource(path)
        return out
    }

    /** A JSON schema on the classpath, as a Map. */
    static Map schemaResource(String path) {
        InputStream stream = AgentToolExecutor.class.classLoader.getResourceAsStream(path)
        if (stream == null) throw new IllegalStateException("Agent schema ${path} is missing".toString())
        try {
            return (Map) ObjectMappers.jsonMapper().readValue(stream, Map)
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

    /**
     * name, description and input_schema as the SDK Tool; strict unless the catalog entry says "strict": false (raise_ticket,
     * so the author's strict tools stay within {@link #STRICT_OPTIONAL_LIMIT}; execute validates every input either way).
     * additionalProperties:false comes from the schema.
     */
    private static Tool definition(Map t) {
        String json = JsonOutput.toJson([name: t.name, description: t.description, input_schema: t.input_schema])
        return ObjectMappers.jsonMapper().readValue(json, Tool).toBuilder().strict(isStrict(t)).build()
    }

    private static boolean isStrict(Map t) { return t.strict != false }

    /**
     * The Messages API's published strict limits for one request, across all strict tool schemas and the JSON output schema
     * (structured-outputs docs, "Schema complexity limits"): strict tools; optional parameters (a property not in its
     * object's required list, nested ones included); parameters with union types (anyOf or a type array). Past any of them
     * every request of the role is a 400. The compiled grammar has a further, unpublished size limit ("Schema is too
     * complex"); flat schemas with every field required stay well inside it.
     */
    static final int STRICT_TOOL_LIMIT = 20
    static final int STRICT_OPTIONAL_LIMIT = 24
    static final int STRICT_UNION_LIMIT = 16

    /** [tools, optional, unions] of these tools' strict schemas plus the output schema (which is always enforced). */
    static Map<String, Integer> strictCounts(List<String> names, Map outputSchema) {
        List<String> strict = names.findAll { String n -> isStrict(toolOf(n)) }
        List<Map> schemas = strict.collect { String n -> (Map) toolOf(n).input_schema } + (outputSchema == null ? [] : [outputSchema])
        int optional = 0, unions = 0
        schemas.each { Map s -> Map c = schemaCounts(s); optional += c.optional as int; unions += c.unions as int }
        return [tools: strict.size(), optional: optional, unions: unions]
    }

    /** [optional, unions] in one schema. */
    static Map<String, Integer> schemaCounts(Map s) {
        int optional = 0, unions = 0
        if (s == null) return [optional: 0, unions: 0]
        if (s.type instanceof List || s.anyOf != null) unions++
        // s.get("properties"), never s.properties: on a Map, Groovy 4 resolves .properties to getProperties().
        Map props = (Map) s.get("properties")
        if (props) {
            List required = (List) (s.required ?: [])
            props.each { Object k, Object v ->
                if (!(k in required)) optional++
                Map c = schemaCounts((Map) v); optional += c.optional as int; unions += c.unions as int
            }
        }
        if (s.items instanceof Map) { Map c = schemaCounts((Map) s.items); optional += c.optional as int; unions += c.unions as int }
        ((List) (s.anyOf ?: [])).each { Object x -> Map c = schemaCounts((Map) x); optional += c.optional as int; unions += c.unions as int }
        return [optional: optional, unions: unions]
    }

    /** Throws when a role's strict schemas pass a published limit (RoleSpec calls it, so a grown schema fails at build time). */
    static void requireWithinStrictLimits(String role, List<String> names, Map outputSchema) {
        Map n = strictCounts(names, outputSchema)
        List<String> over = []
        if ((n.tools as int) > STRICT_TOOL_LIMIT) over << "${n.tools} strict tools (at most ${STRICT_TOOL_LIMIT})".toString()
        if ((n.optional as int) > STRICT_OPTIONAL_LIMIT) over << "${n.optional} optional parameters (at most ${STRICT_OPTIONAL_LIMIT})".toString()
        if ((n.unions as int) > STRICT_UNION_LIMIT) over << "${n.unions} union-type parameters (at most ${STRICT_UNION_LIMIT})".toString()
        if (over) throw new IllegalArgumentException("Role ${role}: its strict schemas hold ${over.join(', ')}; the API refuses the request. Make fields required, flatten, or send a tool without strict.".toString())
    }

    /** A proposal tool: one flat, strict tool per proposal kind, all mapping to create#AuditChangeProposal. */
    static boolean isProposalTool(String name) { return TOOLS[name]?.proposal != null }

    /**
     * The proposal service's input from a proposal tool's flat input: {kind, target, after, reasonText, evidence}, target and
     * after picked by the catalog entry's key lists. An empty string or list is "not given" (every field of a proposal tool is
     * required, to keep strict schemas small) and is left out at every level of target and after, so the core reads what
     * it read when the field was absent and refuses a missing required one as before.
     */
    static Map proposalParams(Map proposal, Map input) {
        Closure<Map> pick = { List keys -> (Map) given(keys.collectEntries { [(it): input.get(it)] }) ?: [:] }
        return [kind: proposal.kind, target: pick((List) proposal.target), after: pick((List) proposal.after),
                reasonText: input.reasonText, evidence: input.evidence]
    }

    /** v without empty strings, empty lists and null, in maps and lists at any depth; null when nothing is left. */
    private static Object given(Object v) {
        if (v == null) return null
        if (v instanceof CharSequence) return v.toString().isEmpty() ? null : v
        if (v instanceof Map) {
            Map out = new LinkedHashMap()
            ((Map) v).each { Object k, Object x -> Object g = given(x); if (g != null) out[k] = g }
            return out.isEmpty() ? null : out
        }
        if (v instanceof List) {
            List out = ((List) v).collect { given(it) }.findAll { it != null }
            return out.isEmpty() ? null : out
        }
        return v
    }

    private static Map toolOf(String name) {
        Map t = TOOLS[name]
        if (t == null) throw new IllegalArgumentException("Unknown tool ${name}".toString())
        return t
    }
}
