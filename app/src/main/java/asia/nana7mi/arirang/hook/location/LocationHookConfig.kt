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
 * The two sources disagree on exactly two things, and the decoder says both once: the top-level
 * `perPackage` object is spelled `per_package` in prefs, and the per-package profile's `enabled`
 * flag was defaulted **differently** by the two old decoders — `false` in the snapshot decoder
 * (matching `LocationProfileSchema` and `LocationConfigPrefs.Profile`, which both declare
 * `false`) and `true` in the prefs decoder. The models agree on `false`, so `false` is what they
 * mean, and the prefs path was the outlier.
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

private fun decode(values: ConfigValues): LocationHookConfig? {
    if (values.isEmpty()) return null
    val enabled = values.boolean("enabled") ?: false
    return LocationHookConfig(
        enabled = enabled,
        defaultProfile = decodeProfile(values, enabledWhenAbsent = enabled),
        perPackage = decodePerPackage(values.jsonObject("perPackage", "per_package")),
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
