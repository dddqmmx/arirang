package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.LocationConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.LocationProfileSchema
import org.json.JSONObject
import java.util.Date

object LocationConfigPrefs {
    const val PREFS_NAME = "location_config_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_MODIFIED = "last_modified"
    const val KEY_LATITUDE = "latitude"
    const val KEY_LONGITUDE = "longitude"
    const val KEY_ALTITUDE = "altitude"
    const val KEY_ACCURACY = "accuracy"
    const val KEY_SPEED = "speed"
    const val KEY_BEARING = "bearing"
    const val KEY_SATELLITES = "satellites"
    const val KEY_PER_PACKAGE = "per_package"

    const val DEFAULT_LATITUDE = 39.019444
    const val DEFAULT_LONGITUDE = 125.738052
    const val DEFAULT_ALTITUDE = 27.0
    const val DEFAULT_ACCURACY = 5.0f
    const val DEFAULT_SPEED = 0.0f
    const val DEFAULT_BEARING = 0.0f
    const val DEFAULT_SATELLITES = 12

    data class Config(
        val enabled: Boolean = false,
        val latitude: Double = DEFAULT_LATITUDE,
        val longitude: Double = DEFAULT_LONGITUDE,
        val altitude: Double = DEFAULT_ALTITUDE,
        val accuracy: Float = DEFAULT_ACCURACY,
        val speed: Float = DEFAULT_SPEED,
        val bearing: Float = DEFAULT_BEARING,
        val satellites: Int = DEFAULT_SATELLITES,
        val perPackage: Map<String, Profile> = emptyMap()
    )

    data class Profile(
        val enabled: Boolean = true,
        val latitude: Double = DEFAULT_LATITUDE,
        val longitude: Double = DEFAULT_LONGITUDE,
        val altitude: Double = DEFAULT_ALTITUDE,
        val accuracy: Float = DEFAULT_ACCURACY,
        val speed: Float = DEFAULT_SPEED,
        val bearing: Float = DEFAULT_BEARING,
        val satellites: Int = DEFAULT_SATELLITES
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: DEFAULT_LATITUDE,
            longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: DEFAULT_LONGITUDE,
            altitude = prefs.getString(KEY_ALTITUDE, null)?.toDoubleOrNull() ?: DEFAULT_ALTITUDE,
            accuracy = prefs.getString(KEY_ACCURACY, null)?.toFloatOrNull() ?: DEFAULT_ACCURACY,
            speed = prefs.getString(KEY_SPEED, null)?.toFloatOrNull() ?: DEFAULT_SPEED,
            bearing = prefs.getString(KEY_BEARING, null)?.toFloatOrNull() ?: DEFAULT_BEARING,
            satellites = prefs.getInt(KEY_SATELLITES, DEFAULT_SATELLITES),
            perPackage = parsePackageProfiles(prefs.getString(KEY_PER_PACKAGE, null))
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_LATITUDE, config.latitude.toString())
            putString(KEY_LONGITUDE, config.longitude.toString())
            putString(KEY_ALTITUDE, config.altitude.toString())
            putString(KEY_ACCURACY, config.accuracy.toString())
            putString(KEY_SPEED, config.speed.toString())
            putString(KEY_BEARING, config.bearing.toString())
            putInt(KEY_SATELLITES, config.satellites.coerceAtLeast(0))
            putString(KEY_PER_PACKAGE, packageProfilesToJson(config.perPackage).toString())
        }
        SubmoduleConfigFiles.write(context)
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return LocationConfigSchema(
            enabled = config.enabled,
            latitude = config.latitude,
            longitude = config.longitude,
            altitude = config.altitude,
            accuracy = config.accuracy,
            speed = config.speed,
            bearing = config.bearing,
            satellites = config.satellites,
            perPackage = config.perPackage.mapValues { (_, p) ->
                LocationProfileSchema(
                    enabled = p.enabled,
                    latitude = p.latitude,
                    longitude = p.longitude,
                    altitude = p.altitude,
                    accuracy = p.accuracy,
                    speed = p.speed,
                    bearing = p.bearing,
                    satellites = p.satellites
                )
            },
            lastModified = lastModified(context)
        ).toJson()
    }

    private fun parsePackageProfiles(json: String?): Map<String, Profile> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    val profile = root.optJSONObject(packageName) ?: continue
                    put(packageName, profileFromJson(profile))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun profileFromJson(json: JSONObject): Profile {
        return Profile(
            enabled = json.optBoolean(KEY_ENABLED, true),
            latitude = json.optDouble(KEY_LATITUDE, DEFAULT_LATITUDE),
            longitude = json.optDouble(KEY_LONGITUDE, DEFAULT_LONGITUDE),
            altitude = json.optDouble(KEY_ALTITUDE, DEFAULT_ALTITUDE),
            accuracy = json.optDouble(KEY_ACCURACY, DEFAULT_ACCURACY.toDouble()).toFloat(),
            speed = json.optDouble(KEY_SPEED, DEFAULT_SPEED.toDouble()).toFloat(),
            bearing = json.optDouble(KEY_BEARING, DEFAULT_BEARING.toDouble()).toFloat(),
            satellites = json.optInt(KEY_SATELLITES, DEFAULT_SATELLITES).coerceAtLeast(0)
        )
    }

    private fun packageProfilesToJson(profiles: Map<String, Profile>): JSONObject {
        return JSONObject().apply {
            profiles.toSortedMap().forEach { (packageName, profile) ->
                put(
                    packageName,
                    JSONObject()
                        .put(KEY_ENABLED, profile.enabled)
                        .put(KEY_LATITUDE, profile.latitude)
                        .put(KEY_LONGITUDE, profile.longitude)
                        .put(KEY_ALTITUDE, profile.altitude)
                        .put(KEY_ACCURACY, profile.accuracy.toDouble())
                        .put(KEY_SPEED, profile.speed.toDouble())
                        .put(KEY_BEARING, profile.bearing.toDouble())
                        .put(KEY_SATELLITES, profile.satellites.coerceAtLeast(0))
                )
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun migratePrivatePrefsIfNeeded(context: Context, sharedPrefs: SharedPreferences) {
        if (sharedPrefs.contains(KEY_LAST_MODIFIED)) return

        val privatePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!privatePrefs.contains(KEY_LAST_MODIFIED) &&
            !privatePrefs.contains(KEY_ENABLED)
        ) {
            return
        }

        sharedPrefs.edit(commit = true) {
            putBoolean(KEY_ENABLED, privatePrefs.getBoolean(KEY_ENABLED, false))
            putLong(KEY_LAST_MODIFIED, privatePrefs.getLong(KEY_LAST_MODIFIED, Date().time))
            privatePrefs.getString(KEY_LATITUDE, null)?.let { putString(KEY_LATITUDE, it) }
            privatePrefs.getString(KEY_LONGITUDE, null)?.let { putString(KEY_LONGITUDE, it) }
            privatePrefs.getString(KEY_ALTITUDE, null)?.let { putString(KEY_ALTITUDE, it) }
            privatePrefs.getString(KEY_ACCURACY, null)?.let { putString(KEY_ACCURACY, it) }
            privatePrefs.getString(KEY_SPEED, null)?.let { putString(KEY_SPEED, it) }
            privatePrefs.getString(KEY_BEARING, null)?.let { putString(KEY_BEARING, it) }
            privatePrefs.getString(KEY_PER_PACKAGE, null)?.let { putString(KEY_PER_PACKAGE, it) }
            if (privatePrefs.contains(KEY_SATELLITES)) {
                putInt(KEY_SATELLITES, privatePrefs.getInt(KEY_SATELLITES, DEFAULT_SATELLITES))
            }
        }
    }
}
