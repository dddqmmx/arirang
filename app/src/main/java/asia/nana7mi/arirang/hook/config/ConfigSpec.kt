package asia.nana7mi.arirang.hook.config

import asia.nana7mi.arirang.hook.xposed.HookCallback
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.MethodHookParam
import asia.nana7mi.arirang.hook.xposed.afterHookedMethod
import asia.nana7mi.arirang.hook.xposed.beforeHookedMethod

/**
 * One config, declared whole: where its values come from, how to combine them, how to decode
 * them, and the named questions callers ask of it.
 *
 * A feature declares this once, next to the config type it produces, and gets a [ConfigStore].
 * Everything a caller used to have to know — which sources exist, in what order they win, how
 * often to look, whether a value is readable before the service is up, how to avoid re-entering
 * its own decode — is settled here instead of at each of the call sites.
 *
 * The spec is also where a config's **named guards** live. A guard is a predicate over the
 * decoded value that a hook callback is gated on, named by what it means rather than written out
 * at each site:
 *
 * ```kotlin
 * internal object WifiConfig : ConfigSpec<WifiHookConfig>(...) {
 *     val identity = guard("identity") { it.enabled && !it.unchangedCurrentWifi }
 *     val scanResults = guard("scanResults") { it.enabled && !it.unchangedScanResults }
 * }
 * ```
 *
 * Naming them exposes the distinctions that the same predicate written inline at 29 sites hid —
 * `identity` and `scanResults` differ, and a fresh name for a genuinely third case is visible
 * rather than a polarity accident.
 *
 * @param id the config's identity, for diagnostics and for interning one store per process.
 * @param fallback the value before any source has answered, and the value a config that cannot be
 *   read at all settles on. Must be a safe "do nothing" value.
 * @param sources consulted in order; see [ConfigArbitration] for how their answers combine.
 * @param readInline true for a config that must reflect a write immediately — the caller's thread
 *   does the read and decode under a lock instead of waiting for the background refresh.
 * @param decode the single decoder for this config. It reads one [ConfigValues] and cannot tell
 *   which source produced it, so its validation and defaults exist once instead of per source.
 */
internal open class ConfigSpec<T>(
    val id: String,
    val logModule: HookLog.Module,
    val fallback: T,
    val sources: List<ConfigSource>,
    val decode: (ConfigValues) -> T?,
    val arbitration: ConfigArbitration = Arbitrations.Single,
    val readInline: Boolean = false,
    /**
     * How long a decoded value stays current before a background refresh is scheduled. Purely a
     * staleness bound: it never delays a return, so a value can be this old and no older.
     */
    val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    /**
     * Alias spellings to collapse to one key before sources are combined; see
     * [ConfigValues.canonicalised]. Leave empty when the decoder's own alias lookup is enough —
     * which it is for every config whose sources are consulted in order rather than merged.
     */
    val canonicalKeys: Map<String, String> = emptyMap(),
    val typeName: String = id,
) {

    /**
     * The store for this config. Initialised when the spec object is, so a process has its config
     * before the first hook runs.
     */
    val store: ConfigStore<T> = ConfigStoreImpl(this).also { it.warm() }

    fun current(force: Boolean = false): T = store.current(force)

    private val guards = mutableMapOf<String, Guard<T>>()

    private val fields = mutableMapOf<String, ConfigField<T, *>>()

    /** Declares a named gate over this config; see the class comment. */
    protected fun guard(name: String, test: (T) -> Boolean): Guard<T> =
        Guard(this, name, test).also { guards[name] = it }

    /**
     * Declares a read of one field of this config.
     *
     * For a module that needs a single value out of a config another module owns — the Bluetooth
     * device name out of the Bluetooth config, say — so it can ask for that field instead of
     * standing up a second reader of the whole config in the same process.
     */
    protected fun <V> field(name: String, default: V, extract: (ConfigValues) -> V?): ConfigField<T, V> =
        ConfigField(this, name, default, extract).also { fields[name] = it }

    internal companion object {
        /** The cadence most configs use; a slower one says so at its own declaration. */
        const val DEFAULT_REFRESH_INTERVAL_MS = 300L
    }
}

/**
 * A named predicate over a config value, and the hook-callback gate built from it.
 *
 * The gate absorbs the preamble that used to be written out at every site — nothing to do when
 * the hooked method threw, nothing to do while the feature is off — so a callback body states
 * only what is peculiar to it.
 *
 * [around] resolves the config **once per invocation** and hands the same value to both halves,
 * which is what a before/after pair that stashes a snapshot in the param's extras depends on:
 * a config flip mid-call must not make the halves disagree.
 */
internal class Guard<T>(
    private val spec: ConfigSpec<T>,
    val name: String,
    private val test: (T) -> Boolean,
) {

    fun holds(value: T): Boolean = test(value)

    /** The config if this guard holds for it, or null. Never throws. */
    fun currentOrNull(): T? = spec.current().takeIf(test)

    /**
     * @param where the site's own precondition, given the value the guard admitted. For a rule
     *   that depends on who is asking — the VPN and proxy rules govern a caller only if that
     *   caller is not exempt — this keeps the precondition inside the gate, so it cannot be
     *   forgotten after the body has already started.
     */
    fun after(
        where: (MethodHookParam, T) -> Boolean = { _, _ -> true },
        body: MethodHookParam.(T) -> Unit,
    ): HookCallback = afterHookedMethod {
        val value = resolve(where) ?: return@afterHookedMethod
        runCatching { body(this, value) }
    }

    fun before(
        where: (MethodHookParam, T) -> Boolean = { _, _ -> true },
        body: MethodHookParam.(T) -> Unit,
    ): HookCallback = beforeHookedMethod {
        val value = resolve(where) ?: return@beforeHookedMethod
        runCatching { body(this, value) }
    }

    /**
     * A before/after pair gated by **one** evaluation, made when the hooked method is entered.
     *
     * If the gate does not open there, neither half runs — so a half that undoes what the other
     * did cannot run alone, and a config flip mid-call cannot make the halves disagree. This is
     * the invariant the Wi-Fi snapshot/restore pairs have always needed and never stated.
     */
    fun around(
        where: (MethodHookParam, T) -> Boolean = { _, _ -> true },
        before: MethodHookParam.(T) -> Unit,
        after: MethodHookParam.(T) -> Unit,
    ): HookCallback = object : HookCallback() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val value = param.resolve(where) ?: return
            runCatching { before.invoke(param, value) }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            @Suppress("UNCHECKED_CAST")
            val value = param.getObjectExtra(extraKey) as? T ?: return
            runCatching { after.invoke(param, value) }
        }
    }

    /** The config for this invocation, remembered for [around]'s second half. Null closes it. */
    private fun MethodHookParam.resolve(where: (MethodHookParam, T) -> Boolean): T? {
        if (hasThrowable()) return null
        val value = spec.current().takeIf(test) ?: return null
        if (!where(this, value)) return null
        setObjectExtra(extraKey, value)
        return value
    }

    /** Namespaced by guard, so two guards can gate one method without clobbering each other. */
    private val extraKey: String get() = EXTRA_PREFIX + name

    private companion object {
        const val EXTRA_PREFIX = "arirang.guard."
    }
}

/** A typed read of one field, declared by the config that owns it; see [ConfigSpec.field]. */
internal class ConfigField<T, V>(
    private val spec: ConfigSpec<T>,
    val name: String,
    val default: V,
    private val extract: (ConfigValues) -> V?,
) {
    /** The field's value from the freshest values this process has, or [default]. Never throws. */
    fun current(): V = spec.store.field(this)

    internal fun read(values: ConfigValues): V? = runCatching { extract(values) }.getOrNull()
}
