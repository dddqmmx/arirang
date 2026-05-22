package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import java.io.File
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
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
        makeReadableForHooks(context)
    }

    private fun makeReadableForHooks(context: Context) {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefsFile = File(sharedPrefsDir, "$PREFS_NAME.xml")
        sharedPrefsDir.setExecutable(true, false)
        sharedPrefsDir.setReadable(true, false)
        prefsFile.setReadable(true, false)
    }
}
