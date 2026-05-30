package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object WifiConfigPrefs {
    const val PREFS_NAME = "wifi_config_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_MODIFIED = "last_modified"
    const val KEY_CURRENT_SSID = "current_ssid"
    const val KEY_CURRENT_BSSID = "current_bssid"
    const val KEY_HIDE_SCAN_RESULTS = "hide_scan_results"
    const val KEY_SCAN_RESULTS = "scan_results"
    const val KEY_SCAN_SSID = "scan_ssid"
    const val KEY_SCAN_BSSID = "scan_bssid"

    const val DEFAULT_CURRENT_SSID = "114514"
    const val DEFAULT_CURRENT_BSSID = "02:00:00:11:45:14"
    const val DEFAULT_SCAN_SSID = "1919810"
    const val DEFAULT_SCAN_BSSID = "02:00:00:19:19:81"

    private val gson = Gson()

    data class ScanNetwork(
        val ssid: String = DEFAULT_SCAN_SSID,
        val bssid: String = DEFAULT_SCAN_BSSID
    )

    data class Config(
        val enabled: Boolean = true,
        val currentSsid: String = DEFAULT_CURRENT_SSID,
        val currentBssid: String = DEFAULT_CURRENT_BSSID,
        val hideScanResults: Boolean = false,
        val scanResults: List<ScanNetwork> = listOf(ScanNetwork())
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }
        val scanResults = parseScanResults(prefs.getString(KEY_SCAN_RESULTS, null))
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            currentSsid = prefs.getString(KEY_CURRENT_SSID, null)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CURRENT_SSID,
            currentBssid = prefs.getString(KEY_CURRENT_BSSID, null)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CURRENT_BSSID,
            hideScanResults = prefs.getBoolean(KEY_HIDE_SCAN_RESULTS, false),
            scanResults = scanResults
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_CURRENT_SSID, config.currentSsid)
            putString(KEY_CURRENT_BSSID, config.currentBssid)
            putBoolean(KEY_HIDE_SCAN_RESULTS, config.hideScanResults)
            putString(KEY_SCAN_RESULTS, gson.toJson(config.scanResults))
        }
        SubmoduleConfigFiles.write(context)
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        val scanResults = JSONArray().apply {
            config.scanResults.forEach { network ->
                put(
                    JSONObject()
                        .put("ssid", network.ssid)
                        .put("bssid", network.bssid)
                )
            }
        }
        return JSONObject()
            .put(KEY_ENABLED, config.enabled)
            .put(KEY_LAST_MODIFIED, lastModified(context))
            .put(KEY_CURRENT_SSID, config.currentSsid)
            .put(KEY_CURRENT_BSSID, config.currentBssid)
            .put(KEY_HIDE_SCAN_RESULTS, config.hideScanResults)
            .put(KEY_SCAN_RESULTS, scanResults)
            .toString()
    }

    fun defaultScanNetwork(index: Int = 0): ScanNetwork {
        if (index == 0) return ScanNetwork()
        val suffix = index.coerceIn(1, 99)
        return ScanNetwork(
            ssid = "$DEFAULT_SCAN_SSID-$suffix",
            bssid = "02:00:00:19:19:%02x".format(suffix)
        )
    }

    private fun parseScanResults(json: String?): List<ScanNetwork> {
        if (json.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<ScanNetwork>>() {}.type
        return runCatching { gson.fromJson<List<ScanNetwork>>(json, type) }
            .getOrNull()
            .orEmpty()
            .filter { it.ssid.isNotBlank() || it.bssid.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)

    private fun migratePrivatePrefsIfNeeded(context: Context, sharedPrefs: SharedPreferences) {
        if (sharedPrefs.contains(KEY_LAST_MODIFIED)) return

        val privatePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!privatePrefs.contains(KEY_LAST_MODIFIED)) return

        sharedPrefs.edit(commit = true) {
            putBoolean(KEY_ENABLED, privatePrefs.getBoolean(KEY_ENABLED, true))
            putLong(KEY_LAST_MODIFIED, privatePrefs.getLong(KEY_LAST_MODIFIED, Date().time))
            privatePrefs.getString(KEY_CURRENT_SSID, null)?.let { putString(KEY_CURRENT_SSID, it) }
            privatePrefs.getString(KEY_CURRENT_BSSID, null)?.let { putString(KEY_CURRENT_BSSID, it) }
            putBoolean(KEY_HIDE_SCAN_RESULTS, privatePrefs.getBoolean(KEY_HIDE_SCAN_RESULTS, false))
            privatePrefs.getString(KEY_SCAN_RESULTS, null)?.let { putString(KEY_SCAN_RESULTS, it) }
            privatePrefs.getString(KEY_SCAN_SSID, null)?.let { putString(KEY_SCAN_SSID, it) }
            privatePrefs.getString(KEY_SCAN_BSSID, null)?.let { putString(KEY_SCAN_BSSID, it) }
        }
    }
}
