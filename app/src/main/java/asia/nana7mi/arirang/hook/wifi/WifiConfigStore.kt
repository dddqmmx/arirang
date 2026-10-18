package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.util.isValidMacAddress
import asia.nana7mi.arirang.hook.xposed.HookLog

internal data class WifiHookConfig(
    val enabled: Boolean = false,
    val unchangedCurrentWifi: Boolean = false,
    val currentSsid: String = WifiConfigPrefs.DEFAULT_CURRENT_SSID,
    val currentBssid: String = WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
    val ipAddress: String = WifiConfigPrefs.DEFAULT_IP_ADDRESS,
    val gateway: String = WifiConfigPrefs.DEFAULT_GATEWAY,
    val dns1: String = WifiConfigPrefs.DEFAULT_DNS1,
    val dns2: String = WifiConfigPrefs.DEFAULT_DNS2,
    val unchangedScanResults: Boolean = false,
    val hideScanResults: Boolean = false,
    val scanResults: List<WifiScanNetwork> = listOf(WifiScanNetwork())
)

internal data class WifiScanNetwork(
    val ssid: String = WifiConfigPrefs.DEFAULT_SCAN_SSID,
    val bssid: String = WifiConfigPrefs.DEFAULT_SCAN_BSSID
)

private const val REFRESH_INTERVAL_MS = 300L

/**
 * The Wi-Fi config.
 *
 * One decoder serves both sources. They disagree on spelling — the snapshot says `currentSsid`,
 * `ipAddress` and `scanResults`, prefs says `current_ssid`, `ip_address` and `scan_results` — and
 * the two old decoders each re-derived the same validation: a BSSID must be a MAC, the four
 * addresses must be IPv4, and the SSIDs must be non-blank. Writing that twice is how the two were
 * free to disagree; it is written once now, so it applies to whichever source answered.
 *
 * The prefs side also carries legacy single-network keys (`scan_ssid`/`scan_bssid`) from before
 * the config held a list; they are read only when there is no list, as before.
 */
internal object WifiConfig : ConfigSpec<WifiHookConfig>(
    id = ConfigIds.WIFI,
    typeName = "Wi-Fi",
    logModule = HookLog.Module.WIFI,
    fallback = WifiHookConfig(),
    sources = listOf(
        ServiceSnapshotSource(ConfigIds.WIFI, logName = "Wi-Fi"),
        RemotePrefsSource(WifiConfigPrefs.PREFS_NAME),
    ),
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
) {
    /**
     * The identity surfaces: the current SSID/BSSID/MAC and DHCP/DNS answers.
     *
     * `unchangedCurrentWifi` is the user's "leave the real connection alone", which is not the
     * same as the feature being off — it has to be checked wherever identity is reported, and
     * nowhere else. Naming it once stops the two being confused at six call sites.
     */
    val identity = guard("identity") { it.enabled && !it.unchangedCurrentWifi }

    /** The scan-result surfaces, governed by `unchangedScanResults` instead of `unchangedCurrentWifi`. */
    val scanResults = guard("scanResults") { it.enabled && !it.unchangedScanResults }
}

private fun decode(values: ConfigValues): WifiHookConfig? {
    if (values.isEmpty()) return null
    return WifiHookConfig(
        enabled = values.boolean("enabled") ?: false,
        unchangedCurrentWifi = values.boolean("unchangedCurrentWifi", "unchanged_current_wifi")
            ?: false,
        currentSsid = values.string("currentSsid", "current_ssid")
            ?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_SSID,
        currentBssid = values.string("currentBssid", "current_bssid")
            ?.takeIf(::isValidMacAddress) ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
        ipAddress = values.string("ipAddress", "ip_address")
            ?.takeIf(WifiConfigPrefs::isValidIpv4) ?: WifiConfigPrefs.DEFAULT_IP_ADDRESS,
        gateway = values.string("gateway")?.takeIf(WifiConfigPrefs::isValidIpv4)
            ?: WifiConfigPrefs.DEFAULT_GATEWAY,
        dns1 = values.string("dns1")?.takeIf(WifiConfigPrefs::isValidIpv4)
            ?: WifiConfigPrefs.DEFAULT_DNS1,
        dns2 = values.string("dns2")?.takeIf(WifiConfigPrefs::isValidIpv4)
            ?: WifiConfigPrefs.DEFAULT_DNS2,
        unchangedScanResults = values.boolean("unchangedScanResults", "unchanged_scan_results")
            ?: false,
        hideScanResults = values.boolean("hideScanResults", "hide_scan_results") ?: false,
        scanResults = decodeScanResults(values),
    )
}

private fun decodeScanResults(values: ConfigValues): List<WifiScanNetwork> {
    // keys are the snapshot's first, then the prefs list, then the pre- list prefs pair.
    val fromList = values.objects("scanResults", "scan_results")
        .map { network ->
            WifiScanNetwork(
                ssid = network.string("ssid") ?: WifiConfigPrefs.DEFAULT_SCAN_SSID,
                bssid = network.string("bssid") ?: WifiConfigPrefs.DEFAULT_SCAN_BSSID,
            )
        }
        .filter { it.ssid.isNotBlank() && isValidMacAddress(it.bssid) }
    if (values.contains("scanResults") || values.contains("scan_results")) return fromList

    val legacySsid = values.string("scan_ssid")
    val legacyBssid = values.string("scan_bssid")
    if (legacySsid == null && legacyBssid == null) return emptyList()
    return listOf(
        WifiScanNetwork(
            ssid = legacySsid ?: WifiConfigPrefs.DEFAULT_SCAN_SSID,
            bssid = legacyBssid?.takeIf(::isValidMacAddress) ?: WifiConfigPrefs.DEFAULT_SCAN_BSSID,
        )
    )
}
