package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.target.Targets
import asia.nana7mi.arirang.hook.xposed.HookLog
import org.json.JSONObject

internal data class LocationHookConfig(
    val enabled: Boolean = false,
    val defaultProfile: LocationProfile = LocationProfile(),
    val perPackage: Map<String, LocationProfile> = emptyMap()
)

internal data class LocationProfile(
    val enabled: Boolean = true,
    val latitude: Double = LocationConfigPrefs.DEFAULT_LATITUDE,
    val longitude: Double = LocationConfigPrefs.DEFAULT_LONGITUDE,
    val altitude: Double = LocationConfigPrefs.DEFAULT_ALTITUDE,
    val accuracy: Float = LocationConfigPrefs.DEFAULT_ACCURACY,
    val speed: Float = LocationConfigPrefs.DEFAULT_SPEED,
    val bearing: Float = LocationConfigPrefs.DEFAULT_BEARING,
    val satellites: Int = LocationConfigPrefs.DEFAULT_SATELLITES
)

private const val REFRESH_INTERVAL_MS = 300L

/**
 * The location config.
 *
 * The source binds with the host process's own Context, which does not exist until its
 * `Application` is created, so before that point the snapshot source reports itself as unable to
 * bind — as the hand-written store did.
 *
 * The two sources disagree on spelling: the top-level `perPackage` object is `per_package` in
 * prefs, and a per-package profile's `enabled` flag was defaulted **differently** by the two old
 * decoders — `false` in the snapshot decoder (matching `LocationProfileSchema`, which declares
 * `false`) and `true` in the prefs decoder. Both writers always emit the key, so the default is
 * only reachable for hand-edited JSON; `false` is kept because it is the schema's meaning and
 * the safe answer when a profile does not say.
 *
 * The user's "leave unchanged by default" switch is folded into the default profile's `enabled`
 * flag rather than carried as its own field: both mean "the default profile does not apply", and
 * every call site already answers that question by resolving a profile. With the switch on, only
 * a package with its own enabled profile is spoofed — which is also the only reading that makes
 * sense for `globalProfileIgnoringPerPackage`, where "the default, whatever it is" is all the
 * information a call site has.
 */
internal object LocationConfig : ConfigSpec<LocationHookConfig>(
    id = ConfigIds.LOCATION,
    typeName = "location",
    logModule = HookLog.Module.LOCATION,
    fallback = LocationHookConfig(),
    sources = listOf(
        ServiceSnapshotSource(
            configName = ConfigIds.LOCATION,
            logName = "location",
            bindContext = { Targets.applicationContext() },
        ),
        RemotePrefsSource(LocationConfigPrefs.PREFS_NAME),
    ),
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
)

internal fun decode(values: ConfigValues): LocationHookConfig? {
    if (values.isEmpty()) return null
    val enabled = values.boolean("enabled") ?: false
    val unchangedDefault = values.boolean("unchangedDefault", "unchanged_default") ?: false
    return LocationHookConfig(
        enabled = enabled,
        // decodeProfile reads the same top-level `enabled` key as the profile's own flag — the
        // schema spells the default profile's coordinates at the top level — so the switch has to
        // be applied after the fact rather than as the absent-key default.
        defaultProfile = decodeProfile(values, enabledWhenAbsent = enabled)
            .let { if (unchangedDefault) it.copy(enabled = false) else it },
        perPackage = decodePerPackage(
            values.jsonObject("perPackage", "per_package", "package_profiles")
        ),
    )
}

private fun decodeProfile(values: ConfigValues, enabledWhenAbsent: Boolean): LocationProfile =
    LocationProfile(
        enabled = values.boolean("enabled") ?: enabledWhenAbsent,
        latitude = values.double("latitude") ?: LocationConfigPrefs.DEFAULT_LATITUDE,
        longitude = values.double("longitude") ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
        altitude = values.double("altitude") ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
        accuracy = values.float("accuracy") ?: LocationConfigPrefs.DEFAULT_ACCURACY,
        speed = values.float("speed") ?: LocationConfigPrefs.DEFAULT_SPEED,
        bearing = values.float("bearing") ?: LocationConfigPrefs.DEFAULT_BEARING,
        satellites = (values.int("satellites") ?: LocationConfigPrefs.DEFAULT_SATELLITES)
            .coerceIn(0, MAX_SATELLITES),
    )

private fun decodePerPackage(root: JSONObject?): Map<String, LocationProfile> {
    if (root == null) return emptyMap()
    return buildMap {
        val names = root.keys()
        while (names.hasNext()) {
            val packageName = names.next()
            val entry = root.optJSONObject(packageName) ?: continue
            put(packageName, decodeProfile(ConfigValues.ofJson(entry.toString()) ?: continue, false))
        }
    }
}

private const val MAX_SATELLITES = 64
