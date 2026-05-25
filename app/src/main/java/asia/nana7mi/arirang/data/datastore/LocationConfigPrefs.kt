package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
            satellites = prefs.getInt(KEY_SATELLITES, DEFAULT_SATELLITES)
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
        }
        SubmoduleConfigFiles.write(context)
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return JSONObject()
            .put(KEY_ENABLED, config.enabled)
            .put(KEY_LAST_MODIFIED, lastModified(context))
            .put(KEY_LATITUDE, config.latitude)
            .put(KEY_LONGITUDE, config.longitude)
            .put(KEY_ALTITUDE, config.altitude)
            .put(KEY_ACCURACY, config.accuracy.toDouble())
            .put(KEY_SPEED, config.speed.toDouble())
            .put(KEY_BEARING, config.bearing.toDouble())
            .put(KEY_SATELLITES, config.satellites)
            .toString()
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
            if (privatePrefs.contains(KEY_SATELLITES)) {
                putInt(KEY_SATELLITES, privatePrefs.getInt(KEY_SATELLITES, DEFAULT_SATELLITES))
            }
        }
    }
}
