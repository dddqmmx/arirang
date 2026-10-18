package asia.nana7mi.arirang.hook.config

import android.content.Context
import asia.nana7mi.arirang.hook.ipc.ArirangClient
import asia.nana7mi.arirang.hook.xposed.HookBridge

/**
 * One place a config's values can come from.
 *
 * Every implementation is **total and non-blocking**: it returns null rather than throwing when
 * it cannot answer, and it never waits for another thread. That is what lets a store consult its
 * sources from a hook callback that runs inside `system_server`.
 *
 * [read]'s [force] means "skip your own cache", never "wait for a fresh copy". A remote source
 * may therefore hand back values fetched before the call, and a caller must not read `force` as
 * read-your-writes.
 */
internal interface ConfigSource {

    /** Identifies the source in diagnostics. */
    val id: String

    /** True when a read reflects what is on disk or in a property *now*, with no IPC. */
    val inlineReadable: Boolean

    fun read(force: Boolean): ConfigValues?
}

/**
 * The manager app's published snapshot of a config, over Binder.
 *
 * This is the freshest source and the only one for configs whose prefs this process cannot read,
 * but it is not [inlineReadable]: [ArirangClient] hands back whatever it last fetched and
 * refreshes on its own executor, so a read here is a cache hit, not a fetch.
 *
 * @param allowBind whether a read may start the service bind. False for a config read from a hook
 *   that runs on every framework call, where the bind is somebody else's job.
 * @param bindContext the Context to bind with, for a host process whose Context only exists after
 *   its `Application` is created; a null answer means "not yet, do not bind".
 */
internal class ServiceSnapshotSource(
    private val configName: String,
    private val logName: String = configName,
    private val allowBind: Boolean = true,
    private val bindContext: (() -> Context?)? = null,
) : ConfigSource {

    override val id = "snapshot:$configName"

    override val inlineReadable = false

    override fun read(force: Boolean): ConfigValues? {
        val context = bindContext?.invoke()
        val snapshot = runCatching {
            ArirangClient.readConfigSnapshot(
                configName = configName,
                force = force,
                allowBind = allowBind && (bindContext == null || context != null),
                bindContext = context,
                bindCurrentUser = context != null,
                logName = logName
            )
        }.getOrNull() ?: return null

        return ConfigValues.ofJson(snapshot)
    }
}

/**
 * The manager app's `SharedPreferences` for [prefsName], through the libxposed remote-preferences
 * bridge.
 *
 * [inlineReadable] because the bridge serves a locally held copy; it throws rather than blocking
 * when the module is not connected yet, which reads as "unavailable".
 */
internal class RemotePrefsSource(private val prefsName: String) : ConfigSource {

    override val id = "prefs:$prefsName"

    override val inlineReadable = true

    override fun read(force: Boolean): ConfigValues? = runCatching {
        ConfigValues.ofPreferences(HookBridge.getRemotePreferences(prefsName).all)
    }.getOrNull()
}

/**
 * `persist.*` system properties, which is how the module's shell gets a debug override to a
 * process it otherwise cannot reach.
 *
 * Only [propertyNames] are consulted — properties cannot be enumerated — and the reflective handle is
 * resolved once, because a refresh walks every key.
 */
internal class SystemPropertySource(
    private val prefix: String,
    private val propertyNames: Set<String>,
) : ConfigSource {

    override val id = "property:$prefix"

    override val inlineReadable = true

    private val get by lazy {
        runCatching {
            val clazz = HookBridge.findClass("android.os.SystemProperties", null)
            clazz.getDeclaredMethod("get", String::class.java, String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    override fun read(force: Boolean): ConfigValues? {
        val method = get ?: return null
        val found = buildMap {
            propertyNames.forEach { key ->
                runCatching { method.invoke(null, prefix + key, "") as? String }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put(key, it) }
            }
        }
        return if (found.isEmpty()) null else ConfigValues.of(found)
    }
}

/**
 * How a store picks one answer out of its sources' answers.
 *
 * The candidates arrive in source order, already filtered to the sources that answered.
 */
internal fun interface ConfigArbitration {
    /** The candidates in the order they should be tried. */
    fun order(candidates: List<ConfigValues>): List<ConfigValues>
}

internal object Arbitrations {

    /** The first source that answered, in source order. */
    val Single = ConfigArbitration { candidates ->
        candidates.filter { !it.isEmpty() }
    }

    /**
     * The highest [ConfigValues.version], earlier sources winning a tie.
     *
     * Needed where a value can legitimately be written to either place — the prefs file is
     * written by the manager app, the snapshot by the service — so neither is authoritative and
     * only the stamp says which is newer.
     */
    val FreshestVersion = ConfigArbitration { candidates ->
        candidates.filter { !it.isEmpty() }.sortedByDescending { it.version() }
    }

    /**
     * Later sources form the base and earlier ones overwrite key by key.
     *
     * For a source that supplies a handful of keys and must not disturb the rest — the debug
     * properties overlay, which sets only the log switches an operator named.
     */
    val Overlay = ConfigArbitration { candidates ->
        val usable = candidates.filter { !it.isEmpty() }
        val base = usable.lastOrNull() ?: return@ConfigArbitration emptyList()
        listOf(
            usable.dropLast(1).reversed().fold(base) { merged, top ->
                ConfigValues.of(merged.asMap() + top.asMap())
            }
        )
    }
}
