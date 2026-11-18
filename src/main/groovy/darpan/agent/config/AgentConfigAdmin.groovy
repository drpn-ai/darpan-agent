package darpan.agent.config

import darpan.admin.AdminAuditSupport
import darpan.config.DarpanSettings
import darpan.config.SettingDefinition
import darpan.facade.common.TenantScopedFinder
import groovy.transform.CompileDynamic

import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * DAR-BE-307: the work behind admin.AgentConfigAdminServices (read, edit and reset the agent's prompts, role models, prices, texts and
 * per-tenant daily caps). The caller has checked super admin. A refusal is {error: "..."} and has saved nothing: every rule is checked
 * before the first write. A write runs in the service's transaction with its AdminAuditLog row, then expires the AgentConfig snapshot
 * once that transaction commits (the next turn on this node; other nodes within 30 s). No network call here: a model change is
 * caught by the preflight on the next turn. Scalar settings are not here; they are DarpanSettings rows (admin.SettingAdminServices).
 */
@CompileDynamic
class AgentConfigAdmin {
    static final int PROMPT_MAX_CHARS = 100_000
    static final int VERSIONS_SHOWN = 10
    /** One side of a text edit in the audit row, so before and after together stay inside the detailText column. */
    private static final int AUDIT_TEXT_SIDE = 1500
    private static final String WHY = "agent config admin: instance-wide agent config"
    private static final String SVC = "admin.AgentConfigAdminServices."
    private static final List<String> PRICE_FIELDS = ["inputUsdPerMTok", "outputUsdPerMTok", "cacheReadUsdPerMTok", "cacheWriteUsdPerMTok"]
    private static final BigDecimal MAX_PRICE = 1000G
    static final String NONE = "none"

    // ------------------------------------------------------------------ reads

    static Map getAgentConfig(def ec) {
        AgentConfig cfg = AgentConfig.load(ec)
        List<String> roleIds = AgentConfigDefaults.ROLE_MODELS.keySet().toList()
        Map<String, Map> promptRows = rowsById(ec, "darpan.agent.AgentPrompt", "promptId")
        Map<String, Map> priceRows = rowsById(ec, "darpan.agent.AgentModelPrice", "modelId")
        Map<String, Map> textRows = rowsById(ec, "darpan.agent.AgentText", "textId")

        List<Map> prompts = AgentConfigDefaults.PROMPT_PATHS.keySet().collect { String id -> promptSummary(id, cfg, promptRows.get(id)) }
        List<Map> roles = roleIds.collect { String r -> roleMap(cfg, r) }
        List<String> modelIds = (AgentConfigDefaults.PRICES.keySet() + priceRows.keySet()).toList().unique()
        List<Map> prices = modelIds.collect { String m -> priceMap(cfg, m, priceRows.containsKey(m), roleIds) }
        Map cap = DarpanSettings.describe(AgentLimits.DAILY_USD_CAP_PER_TENANT)
        List<Map> tenantCaps = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentTenantCap", WHY).orderBy("tenantUserGroupId")
                .useCache(false).list().collect { [tenantUserGroupId: it.tenantUserGroupId, dailyUsdCap: it.dailyUsdCap] }
        List<Map> texts = AgentTextDefaults.TEXTS.keySet().collect { String id -> textMap(id, cfg, textRows.get(id)) }
        return [config: [prompts: prompts, roles: roles, prices: prices,
                         globalDailyUsdCap: [effectiveValue: cap.effectiveValue, source: cap.source, min: cap.min, max: cap.max],
                         tenantCaps: tenantCaps, texts: texts]]
    }

    static Map getAgentPrompt(def ec, String promptId) {
        if (!AgentConfigDefaults.PROMPT_PATHS.containsKey(promptId)) return [error: unknown("prompt", promptId)]
        return [prompt: promptDetail(ec, promptId)]
    }

    private static Map promptDetail(def ec, String promptId) {
        AgentConfig cfg = AgentConfig.load(ec)
        Map row = row(ec, "darpan.agent.AgentPrompt", [promptId: promptId])
        Map base = promptSummary(promptId, cfg, row)
        List<Map> versions = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentPromptVersion", WHY).condition("promptId", promptId)
                .useCache(false).list().collect { it.getMap() as Map }
        // Newest first: the later change, and for one instant the later id.
        versions = versions.sort(false) { Map v -> v.changedDate.time * 1_000_000L + sequence(v.promptVersionId) % 1_000_000L }.reverse()
        return [promptId: promptId, text: cfg.prompt(promptId), defaultText: defaultPrompt(promptId), edited: base.edited,
                defaultChangedSinceEdit: base.defaultChangedSinceEdit,
                versions: versions.take(VERSIONS_SHOWN).collect { Map v ->
                    [promptVersionId: v.promptVersionId, text: v.promptText, changedByUserId: v.changedByUserId, changedDate: v.changedDate,
                     reset: v.promptText == null]
                }]
    }

    private static long sequence(Object id) {
        String digits = id?.toString()?.replaceAll(/\D/, "")
        return digits ? Long.parseLong(digits.length() > 18 ? digits.substring(digits.length() - 18) : digits) : 0L
    }

    private static Map promptSummary(String id, AgentConfig cfg, Map row) {
        boolean edited = row != null && row.promptText?.toString()?.trim() as boolean
        return [promptId: id, edited: edited, defaultChangedSinceEdit: edited && row.defaultSha256 != null && row.defaultSha256 != sha256(defaultPrompt(id)),
                chars: cfg.prompt(id).length(), lastChangedByUserId: row?.lastChangedByUserId, lastChangedDate: row?.lastChangedDate]
    }

    private static Map roleMap(AgentConfig cfg, String roleId) {
        String model = cfg.model(roleId), dflt = AgentConfigDefaults.ROLE_MODELS.get(roleId)
        return [roleId: roleId, modelId: model, defaultModelId: dflt, edited: model != dflt]
    }

    private static Map priceMap(AgentConfig cfg, String modelId, boolean hasRow, List<String> roleIds) {
        ModelPrice p = cfg.price(modelId)
        String source = !hasRow ? "DEFAULT" : (AgentConfigDefaults.PRICES.containsKey(modelId) ? "EDITED" : "ADDED")
        return [modelId: modelId, inputUsdPerMTok: p?.input, outputUsdPerMTok: p?.output, cacheReadUsdPerMTok: p?.cacheRead,
                cacheWriteUsdPerMTok: p?.cacheWrite, source: source, usedBy: roleIds.findAll { cfg.model(it) == modelId }]
    }

    private static Map textMap(String id, AgentConfig cfg, Map row) {
        String dflt = AgentTextDefaults.TEXTS.get(id)
        boolean edited = row != null && AgentTextDefaults.validTextError(id, row.textValue as String) == null
        return [textId: id, text: cfg.text(id), defaultText: dflt, tokens: AgentTextDefaults.tokens(dflt).toList(),
                maxChars: id.startsWith("tool.") ? AgentTextDefaults.MAX_TOOL_CHARS : AgentTextDefaults.MAX_OTHER_CHARS, edited: edited,
                defaultChangedSinceEdit: edited && row.defaultSha256 != null && row.defaultSha256 != sha256(dflt)]
    }

    // ------------------------------------------------------------------ prompts

    static Map updateAgentPrompt(def ec, String promptId, String promptText) {
        if (!AgentConfigDefaults.PROMPT_PATHS.containsKey(promptId)) return [error: unknown("prompt", promptId)]
        String problem = promptProblem(ec, promptText)
        if (problem != null) return [error: problem]
        String dflt = defaultPrompt(promptId)
        Map before = row(ec, "darpan.agent.AgentPrompt", [promptId: promptId])
        if (promptText == dflt) {
            // The default needs no edit row: an existing edit goes, and the save still lands in the history.
            if (before != null) deleteRow(ec, "darpan.agent.AgentPrompt", [promptId: promptId])
        } else {
            ec.entity.makeValue("darpan.agent.AgentPrompt").setAll([promptId: promptId, promptText: promptText, defaultSha256: sha256(dflt),
                    lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp]).createOrUpdate()
        }
        version(ec, promptId, promptText)
        AdminAuditSupport.record(ec, SVC + "update#AgentPrompt", "AgentPrompt", promptId, "Agent prompt ${promptId} edited (${promptText.length()} chars).".toString())
        AgentConfig.expireAfterCommit(ec)
        return [prompt: promptDetail(ec, promptId)]
    }

    /** Null when the text may be a prompt: not blank, within the cap, and naming no tenant (agent prompts are shared by every tenant). */
    static String promptProblem(def ec, String text) {
        if (text == null || !text.trim()) return "The prompt is empty."
        if (text.length() > PROMPT_MAX_CHARS) return "The prompt is longer than ${PROMPT_MAX_CHARS} characters.".toString()
        List<String> tenants = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY).useCache(false).list()*.companyUserGroupId
        String named = tenants.sort(false).find { String id -> id && Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(id) + "(?![A-Za-z0-9_])").matcher(text).find() }
        return named == null ? null : "The prompt names tenant ${named}; agent prompts are shared by every tenant.".toString()
    }

    private static void version(def ec, String promptId, String text) {
        ec.entity.makeValue("darpan.agent.AgentPromptVersion").setAll([promptId: promptId, promptText: text, changedByUserId: ec.user.userId,
                changedDate: ec.user.nowTimestamp]).setSequencedIdPrimary().create()
    }

    // ------------------------------------------------------------------ role models and prices

    static Map updateAgentRoleModel(def ec, String roleId, String modelId) {
        if (!AgentConfigDefaults.ROLE_MODELS.containsKey(roleId)) return [error: unknown("role", roleId)]
        String model = modelId?.trim()
        if (model == NONE) {
            if (roleId != AgentConfigDefaults.AUTHOR_ESCALATION) return [error: "Only AUTHOR_ESCALATION can be none."]
        } else {
            if (!AgentConfig.validModelId(model)) return [error: "${modelId} is not a model id.".toString()]
            if (AgentConfig.load(ec).price(model) == null) return [error: "Model ${model} has no price; add its price first.".toString()]
        }
        String was = AgentConfig.load(ec).model(roleId)
        ec.entity.makeValue("darpan.agent.AgentRoleConfig").setAll([roleId: roleId, modelId: model, lastChangedByUserId: ec.user.userId,
                lastChangedDate: ec.user.nowTimestamp]).createOrUpdate()
        AdminAuditSupport.record(ec, SVC + "update#AgentRoleModel", "AgentRoleConfig", roleId, "Agent model ${roleId} ${was} -> ${model}.".toString())
        AgentConfig.expireAfterCommit(ec)
        return [role: roleMap(AgentConfig.load(ec), roleId)]
    }

    static Map updateAgentModelPrice(def ec, String modelId, Object input, Object output, Object cacheRead, Object cacheWrite) {
        String model = modelId?.trim()
        if (!AgentConfig.validModelId(model)) return [error: "${modelId} is not a model id.".toString()]
        List<BigDecimal> v = [input, output, cacheRead, cacheWrite].collect { decimal(it) }
        if (v.any { it == null || it < 0G || it > MAX_PRICE } || v[0] <= 0G || v[1] <= 0G) {
            return [error: "Prices are dollars per million tokens: input and output above 0, cache prices 0 or more, none above 1000."]
        }
        AgentConfig cfg = AgentConfig.load(ec)
        ModelPrice was = cfg.price(model)
        Map values = [:]
        PRICE_FIELDS.eachWithIndex { String f, int i -> values.put(f, v[i]) }
        ec.entity.makeValue("darpan.agent.AgentModelPrice").setAll([modelId: model] + values + [lastChangedByUserId: ec.user.userId,
                lastChangedDate: ec.user.nowTimestamp]).createOrUpdate()
        AdminAuditSupport.record(ec, SVC + "update#AgentModelPrice", "AgentModelPrice", model,
                "Agent price ${model} ${was == null ? "new" : priceText(was.input, was.output, was.cacheRead, was.cacheWrite)} -> ${priceText(v[0], v[1], v[2], v[3])}.".toString())
        AgentConfig.expireAfterCommit(ec)
        return [price: priceMap(AgentConfig.load(ec), model, true, AgentConfigDefaults.ROLE_MODELS.keySet().toList())]
    }

    private static String priceText(BigDecimal... p) { p.collect { plain(it) }.join("/") + " (input/output/cache read/cache write)" }

    // ------------------------------------------------------------------ texts

    static Map updateAgentText(def ec, String textId, String text) {
        if (!AgentTextDefaults.TEXTS.containsKey(textId)) return [error: unknown("text", textId)]
        String problem = AgentTextDefaults.validTextError(textId, text)
        if (problem != null) return [error: problem]
        String was = AgentConfig.load(ec).text(textId)
        ec.entity.makeValue("darpan.agent.AgentText").setAll([textId: textId, textValue: text, defaultSha256: sha256(AgentTextDefaults.TEXTS.get(textId)),
                lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp]).createOrUpdate()
        AdminAuditSupport.record(ec, SVC + "update#AgentText", "AgentText", textId,
                "Agent text ${textId}: \"${clip(was)}\" -> \"${clip(text)}\"".toString())
        AgentConfig.expireAfterCommit(ec)
        return [text: textMap(textId, AgentConfig.load(ec), row(ec, "darpan.agent.AgentText", [textId: textId]))]
    }

    private static String clip(String s) { return s.length() <= AUDIT_TEXT_SIDE ? s : s.substring(0, AUDIT_TEXT_SIDE) + "..." }

    // ------------------------------------------------------------------ tenant caps

    static Map updateAgentTenantDailyCap(def ec, String tenantUserGroupId, Object dailyUsdCap) {
        String tenant = tenantUserGroupId?.trim()
        SettingDefinition d = DarpanSettings.catalog().get(AgentLimits.DAILY_USD_CAP_PER_TENANT)
        BigDecimal cap = decimal(dailyUsdCap)
        if (cap == null || (d.min != null && cap < d.min) || (d.max != null && cap > d.max)) {
            return [error: "A tenant's daily cap must be a number from ${plain(d.min)} to ${plain(d.max)} dollars, the same range as the global cap.".toString()]
        }
        if (!tenant || row(ec, "darpan.auth.TenantSetting", [companyUserGroupId: tenant]) == null) return [error: "Tenant ${tenant} was not found.".toString()]
        BigDecimal was = AgentConfig.load(ec).tenantCap(tenant)
        String before = was != null ? plain(was) : DarpanSettings.describe(AgentLimits.DAILY_USD_CAP_PER_TENANT).effectiveValue
        ec.entity.makeValue("darpan.agent.AgentTenantCap").setAll([tenantUserGroupId: tenant, dailyUsdCap: cap, lastChangedByUserId: ec.user.userId,
                lastChangedDate: ec.user.nowTimestamp]).createOrUpdate()
        AdminAuditSupport.record(ec, SVC + "update#AgentTenantDailyCap", "AgentTenantCap", tenant, "Agent tenant cap ${tenant} ${before} -> ${plain(cap)}.".toString())
        AgentConfig.expireAfterCommit(ec)
        return [tenantCap: [tenantUserGroupId: tenant, dailyUsdCap: cap]]
    }

    // ------------------------------------------------------------------ reset

    static Map resetAgentConfigItem(def ec, String kind, String id) {
        switch (kind) {
            case "PROMPT": return resetPrompt(ec, id)
            case "ROLE_MODEL": return resetRole(ec, id)
            case "PRICE": return resetPrice(ec, id)
            case "TEXT": return resetText(ec, id)
            case "TENANT_CAP": return resetCap(ec, id)
            default: return [error: "Unknown agent kind ${kind}.".toString()]
        }
    }

    private static Map resetPrompt(def ec, String id) {
        if (!AgentConfigDefaults.PROMPT_PATHS.containsKey(id)) return [error: unknown("prompt", id)]
        if (row(ec, "darpan.agent.AgentPrompt", [promptId: id]) != null) {
            deleteRow(ec, "darpan.agent.AgentPrompt", [promptId: id])
            version(ec, id, null)
            done(ec, "reset#AgentConfigItem", "AgentPrompt", id, "Agent prompt ${id} reset to the shipped default.")
        }
        return [reset: [kind: "PROMPT", id: id, edited: false]]
    }

    private static Map resetRole(def ec, String id) {
        if (!AgentConfigDefaults.ROLE_MODELS.containsKey(id)) return [error: unknown("role", id)]
        if (row(ec, "darpan.agent.AgentRoleConfig", [roleId: id]) != null) {
            String was = AgentConfig.load(ec).model(id)
            deleteRow(ec, "darpan.agent.AgentRoleConfig", [roleId: id])
            done(ec, "reset#AgentConfigItem", "AgentRoleConfig", id, "Agent model ${id} ${was} -> ${AgentConfigDefaults.ROLE_MODELS.get(id)}.")
        }
        return [reset: [kind: "ROLE_MODEL", id: id, edited: false]]
    }

    private static Map resetPrice(def ec, String id) {
        boolean shipped = AgentConfigDefaults.PRICES.containsKey(id)
        Map existing = row(ec, "darpan.agent.AgentModelPrice", [modelId: id])
        if (existing == null) return shipped ? [reset: [kind: "PRICE", id: id, edited: false]] : [error: unknown("price", id)]
        if (!shipped) {
            // An added price goes with its row; a role still pointing at that model would have none.
            AgentConfig cfg = AgentConfig.load(ec)
            String role = AgentConfigDefaults.ROLE_MODELS.keySet().find { cfg.model(it) == id }
            if (role != null) return [error: "${id} is the model of ${role}; pick another model first.".toString()]
        }
        deleteRow(ec, "darpan.agent.AgentModelPrice", [modelId: id])
        done(ec, "reset#AgentConfigItem", "AgentModelPrice", id, "Agent price ${id} ${shipped ? "reset to the shipped price" : "removed"}.")
        return [reset: [kind: "PRICE", id: id, edited: false]]
    }

    private static Map resetText(def ec, String id) {
        if (!AgentTextDefaults.TEXTS.containsKey(id)) return [error: unknown("text", id)]
        Map existing = row(ec, "darpan.agent.AgentText", [textId: id])
        if (existing != null) {
            deleteRow(ec, "darpan.agent.AgentText", [textId: id])
            done(ec, "reset#AgentConfigItem", "AgentText", id, "Agent text ${id}: \"${clip(existing.textValue as String)}\" -> default.")
        }
        return [reset: [kind: "TEXT", id: id, edited: false]]
    }

    private static Map resetCap(def ec, String id) {
        Map existing = row(ec, "darpan.agent.AgentTenantCap", [tenantUserGroupId: id])
        if (existing != null) {
            deleteRow(ec, "darpan.agent.AgentTenantCap", [tenantUserGroupId: id])
            String global = DarpanSettings.describe(AgentLimits.DAILY_USD_CAP_PER_TENANT).effectiveValue
            done(ec, "reset#AgentConfigItem", "AgentTenantCap", id, "Agent tenant cap ${id} ${plain(existing.dailyUsdCap as BigDecimal)} -> ${global}.")
        }
        return [reset: [kind: "TENANT_CAP", id: id, edited: false]]
    }

    // ------------------------------------------------------------------ helpers

    private static void done(def ec, String method, String targetType, String targetId, String detail) {
        AdminAuditSupport.record(ec, SVC + method, targetType, targetId, detail)
        AgentConfig.expireAfterCommit(ec)
    }

    private static String unknown(String kind, String id) { return "Unknown agent ${kind} ${id}.".toString() }

    private static String defaultPrompt(String id) { return AgentConfigDefaults.classpathText(AgentConfigDefaults.PROMPT_PATHS.get(id)) }

    private static Map row(def ec, String entity, Map pk) {
        def value = TenantScopedFinder.findGlobalUnscoped(ec, entity, WHY).condition(pk).useCache(false).one()
        return value == null ? null : (value.getMap() as Map)
    }

    private static Map<String, Map> rowsById(def ec, String entity, String idField) {
        Map<String, Map> out = new LinkedHashMap<>()
        TenantScopedFinder.findGlobalUnscoped(ec, entity, WHY).useCache(false).list().each { out.put(it.get(idField) as String, it.getMap() as Map) }
        return out
    }

    private static void deleteRow(def ec, String entity, Map pk) {
        TenantScopedFinder.findGlobalUnscoped(ec, entity, WHY).condition(pk).useCache(false).forUpdate(true).one()?.delete()
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) return null
        try { return new BigDecimal(v.toString().trim()) } catch (NumberFormatException ignored) { return null }
    }

    private static String plain(BigDecimal v) { return v == null ? "" : v.stripTrailingZeros().toPlainString() }

    private static String sha256(String text) {
        // The first 40 hex digits: the defaultSha256 column is text-short (63), and 160 bits is plenty to see that a default changed.
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).encodeHex().toString().substring(0, 40)
    }
}
