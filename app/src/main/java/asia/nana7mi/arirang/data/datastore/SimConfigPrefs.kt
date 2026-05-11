package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.model.SimInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Date

object SimConfigPrefs {
    const val PREFS_NAME = "sim_config_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MODIFIED = "last_modified"
    private const val KEY_SIM_INFO_LIST = "sim_info_list"
    private const val KEY_HIDE_SIM = "hide_sim"

    private val gson = Gson()

    data class Config(
        val enabled: Boolean = false,
        val hideSim: Boolean = false,
        val simInfoList: List<SimInfo> = emptyList()
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hideSim = prefs.getBoolean(KEY_HIDE_SIM, false),
            simInfoList = loadSimInfoList(prefs)
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putBoolean(KEY_HIDE_SIM, config.hideSim)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_SIM_INFO_LIST, gson.toJson(config.simInfoList))
        }
        makePrefsReadable(context)
    }

    private fun loadSimInfoList(prefs: SharedPreferences): List<SimInfo> {
        val json = prefs.getString(KEY_SIM_INFO_LIST, null) ?: return emptyList()
        val type = object : TypeToken<List<SimInfo>>() {}.type
        return runCatching { gson.fromJson<List<SimInfo>>(json, type) }.getOrNull().orEmpty()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun makePrefsReadable(context: Context) {
        runCatching {
            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            prefsFile.setReadable(true, false)
            prefsFile.parentFile?.setExecutable(true, false)
        }
    }
}
