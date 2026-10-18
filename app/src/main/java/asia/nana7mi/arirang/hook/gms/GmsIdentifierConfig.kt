package asia.nana7mi.arirang.hook.gms

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.hook.config.Arbitrations
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.target.Targets
import asia.nana7mi.arirang.hook.xposed.HookLog

internal data class GmsIdentifierConfig(
    val enabled: Boolean = false,
    val gaid: String = "",
    val appSetId: String = ""
)

private const val REFRESH_INTERVAL_MS = 300L

/**
 * The GMS-projected view of the unique-identifier config: the advertising ID and the app set ID.
 *
 * The SIM module reads a **different projection** of this same config — its per-slot IMEI and TAC
 * — and declares its own spec for it. They share this config's source reads and cache, which is
 * the point: two consumers reading one config with independent refresh clocks could act on two
 * different values of it. They do not share the decoded value, because they do not decode the
 * same thing.
 *
 * The source binds with the host process's Context, so it stays unable to bind until GMS's
 * `Application` exists — as the hand-written store did, and the reason this module declares
 * `installsBeforeConfigIsKnown`.
 */
internal object UniqueIdentifierConfig : ConfigSpec<GmsIdentifierConfig>(
    id = ConfigIds.UNIQUE_IDENTIFIER,
    typeName = "unique identifier",
    logModule = HookLog.Module.GMS,
    fallback = GmsIdentifierConfig(),
    sources = listOf(
        ServiceSnapshotSource(
            configName = ConfigIds.UNIQUE_IDENTIFIER,
            logName = "unique identifier",
            bindContext = { Targets.applicationContext() },
        ),
        RemotePrefsSource(UniqueIdentifierPrefs.PREFS_NAME),
    ),
    // A value can be written to either place, so only the stamp says which is newer.
    arbitration = Arbitrations.FreshestVersion,
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
) {
    /** The advertising ID surfaces. Nothing to report while the configured ID is blank. */
    val gaid = guard("gaid") { it.enabled && it.gaid.isNotBlank() }

    /** The Android ID to report, or null while the feature is off. */
    val spoofedAndroidId = field<String?>("spoofedAndroidId", default = null) { values ->
        enabledValue(values)?.let { values.string("androidId", "android_id") }
    }

    /** Per-slot IMEI overrides, which the SIM module embeds in its own config. */
    val imeiBySlot = field("imeiBySlot", default = emptyMap<Int, String>()) { values ->
        decodeSlotStringMap(values, "imeiBySlot", "imei_by_slot")
    }

    /**
     * Per-slot type-allocation codes, falling back to an IMEI's leading digits.
     *
     * A TAC is the first eight digits of an IMEI, so a config that names an IMEI but no TAC still
     * has one.
     */
    val tacBySlot = field("tacBySlot", default = emptyMap<Int, String>()) { values ->
        decodeSlotStringMap(values, "tacBySlot", "tac_by_slot")
            .ifEmpty {
                decodeSlotStringMap(values, "imeiBySlot", "imei_by_slot")
                    .mapValues { it.value.take(TAC_DIGITS) }
            }
    }
}

/**
 * A `slot -> string` map, however the source carries it: a JSON object from the snapshot, the
 * same JSON as text from prefs.
 */
private fun decodeSlotStringMap(raw: ConfigValues, vararg keys: String): Map<Int, String> {
    val root = raw.jsonObject(*keys) ?: return emptyMap()
    val result = sortedMapOf<Int, String>()
    val names = root.keys()
    while (names.hasNext()) {
        val name = names.next()
        val slot = name.toIntOrNull() ?: continue
        root.optString(name).takeIf { it.isNotBlank() }?.let { result[slot] = it }
    }
    return result
}

private const val TAC_DIGITS = 8

/** The configured value of a field, but only while the feature is switched on. */
private fun enabledValue(values: ConfigValues): Unit? =
    if (values.boolean("enabled") == true) Unit else null

private fun decode(values: ConfigValues): GmsIdentifierConfig? {
    if (values.isEmpty()) return null
    if (values.boolean("enabled") != true) return GmsIdentifierConfig()
    return GmsIdentifierConfig(
        enabled = true,
        gaid = values.string("gaid").orEmpty(),
        appSetId = values.string("appSetId", "app_set_id").orEmpty(),
    )
}
