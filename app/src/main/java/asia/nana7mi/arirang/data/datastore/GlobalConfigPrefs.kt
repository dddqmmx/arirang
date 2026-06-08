package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Date

object GlobalConfigPrefs {
    const val PREFS_NAME = "global_config_prefs"
    const val KEY_RESTRICT_HOT_SWITCHING = "restrict_hot_switching"
    const val KEY_LAST_MODIFIED = "last_modified"

    data class Config(
        val restrictHotSwitching: Boolean = false
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }
        return Config(
            restrictHotSwitching = prefs.getBoolean(KEY_RESTRICT_HOT_SWITCHING, false)
        )
    }

    fun saveConfig(context: Context, config: Config): Boolean {
        val saved = prefs(context).edit()
            .putBoolean(KEY_RESTRICT_HOT_SWITCHING, config.restrictHotSwitching)
            .putLong(KEY_LAST_MODIFIED, Date().time)
            .commit()
        if (saved) {
            SubmoduleConfigFiles.write(context)
        }
        return saved
    }

    fun lastModified(context: Context): Long {
        return prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }.getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return JSONObject().apply {
            put("version", lastModified(context))
            put(KEY_RESTRICT_HOT_SWITCHING, config.restrictHotSwitching)
        }.toString()
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)

    private fun migratePrivatePrefsIfNeeded(context: Context, sharedPrefs: SharedPreferences) {
        if (sharedPrefs.contains(KEY_LAST_MODIFIED)) return

        val privatePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!privatePrefs.contains(KEY_LAST_MODIFIED) &&
            !privatePrefs.contains(KEY_RESTRICT_HOT_SWITCHING)
        ) {
            return
        }

        sharedPrefs.edit(commit = true) {
            putLong(KEY_LAST_MODIFIED, privatePrefs.getLong(KEY_LAST_MODIFIED, Date().time))
            if (privatePrefs.contains(KEY_RESTRICT_HOT_SWITCHING)) {
                putBoolean(KEY_RESTRICT_HOT_SWITCHING, privatePrefs.getBoolean(KEY_RESTRICT_HOT_SWITCHING, false))
            }
        }
    }
}
