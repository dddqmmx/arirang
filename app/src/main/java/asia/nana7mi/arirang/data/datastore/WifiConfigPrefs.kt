package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scanResults = parseScanResults(prefs.getString(KEY_SCAN_RESULTS, null)).ifEmpty {
            listOf(
                ScanNetwork(
                    ssid = prefs.getString(KEY_SCAN_SSID, null)?.takeIf { it.isNotBlank() }
                        ?: DEFAULT_SCAN_SSID,
                    bssid = prefs.getString(KEY_SCAN_BSSID, null)?.takeIf { it.isNotBlank() }
                        ?: DEFAULT_SCAN_BSSID
                )
            )
        }
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_CURRENT_SSID, config.currentSsid)
            putString(KEY_CURRENT_BSSID, config.currentBssid)
            putBoolean(KEY_HIDE_SCAN_RESULTS, config.hideScanResults)
            putString(KEY_SCAN_RESULTS, gson.toJson(config.scanResults))
        }
        makeReadableForHooks(context)
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

    private fun makeReadableForHooks(context: Context) {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefsFile = File(sharedPrefsDir, "$PREFS_NAME.xml")
        sharedPrefsDir.setExecutable(true, false)
        sharedPrefsDir.setReadable(true, false)
        prefsFile.setReadable(true, false)
    }
}
