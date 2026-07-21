package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.WifiConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.WifiScanNetworkSchema
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

object WifiConfigPrefs {
    const val PREFS_NAME = "wifi_config_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_MODIFIED = "last_modified"
    const val KEY_CURRENT_SSID = "current_ssid"
    const val KEY_CURRENT_BSSID = "current_bssid"
    const val KEY_IP_ADDRESS = "ip_address"
    const val KEY_GATEWAY = "gateway"
    const val KEY_DNS1 = "dns1"
    const val KEY_DNS2 = "dns2"
    const val KEY_HIDE_SCAN_RESULTS = "hide_scan_results"
    const val KEY_SCAN_RESULTS = "scan_results"
    const val KEY_SCAN_SSID = "scan_ssid"
    const val KEY_SCAN_BSSID = "scan_bssid"

    const val DEFAULT_CURRENT_SSID = "114514"
    const val DEFAULT_CURRENT_BSSID = "02:00:00:11:45:14"
    const val DEFAULT_IP_ADDRESS = "192.168.1.100"
    const val DEFAULT_GATEWAY = "192.168.1.1"
    const val DEFAULT_DNS1 = "192.168.1.1"
    const val DEFAULT_DNS2 = "8.8.8.8"
    const val DEFAULT_SCAN_SSID = "1919810"
    const val DEFAULT_SCAN_BSSID = "02:00:00:19:19:81"

    private val gson = Gson()

    data class ScanNetwork(
        val ssid: String = DEFAULT_SCAN_SSID,
        val bssid: String = DEFAULT_SCAN_BSSID
    )

    data class Config(
        val enabled: Boolean = false,
        val currentSsid: String = DEFAULT_CURRENT_SSID,
        val currentBssid: String = DEFAULT_CURRENT_BSSID,
        val ipAddress: String = DEFAULT_IP_ADDRESS,
        val gateway: String = DEFAULT_GATEWAY,
        val dns1: String = DEFAULT_DNS1,
        val dns2: String = DEFAULT_DNS2,
        val hideScanResults: Boolean = false,
        val scanResults: List<ScanNetwork> = listOf(ScanNetwork())
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context).also {
            migratePrivatePrefsIfNeeded(context, it)
        }
        val scanResults = parseScanResults(prefs.getString(KEY_SCAN_RESULTS, null))
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            currentSsid = prefs.getString(KEY_CURRENT_SSID, null)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CURRENT_SSID,
            currentBssid = prefs.getString(KEY_CURRENT_BSSID, null)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CURRENT_BSSID,
            ipAddress = prefs.getString(KEY_IP_ADDRESS, null)?.takeIf { isValidIpv4(it) }
                ?: DEFAULT_IP_ADDRESS,
            gateway = prefs.getString(KEY_GATEWAY, null)?.takeIf { isValidIpv4(it) }
                ?: DEFAULT_GATEWAY,
            dns1 = prefs.getString(KEY_DNS1, null)?.takeIf { isValidIpv4(it) }
                ?: DEFAULT_DNS1,
            dns2 = prefs.getString(KEY_DNS2, null)?.takeIf { isValidIpv4(it) }
                ?: DEFAULT_DNS2,
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
            putString(KEY_IP_ADDRESS, config.ipAddress)
            putString(KEY_GATEWAY, config.gateway)
            putString(KEY_DNS1, config.dns1)
            putString(KEY_DNS2, config.dns2)
            putBoolean(KEY_HIDE_SCAN_RESULTS, config.hideScanResults)
            putString(KEY_SCAN_RESULTS, gson.toJson(config.scanResults))
        }
        SubmoduleConfigFiles.write(context)
    }

    fun importSchema(context: Context, schema: WifiConfigSchema) {
        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                currentSsid = schema.currentSsid.trim().take(MAX_SSID_LENGTH)
                    .ifBlank { DEFAULT_CURRENT_SSID },
                currentBssid = schema.currentBssid.normalizedMacOr(DEFAULT_CURRENT_BSSID),
                ipAddress = schema.ipAddress.trim().takeIf { isValidIpv4(it) } ?: DEFAULT_IP_ADDRESS,
                gateway = schema.gateway.trim().takeIf { isValidIpv4(it) } ?: DEFAULT_GATEWAY,
                dns1 = schema.dns1.trim().takeIf { isValidIpv4(it) } ?: DEFAULT_DNS1,
                dns2 = schema.dns2.trim().takeIf { isValidIpv4(it) } ?: DEFAULT_DNS2,
                hideScanResults = schema.hideScanResults,
                scanResults = schema.scanResults.take(MAX_NETWORKS).mapNotNull { network ->
                    val ssid = network.ssid.trim().take(MAX_SSID_LENGTH)
                    val bssid = network.bssid.normalizedMacOrNull() ?: return@mapNotNull null
                    ScanNetwork(ssid = ssid, bssid = bssid)
                }
            )
        )
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return WifiConfigSchema(
            enabled = config.enabled,
            currentSsid = config.currentSsid,
            currentBssid = config.currentBssid,
            ipAddress = config.ipAddress,
            gateway = config.gateway,
            dns1 = config.dns1,
            dns2 = config.dns2,
            hideScanResults = config.hideScanResults,
            scanResults = config.scanResults.map { s -> WifiScanNetworkSchema(ssid = s.ssid, bssid = s.bssid) },
            lastModified = lastModified(context)
        ).toJson()
    }

    fun defaultScanNetwork(index: Int = 0): ScanNetwork {
        if (index == 0) return ScanNetwork()
        val suffix = index.coerceIn(1, 99)
        return ScanNetwork(
            ssid = "$DEFAULT_SCAN_SSID-$suffix",
            bssid = "02:00:00:19:19:%02x".format(suffix)
        )
    }

    fun isValidIpv4(value: String): Boolean = IPV4.matches(value.trim())

    private fun parseScanResults(json: String?): List<ScanNetwork> {
        if (json.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<ScanNetwork>>() {}.type
        return runCatching { gson.fromJson<List<ScanNetwork>>(json, type) }
            .getOrNull()
            .orEmpty()
            .filter { it.ssid.isNotBlank() || it.bssid.isNotBlank() }
    }

    private fun String.normalizedMacOr(fallback: String): String = normalizedMacOrNull() ?: fallback

    private fun String.normalizedMacOrNull(): String? {
        val normalized = trim().uppercase()
        return normalized.takeIf { MAC_ADDRESS.matches(it) }
    }

    private const val MAX_SSID_LENGTH = 32
    private const val MAX_NETWORKS = 256
    private val MAC_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val IPV4 = Regex(
        "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$"
    )

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)

    private fun migratePrivatePrefsIfNeeded(context: Context, sharedPrefs: SharedPreferences) {
        if (sharedPrefs.contains(KEY_LAST_MODIFIED)) return

        val privatePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!privatePrefs.contains(KEY_LAST_MODIFIED)) return

        sharedPrefs.edit(commit = true) {
            putBoolean(KEY_ENABLED, privatePrefs.getBoolean(KEY_ENABLED, false))
            putLong(KEY_LAST_MODIFIED, privatePrefs.getLong(KEY_LAST_MODIFIED, Date().time))
            privatePrefs.getString(KEY_CURRENT_SSID, null)?.let { putString(KEY_CURRENT_SSID, it) }
            privatePrefs.getString(KEY_CURRENT_BSSID, null)?.let { putString(KEY_CURRENT_BSSID, it) }
            privatePrefs.getString(KEY_IP_ADDRESS, null)?.let { putString(KEY_IP_ADDRESS, it) }
            privatePrefs.getString(KEY_GATEWAY, null)?.let { putString(KEY_GATEWAY, it) }
            privatePrefs.getString(KEY_DNS1, null)?.let { putString(KEY_DNS1, it) }
            privatePrefs.getString(KEY_DNS2, null)?.let { putString(KEY_DNS2, it) }
            putBoolean(KEY_HIDE_SCAN_RESULTS, privatePrefs.getBoolean(KEY_HIDE_SCAN_RESULTS, false))
            privatePrefs.getString(KEY_SCAN_RESULTS, null)?.let { putString(KEY_SCAN_RESULTS, it) }
            privatePrefs.getString(KEY_SCAN_SSID, null)?.let { putString(KEY_SCAN_SSID, it) }
            privatePrefs.getString(KEY_SCAN_BSSID, null)?.let { putString(KEY_SCAN_BSSID, it) }
        }
    }
}
