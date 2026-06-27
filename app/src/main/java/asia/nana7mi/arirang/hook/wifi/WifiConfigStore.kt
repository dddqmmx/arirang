package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class WifiHookConfig(
    val enabled: Boolean = false,
    val currentSsid: String = WifiConfigPrefs.DEFAULT_CURRENT_SSID,
    val currentBssid: String = WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
    val hideScanResults: Boolean = false,
    val scanResults: List<WifiScanNetwork> = listOf(WifiScanNetwork())
)

internal data class WifiScanNetwork(
    val ssid: String = WifiConfigPrefs.DEFAULT_SCAN_SSID,
    val bssid: String = WifiConfigPrefs.DEFAULT_SCAN_BSSID
)

internal class WifiConfigStore {
    private val hardcodedConfig = WifiHookConfig(
        currentSsid = "1919810",
        currentBssid = "02:00:00:19:19:81",
        scanResults = listOf(
            WifiScanNetwork(ssid = "114514", bssid = "02:00:00:11:45:14"),
            WifiScanNetwork(ssid = "114", bssid = "02:00:00:00:01:14")
        )
    )

    private val configFile = HookConfigFile(
        configName = "wifi",
        prefsName = WifiConfigPrefs.PREFS_NAME,
        defaultValue = WifiHookConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = "wifi",
                force = force,
                allowBind = true,
                logName = "Wi-Fi"
            )
        },
        parseRealtimeSnapshot = ::parseSnapshot,
        readStoredConfig = ::readStored
    )

    fun current(): WifiHookConfig {
        if (DEBUG_HARDCODED_CONFIG) return hardcodedConfig
        return configFile.current()
    }

    private fun parseSnapshot(snapshot: String): WifiHookConfig? {
        return runCatching {
            val json = JSONObject(snapshot)
            WifiHookConfig(
                enabled = json.optBoolean(WifiConfigPrefs.KEY_ENABLED, false),
                currentSsid = json.optString(WifiConfigPrefs.KEY_CURRENT_SSID, "")
                    .takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_SSID,
                currentBssid = json.optString(WifiConfigPrefs.KEY_CURRENT_BSSID, "")
                    .takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
                hideScanResults = json.optBoolean(WifiConfigPrefs.KEY_HIDE_SCAN_RESULTS, false),
                scanResults = parseScanResults(
                    json.optJSONArray(WifiConfigPrefs.KEY_SCAN_RESULTS)?.toString(),
                    json.optString(WifiConfigPrefs.KEY_SCAN_SSID).takeIf { it.isNotBlank() },
                    json.optString(WifiConfigPrefs.KEY_SCAN_BSSID).takeIf { it.isNotBlank() }
                )
            )
        }.onFailure {
            HookLog.w(HookLog.Module.WIFI, "failed to parse Wi-Fi config snapshot: ${it.message}")
        }.getOrNull()
    }

    private fun readStored(prefs: XSharedPreferences): WifiHookConfig {
        return WifiHookConfig(
            enabled = prefs.getBoolean(WifiConfigPrefs.KEY_ENABLED, false),
            currentSsid = prefs.getString(WifiConfigPrefs.KEY_CURRENT_SSID, null)
                ?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_SSID,
            currentBssid = prefs.getString(WifiConfigPrefs.KEY_CURRENT_BSSID, null)
                ?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
            hideScanResults = prefs.getBoolean(WifiConfigPrefs.KEY_HIDE_SCAN_RESULTS, false),
            scanResults = parseScanResults(
                prefs.getString(WifiConfigPrefs.KEY_SCAN_RESULTS, null),
                prefs.getString(WifiConfigPrefs.KEY_SCAN_SSID, null),
                prefs.getString(WifiConfigPrefs.KEY_SCAN_BSSID, null)
            )
        )
    }

    private fun parseScanResults(
        json: String?,
        legacySsid: String?,
        legacyBssid: String?
    ): List<WifiScanNetwork> {
        val parsed = runCatching {
            val array = JSONArray(json ?: "")
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                WifiScanNetwork(
                    ssid = item.optString("ssid", WifiConfigPrefs.DEFAULT_SCAN_SSID),
                    bssid = item.optString("bssid", WifiConfigPrefs.DEFAULT_SCAN_BSSID)
                )
            }
        }.getOrDefault(emptyList())
            .filter { it.ssid.isNotBlank() || it.bssid.isNotBlank() }

        if (parsed.isNotEmpty()) return parsed
        return listOf(
            WifiScanNetwork(
                ssid = legacySsid?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_SCAN_SSID,
                bssid = legacyBssid?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_SCAN_BSSID
            )
        )
    }

    private companion object {
        private const val DEBUG_HARDCODED_CONFIG = false
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
    }
}
