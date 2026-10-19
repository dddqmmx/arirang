package asia.nana7mi.arirang.hook.xposed

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.hook.config.Arbitrations
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.config.SystemPropertySource

private const val PROPERTY_PREFIX = "persist.arirang.log."
private const val REFRESH_INTERVAL_MS = 1_000L

/** Every switch an operator can override with a `persist.arirang.log.*` property. */
private val PROPERTY_KEYS: Set<String> = buildSet {
    add(HookLog.ALL_KEY)
    add(HookLog.DEBUG_KEY)
    HookLog.Module.entries.forEach { module ->
        add(module.key)
        add("${module.key}.${HookLog.DEBUG_SUFFIX}")
    }
}

/**
 * The hook-log switches, as an ordinary config.
 *
 * Three sources, in precedence order:
 *
 *  1. a `persist.arirang.log.*` system property, which is how an operator reaches a process the
 *     module otherwise cannot be reconfigured in;
 *  2. the manager app's published snapshot, which is the normal path;
 *  3. the `SharedPreferences` file, which is all a process has before the service is reachable.
 *
 * Their keys do not even agree: the snapshot says `packageList` and `uniqueId`, the prefs file
 * says `enabled_package_list`, a property says `package_list`. [canonicalKeys] collapses those
 * before the overlay combines them — otherwise the lowest-priority source could win a key merely
 * by spelling it differently from the others.
 *
 * This config used to be a second, hand-rolled cache with its own 1000 ms clock, its own executor,
 * a thread-local "am I resolving" flag and a function that re-parsed the schema only to
 * re-serialise it under the module keys. All of that is the seam's job now.
 */
internal object HookLogSwitches : ConfigSpec<Map<String, Boolean>>(
    id = ConfigIds.HOOK_LOG,
    typeName = "hook log",
    logModule = HookLog.Module.CORE,
    fallback = emptyMap(),
    sources = listOf(
        SystemPropertySource(PROPERTY_PREFIX, PROPERTY_KEYS),
        ServiceSnapshotSource(ConfigIds.HOOK_LOG, logName = "hook log"),
        RemotePrefsSource(HookLogSettings.PREFS_NAME),
    ),
    arbitration = Arbitrations.Overlay,
    canonicalKeys = buildMap {
        put("packageList", HookLog.Module.PACKAGE_LIST.key)
        put("uniqueId", HookLog.Module.UNIQUE_ID.key)
        HookLog.Module.entries.forEach { put(HookLogSettings.prefKey(it.key), it.key) }
    },
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
)

private fun decode(raw: ConfigValues): Map<String, Boolean>? {
    if (raw.isEmpty()) return null
    val switches = HashMap<String, Boolean>()
    raw.boolean(HookLog.ALL_KEY)?.let { switches[HookLog.ALL_KEY] = it }
    raw.boolean(HookLog.DEBUG_KEY)?.let { switches[HookLog.DEBUG_KEY] = it }
    HookLog.Module.entries.forEach { module ->
        // Only a switch a source actually set goes in. Writing the module default here would
        // shadow the `all` switch below, which then could never turn a module off.
        raw.boolean(module.key)?.let { switches[module.key] = it }
        val debugKey = "${module.key}.${HookLog.DEBUG_SUFFIX}"
        raw.boolean(debugKey)?.let { switches[debugKey] = it }
    }
    return switches
}
