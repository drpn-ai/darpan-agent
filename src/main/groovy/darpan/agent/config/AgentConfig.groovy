package darpan.agent.config

import darpan.config.DarpanSettings
import darpan.config.SettingDefinition
import darpan.facade.common.TenantScopedFinder
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import org.moqui.Moqui
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * DAR-BE-307. The agent's non-scalar config as one immutable snapshot: role models, model prices, prompts and per-tenant daily cap
 * overrides, each the shipped default (AgentConfigDefaults) with the valid edit rows over it. Same shape as DarpanSettings: cached
 * for TTL_MS, expired after the commit of an edit, one reloader at a time, and a failed reload keeps the last snapshot. current()
 * never throws; with no framework it answers the defaults. An invalid row (direct DB edit) is ignored with a WARN and its default
 * applies. A turn takes one AgentConfig and uses it throughout, so it never mixes two.
 */
class AgentConfig {
    private static final Logger logger = LoggerFactory.getLogger(AgentConfig)
    static final long TTL_MS = 30_000L
    private static final String WHY = "agent config: global settings"
    /** At most 63 characters in all: the modelId columns are text-short (VARCHAR(63) on MySQL), so a longer id could never be saved. */
    private static final String MODEL_ID_PATTERN = '^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$'
    private static final BigDecimal MAX_PRICE = 10000G

    /** Seams: the clock, the loader, and whether the framework is up. */
    static Closure<Long> clock = { -> System.currentTimeMillis() }
    static Closure<AgentConfig> loader = { ec -> load(ec) }
    static Closure<Boolean> frameworkUp = { -> Moqui.executionContextFactory != null }

    private static final class Snapshot {
        final AgentConfig config
        final long loadedAt
        final boolean expired
        Snapshot(AgentConfig config, long loadedAt, boolean expired) { this.config = config; this.loadedAt = loadedAt; this.expired = expired }
    }
    private static volatile Snapshot snapshot
    private static final AtomicBoolean reloading = new AtomicBoolean(false)
    private static final AtomicLong expireCount = new AtomicLong(0)
    private static final ThreadLocal<AgentConfig> OVERRIDE = new ThreadLocal<>()

    private final Map<String, String> models
    private final Map<String, ModelPrice> prices
    private final Map<String, String> prompts
    private final Map<String, BigDecimal> tenantCaps

    private AgentConfig(Map<String, String> models, Map<String, ModelPrice> prices, Map<String, String> prompts, Map<String, BigDecimal> tenantCaps) {
        this.models = Collections.unmodifiableMap(new LinkedHashMap<>(models))
        this.prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices))
        this.prompts = Collections.unmodifiableMap(new LinkedHashMap<>(prompts))
        this.tenantCaps = Collections.unmodifiableMap(new LinkedHashMap<>(tenantCaps))
    }

    // ------------------------------------------------------------------ access

    static AgentConfig defaults() {
        return new AgentConfig(AgentConfigDefaults.ROLE_MODELS, AgentConfigDefaults.PRICES, [:], [:])
    }

    /** A copy with the given maps merged over this one (keys models, prices, prompts, tenantCaps). */
    AgentConfig with(Map changes) {
        Map<String, String> m = new LinkedHashMap<>(models); if (changes.models) m.putAll((Map<String, String>) changes.models)
        Map<String, ModelPrice> p = new LinkedHashMap<>(prices); if (changes.prices) p.putAll((Map<String, ModelPrice>) changes.prices)
        Map<String, String> t = new LinkedHashMap<>(prompts); if (changes.prompts) t.putAll((Map<String, String>) changes.prompts)
        Map<String, BigDecimal> c = new LinkedHashMap<>(tenantCaps); if (changes.tenantCaps) c.putAll((Map<String, BigDecimal>) changes.tenantCaps)
        return new AgentConfig(m, p, t, c)
    }

    /** The effective text of a prompt id: the edit, else the shipped file. */
    String prompt(String id) {
        String edited = prompts.get(id)
        if (edited != null) return edited
        String path = AgentConfigDefaults.PROMPT_PATHS.get(id)
        if (path == null) throw new IllegalArgumentException("Unknown agent prompt: ${id}".toString())
        return AgentConfigDefaults.classpathText(path)
    }

    /** A known prompt path gives the effective text; any other path gives the class-path file. */
    String promptForPath(String path) {
        String id = AgentConfigDefaults.PROMPT_PATHS.find { it.value == path }?.key
        return id != null ? prompt(id) : AgentConfigDefaults.classpathText(path)
    }

    String model(String roleId) { return models.get(roleId) }

    /** The price of a model, or null when it is unpriced. */
    ModelPrice price(String modelId) { return modelId == null ? null : prices.get(modelId) }

    /** The dearest effective price (each component's maximum). */
    ModelPrice dearest() { return ModelPrice.max(prices.values()) }

    /** The roles whose model has no price; a role whose model is none is skipped. */
    List<String> unpricedRoles(List<String> roleIds) {
        return roleIds.findAll { String r ->
            String m = models.get(r)
            m != null && m != "none" && price(m) == null
        }
    }

    /** The tenant's daily cap override, or null (use the global cap). Valid only inside the global cap key's catalog range. */
    BigDecimal tenantCap(String tenantId) {
        BigDecimal v = tenantId == null ? null : tenantCaps.get(tenantId)
        if (v == null) return null
        SettingDefinition d
        try {
            d = DarpanSettings.catalog().get(AgentLimits.DAILY_USD_CAP_PER_TENANT)
        } catch (Throwable t) {
            logger.warn("Agent tenant cap range could not be read ({}); using the global cap", t.toString())
            return null
        }
        if (d == null || (d.min != null && v < d.min) || (d.max != null && v > d.max)) {
            logger.warn("Agent tenant cap {} for {} is outside the allowed range; using the global cap", v, tenantId)
            return null
        }
        return v
    }

    // ------------------------------------------------------------------ snapshot

    /** A thread-only override for tests that run without Moqui. */
    static <T> T withConfig(AgentConfig config, Closure<T> body) {
        AgentConfig previous = OVERRIDE.get()
        OVERRIDE.set(config)
        try { return body.call() } finally { if (previous == null) OVERRIDE.remove() else OVERRIDE.set(previous) }
    }

    static AgentConfig current(def ec) {
        AgentConfig forced = OVERRIDE.get()
        if (forced != null) return forced
        try {
            if (!frameworkUp.call()) return defaults()
            Snapshot snap = snapshot
            long now = clock.call()
            if (snap == null) {
                synchronized (AgentConfig) {
                    if (snapshot == null) reload(ec, now, null)
                    return snapshot.config
                }
            }
            if ((snap.expired || now - snap.loadedAt >= TTL_MS) && reloading.compareAndSet(false, true)) {
                try { reload(ec, now, snap) } finally { reloading.set(false) }
            }
            return snapshot.config
        } catch (Throwable t) {
            logger.warn("Agent config read failed, using the defaults: {}", t.toString())
            return snapshot != null ? snapshot.config : defaults()
        }
    }

    private static void reload(def ec, long now, Snapshot previous) {
        long seen = expireCount.get()
        try {
            AgentConfig loaded = loader.call(ec)
            if (loaded == null) throw new IllegalStateException("loader answered nothing")
            snapshot = new Snapshot(loaded, now, expireCount.get() != seen)
        } catch (Throwable t) {
            logger.warn("Agent config reload failed, keeping the last snapshot: {}", t.toString())
            snapshot = new Snapshot(previous != null ? previous.config : defaults(), now, false)
        }
    }

    static void expire() {
        expireCount.incrementAndGet()
        Snapshot snap = snapshot
        if (snap != null) snapshot = new Snapshot(snap.config, snap.loadedAt, true)
    }

    /** Expires the snapshot once the surrounding transaction has committed (now when there is none). */
    static void expireAfterCommit(def ec) {
        if (ec != null && ec.transaction.isTransactionInPlace()) {
            ec.transaction.registerSynchronization(new Synchronization() {
                @Override void beforeCompletion() { }
                @Override void afterCompletion(int status) { if (status == Status.STATUS_COMMITTED) expire() }
            })
        } else {
            expire()
        }
    }

    static void resetForTest() {
        clock = { -> System.currentTimeMillis() }
        loader = { ec -> load(ec) }
        frameworkUp = { -> Moqui.executionContextFactory != null }
        snapshot = null
        reloading.set(false)
        OVERRIDE.remove()
    }

    // ------------------------------------------------------------------ load

    /** The defaults with every valid edit row over them. Throws when the database cannot be read (the caller keeps the last snapshot). */
    static AgentConfig load(def ec) {
        def ecfi = Moqui.executionContextFactory
        if (ecfi == null) return defaults()
        boolean created = false
        def use = ec
        if (use == null) {
            use = ((ExecutionContextFactoryImpl) ecfi).activeContext.get()
            if (use == null) { use = ecfi.getExecutionContext(); created = true }
        }
        try {
            return fromRows(
                    rows(use, "darpan.agent.AgentRoleConfig"), rows(use, "darpan.agent.AgentModelPrice"),
                    rows(use, "darpan.agent.AgentPrompt"), rows(use, "darpan.agent.AgentTenantCap"))
        } finally {
            if (created) ((ExecutionContextFactoryImpl) ecfi).destroyActiveExecutionContext()
        }
    }

    private static List<Map> rows(def ec, String entity) {
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entity, WHY).useCache(false).list().collect { it.getMap() as Map }
    }

    static AgentConfig fromRows(List<Map> roles, List<Map> priceRows, List<Map> promptRows, List<Map> capRows) {
        Map<String, ModelPrice> p = new LinkedHashMap<>(AgentConfigDefaults.PRICES)
        for (Map r : priceRows) {
            String id = r.modelId as String
            ModelPrice mp = validPrice(r)
            if (!validModelId(id) || mp == null) { logger.warn("Ignoring invalid agent model price row '{}'", id); continue }
            p.put(id, mp)
        }
        Map<String, String> m = new LinkedHashMap<>(AgentConfigDefaults.ROLE_MODELS)
        for (Map r : roles) {
            String role = r.roleId as String, model = r.modelId as String
            // none means escalation off, and only the escalation author may be off: a raw none on a role that must send keeps its default.
            boolean off = model == "none" && role == AgentConfigDefaults.AUTHOR_ESCALATION
            if (!AgentConfigDefaults.ROLE_MODELS.containsKey(role) || !(off || (model != "none" && validModelId(model)))) {
                logger.warn("Ignoring invalid agent role row {} -> '{}'", role, model); continue
            }
            m.put(role, model)
        }
        Map<String, String> t = new LinkedHashMap<>()
        for (Map r : promptRows) {
            String id = r.promptId as String, text = r.promptText as String
            if (!AgentConfigDefaults.PROMPT_PATHS.containsKey(id) || !text?.trim()) { logger.warn("Ignoring invalid agent prompt row '{}'", id); continue }
            t.put(id, text)
        }
        Map<String, BigDecimal> c = new LinkedHashMap<>()
        for (Map r : capRows) {
            String tenant = r.tenantUserGroupId as String
            if (!tenant || r.dailyUsdCap == null) continue
            c.put(tenant, new BigDecimal(r.dailyUsdCap.toString()))
        }
        return new AgentConfig(m, p, t, c)
    }

    static boolean validModelId(String id) { return id != null && id.matches(MODEL_ID_PATTERN) }

    private static ModelPrice validPrice(Map r) {
        try {
            List<BigDecimal> v = ["inputUsdPerMTok", "outputUsdPerMTok", "cacheReadUsdPerMTok", "cacheWriteUsdPerMTok"].collect {
                r.get(it) == null ? null : new BigDecimal(r.get(it).toString()) }
            if (v.any { it == null || it < 0G || it > MAX_PRICE } || v[0] <= 0G || v[1] <= 0G) return null
            return new ModelPrice(v[0], v[1], v[2], v[3])
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}
