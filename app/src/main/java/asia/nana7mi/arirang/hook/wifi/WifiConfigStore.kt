package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.schema.WifiConfigSchema
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
    private val configFile = HookConfigFile(
        configName = ConfigIds.WIFI,
        prefsName = WifiConfigPrefs.PREFS_NAME,
        defaultValue = WifiHookConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.WIFI,
                force = force,
                allowBind = true,
                logName = "Wi-Fi"
            )
        },
        parseRealtimeSnapshot = ::parseSnapshot,
        readStoredConfig = ::readStored
    )

    fun current(): WifiHookConfig {
        return configFile.current()
    }

    private fun parseSnapshot(snapshot: String): WifiHookConfig? {
        return runCatching {
            val schema = WifiConfigSchema.fromJson(snapshot)
            WifiHookConfig(
                enabled = schema.enabled,
                currentSsid = schema.currentSsid
                    .takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_SSID,
                currentBssid = schema.currentBssid
                    .takeIf(::isValidMacAddress) ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
                hideScanResults = schema.hideScanResults,
                scanResults = schema.scanResults.map { WifiScanNetwork(it.ssid, it.bssid) }
                    .filter { it.ssid.isNotBlank() && isValidMacAddress(it.bssid) }
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
                ?.takeIf(::isValidMacAddress) ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
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
        if (!json.isNullOrBlank()) {
            val parsed = runCatching {
                val array = JSONArray(json)
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    WifiScanNetwork(
                        ssid = item.optString("ssid", WifiConfigPrefs.DEFAULT_SCAN_SSID),
                        bssid = item.optString("bssid", WifiConfigPrefs.DEFAULT_SCAN_BSSID)
                    )
                }
            }
                .getOrNull()
                ?.filter { it.ssid.isNotBlank() && isValidMacAddress(it.bssid) }
            if (parsed != null) return parsed
        }
        if (legacySsid.isNullOrBlank() && legacyBssid.isNullOrBlank()) return emptyList()
        return listOf(
            WifiScanNetwork(
                ssid = legacySsid?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_SCAN_SSID,
                bssid = legacyBssid?.takeIf(::isValidMacAddress) ?: WifiConfigPrefs.DEFAULT_SCAN_BSSID
            )
        )
    }

    private companion object {
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
    }
}

internal fun isValidMacAddress(value: String): Boolean {
    return MAC_ADDRESS.matches(value)
}

private val MAC_ADDRESS = Regex("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
