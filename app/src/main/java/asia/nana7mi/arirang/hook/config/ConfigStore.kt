package asia.nana7mi.arirang.hook.config

import android.os.SystemClock
import asia.nana7mi.arirang.hook.xposed.HookLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The current value of one config, for this process.
 *
 * This is the whole interface a feature needs: read [current], and ask for a [field] of a config
 * it does not own. Which sources exist, in what order they win, how often to look, whether the
 * value is readable before the service is bound, and how to avoid re-entering a decode are all
 * behind it.
 *
 * [current] returns the last decoded value and never blocks and never throws: a refresh happens
 * on the store's own thread, and a config that cannot be read answers with the spec's fallback.
 * The cost of a call is a volatile read plus a clock comparison; a source is read at most once per
 * `refreshIntervalMs` per process.
 */
internal interface ConfigStore<T> {

    val id: String

    /** The current value. [force] asks for a refresh without waiting for it. */
    fun current(force: Boolean = false): T

    /** Schedules a refresh now. Never blocks, never throws. */
    fun refresh()

    /** Bumped whenever the decoded value changes, so a caller can invalidate its own cache. */
    fun version(): Long

    /** The freshest value of a field of this config. Never throws. */
    fun <V> field(field: ConfigField<T, V>): V
}

/**
 * Registry of the one [ConfigCache] per config id.
 *
 * Only the *reads* are shared, not the decoded value: one config can have more than one consumer
 * that wants a different projection of it — the unique-identifier config is read by the GMS
 * module for its advertising ID and by the SIM module for its per-slot IMEI — and each of those
 * declares its own spec and its own decoded type. They must share the source reads, because two
 * readers in one process with independent refresh clocks are free to act on two different values
 * of one config, which is exactly what used to happen. They must not share the decoded value,
 * because they do not decode the same thing.
 */
internal object Configs {

    private val caches = ConcurrentHashMap<String, ConfigCache>()

    fun cacheFor(spec: ConfigSpec<*>): ConfigCache =
        caches.computeIfAbsent(spec.id) { id -> ConfigCache(id, spec).also { it.warm() } }
}

/**
 * The freshest values of one config, read from its sources on a cadence and cached.
 *
 * Owns everything that is per *config* rather than per consumer: the source list, the
 * arbitration, the refresh clock, and the guard that keeps a decode from re-entering itself.
 */
internal class ConfigCache(
    private val id: String,
    private val spec: ConfigSpec<*>,
) {

    /** The candidates in arbitration order; the first the decoder accepts is the value. */
    @Volatile
    private var ordered: List<ConfigValues> = emptyList()

    /**
     * When [ordered] was last read, relative to the uptime clock.
     *
     * Negative by one interval so the first [candidates] call schedules a read: comparing against
     * `Long.MIN_VALUE` would overflow to a negative difference and the refresh would never be due.
     */
    @Volatile
    private var checkedAt: Long = -spec.refreshIntervalMs

    private val refreshing = AtomicBoolean(false)
    private val inlineLock = Any()

    /**
     * The candidates to decode, freshest first, reading again when [force] or when the spec's
     * cadence says a read is due.
     *
     * One entry point rather than a read plus a separate invalidate, so a forced read cannot be
     * performed twice. Returns the last known candidates when a read is not due or cannot run, so
     * a caller never waits for one.
     */
    fun candidates(force: Boolean): List<ConfigValues> {
        if (LOADING.get().contains(id)) return cached()

        if (spec.readInline) {
            synchronized(inlineLock) {
                if (force || due()) {
                    reload(force)
                    checkedAt = SystemClock.uptimeMillis()
                }
            }
            return cached()
        }

        if (force || due()) schedule(force)
        return cached()
    }

    private fun due(): Boolean =
        SystemClock.uptimeMillis() - checkedAt >= spec.refreshIntervalMs

    /**
     * The read that lets a fresh process answer with real config rather than the fallback.
     *
     * Synchronous and once per config: it happens as the spec is installed, which is when the
     * feature's module object is constructed — the same point the per-store readers used to do
     * their initial read, so the first remote-prefs access has not moved. The asynchronous path
     * is no use here, because the value has to be there before the first hook runs.
     */
    fun warm() {
        reload(force = false)
        checkedAt = SystemClock.uptimeMillis()
    }

    private fun cached(): List<ConfigValues> = ordered

    private fun schedule(force: Boolean) {
        if (!refreshing.compareAndSet(false, true)) return
        REFRESH_EXECUTOR.execute {
            try {
                reload(force)
            } finally {
                checkedAt = SystemClock.uptimeMillis()
                refreshing.set(false)
            }
        }
    }

    /**
     * Reads every source and keeps the candidates in the order the arbitration puts them.
     *
     * The arbitration orders candidates rather than picking one, because whether a candidate is
     * usable is decided by the *decoder* — so a source that answers but cannot be decoded falls
     * through to the next one instead of blanking the config, which is what the old
     * `snapshot ?: stored` chain did and never said.
     */
    private fun reload(force: Boolean) {
        if (!LOADING.get().add(id)) return
        try {
            val candidates = spec.sources.mapNotNull { source ->
                runCatching { source.read(force)?.canonicalised(spec.canonicalKeys) }
                    .onFailure {
                        HookLog.d(
                            spec.logModule,
                            "config $id: source ${source.id} failed: ${it.message}"
                        )
                    }
                    .getOrNull()
            }
            spec.arbitration.order(candidates)
                .takeIf { it.isNotEmpty() }
                ?.let { ordered = it }
        } finally {
            LOADING.get().remove(id)
        }
    }

    companion object {
        /** One thread for every config, as the single per-store executor used to be. */
        private val REFRESH_EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ArirangConfigRefresh").apply { isDaemon = true }
        }

        /** Config ids being read on this thread, so a read cannot re-enter its own config. */
        private val LOADING: ThreadLocal<MutableSet<String>> = ThreadLocal.withInitial { mutableSetOf() }

        fun isReading(id: String): Boolean = LOADING.get().contains(id)
    }
}

internal class ConfigStoreImpl<T>(private val spec: ConfigSpec<T>) : ConfigStore<T> {

    private val cache = Configs.cacheFor(spec)

    override val id: String = spec.id

    @Volatile
    private var cached: T = spec.fallback

    @Volatile
    private var generation: Long = 0

    /** Decodes once at install, so a process answers with real config before its first hook. */
    fun warm() {
        decodeOnce(cache.candidates(force = false))
    }

    override fun current(force: Boolean): T {
        // A decode that reaches this config again gets the last value instead of re-entering.
        if (ConfigCache.isReading(spec.id)) return cached
        decodeOnce(cache.candidates(force))
        return cached
    }

    override fun refresh() {
        cache.candidates(force = true)
    }

    override fun version(): Long = generation

    override fun <V> field(field: ConfigField<T, V>): V {
        val values = cache.candidates(force = false).firstOrNull() ?: return field.default
        return field.read(values) ?: field.default
    }

    /**
     * Decodes the first candidate this spec's decoder accepts, leaving the value unchanged when
     * none is acceptable — a failed refresh must not blank a config that was already known.
     */
    private fun decodeOnce(candidates: List<ConfigValues>) {
        for (candidate in candidates) {
            val decoded = runCatching { spec.decode(candidate) }.getOrNull() ?: continue
            if (decoded != cached) {
                cached = decoded
                generation++
            }
            return
        }
    }
}
