package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.hook.core.HookLog

import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.schema.LocationConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.LocationProfileSchema
import de.robv.android.xposed.XSharedPreferences
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

internal object LocationHookConfigParser {
    fun parseSnapshot(snapshot: String): LocationHookConfig? {
        return runCatching {
            val schema = LocationConfigSchema.fromJson(snapshot)
            LocationHookConfig(
                enabled = schema.enabled,
                defaultProfile = schema.toProfile(),
                perPackage = schema.perPackage.mapValues { (_, profile) -> profile.toProfile() }
            )
        }.onFailure {
            HookLog.w(HookLog.Module.LOCATION, "failed to parse location config snapshot: ${it.message}")
        }.getOrNull()
    }

    private fun LocationConfigSchema.toProfile(): LocationProfile = LocationProfile(
        enabled = enabled,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        satellites = satellites.coerceIn(0, 64)
    )

    private fun LocationProfileSchema.toProfile(): LocationProfile = LocationProfile(
        enabled = enabled,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        satellites = satellites.coerceIn(0, 64)
    )

    fun readStored(prefs: XSharedPreferences): LocationHookConfig {
        return LocationHookConfig(
            enabled = prefs.getBoolean(LocationConfigPrefs.KEY_ENABLED, false),
            defaultProfile = LocationProfile(
                latitude = prefs.getString(LocationConfigPrefs.KEY_LATITUDE, null)?.toDoubleOrNull()
                    ?: LocationConfigPrefs.DEFAULT_LATITUDE,
                longitude = prefs.getString(LocationConfigPrefs.KEY_LONGITUDE, null)?.toDoubleOrNull()
                    ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
                altitude = prefs.getString(LocationConfigPrefs.KEY_ALTITUDE, null)?.toDoubleOrNull()
                    ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
                accuracy = prefs.getString(LocationConfigPrefs.KEY_ACCURACY, null)?.toFloatOrNull()
                    ?: LocationConfigPrefs.DEFAULT_ACCURACY,
                speed = prefs.getString(LocationConfigPrefs.KEY_SPEED, null)?.toFloatOrNull()
                    ?: LocationConfigPrefs.DEFAULT_SPEED,
                bearing = prefs.getString(LocationConfigPrefs.KEY_BEARING, null)?.toFloatOrNull()
                    ?: LocationConfigPrefs.DEFAULT_BEARING,
                satellites = prefs.getInt(LocationConfigPrefs.KEY_SATELLITES, LocationConfigPrefs.DEFAULT_SATELLITES)
                    .coerceIn(0, 64)
            ),
            perPackage = prefs.getString(LocationConfigPrefs.KEY_PER_PACKAGE, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(::parseStoredPackageProfiles)
                .orEmpty()
        )
    }

    private fun profileFromJson(json: JSONObject): LocationProfile {
        return LocationProfile(
            enabled = json.optBoolean(LocationConfigPrefs.KEY_ENABLED, true),
            latitude = json.optDouble(LocationConfigPrefs.KEY_LATITUDE, LocationConfigPrefs.DEFAULT_LATITUDE),
            longitude = json.optDouble(LocationConfigPrefs.KEY_LONGITUDE, LocationConfigPrefs.DEFAULT_LONGITUDE),
            altitude = json.optDouble(LocationConfigPrefs.KEY_ALTITUDE, LocationConfigPrefs.DEFAULT_ALTITUDE),
            accuracy = json.optDouble(
                LocationConfigPrefs.KEY_ACCURACY,
                LocationConfigPrefs.DEFAULT_ACCURACY.toDouble()
            ).toFloat(),
            speed = json.optDouble(LocationConfigPrefs.KEY_SPEED, LocationConfigPrefs.DEFAULT_SPEED.toDouble()).toFloat(),
            bearing = json.optDouble(
                LocationConfigPrefs.KEY_BEARING,
                LocationConfigPrefs.DEFAULT_BEARING.toDouble()
            ).toFloat(),
            satellites = json.optInt(LocationConfigPrefs.KEY_SATELLITES, LocationConfigPrefs.DEFAULT_SATELLITES)
                .coerceIn(0, 64)
        )
    }

    private fun parsePackageProfiles(json: JSONObject): Map<String, LocationProfile> {
        val root = json.optJSONObject("perPackage")
            ?: json.optJSONObject(LocationConfigPrefs.KEY_PER_PACKAGE)
            ?: json.optJSONObject("package_profiles")
            ?: json

        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val profileJson = root.optJSONObject(packageName) ?: continue
                put(packageName, profileFromJson(profileJson))
            }
        }
    }

    private fun parseStoredPackageProfiles(rawJson: String): Map<String, LocationProfile> {
        return runCatching {
            parsePackageProfiles(JSONObject(rawJson))
        }.onFailure {
            HookLog.w(HookLog.Module.LOCATION, "failed to parse stored per-package location config: ${it.message}")
        }.getOrDefault(emptyMap())
    }
}
