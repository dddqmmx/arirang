package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Date

object HookLogSettings {
    const val PREFS_NAME = "hook_log_settings"
    private const val KEY_PREFIX_ENABLED = "enabled_"
    private const val KEY_LAST_MODIFIED = "last_modified"

    data class Module(
        val key: String,
        val labelRes: Int
    )

    val MODULE_KEYS = listOf(
        "core",
        "clipboard",
        "gms",
        "location",
        "package_list",
        "settings",
        "sim",
        "wifi",
        "unique_id",
        "notify"
    )

    fun isEnabled(context: Context, key: String): Boolean {
        return prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }.getBoolean(prefKey(key), true)
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean): Boolean {
        return prefs(context)
            .edit()
            .putBoolean(prefKey(key), enabled)
            .putLong(KEY_LAST_MODIFIED, Date().time)
            .commit()
    }

    fun lastModified(context: Context): Long {
        return prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }.getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val prefs = prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }
        return JSONObject().apply {
            put("version", prefs.getLong(KEY_LAST_MODIFIED, 0L))
            MODULE_KEYS.forEach { key ->
                put(key, prefs.getBoolean(prefKey(key), true))
            }
        }.toString()
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)

    fun prefKey(key: String): String = KEY_PREFIX_ENABLED + key

    private fun migratePrivatePrefsIfNeeded(context: Context, sharedPrefs: SharedPreferences) {
        if (sharedPrefs.contains(KEY_LAST_MODIFIED)) return

        val privatePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!privatePrefs.contains(KEY_LAST_MODIFIED) &&
            MODULE_KEYS.none { privatePrefs.contains(prefKey(it)) }
        ) {
            return
        }

        sharedPrefs.edit(commit = true) {
            putLong(KEY_LAST_MODIFIED, privatePrefs.getLong(KEY_LAST_MODIFIED, Date().time))
            MODULE_KEYS.forEach { key ->
                if (privatePrefs.contains(prefKey(key))) {
                    putBoolean(prefKey(key), privatePrefs.getBoolean(prefKey(key), true))
                }
            }
        }
    }
}
