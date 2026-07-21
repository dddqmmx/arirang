package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WifiChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_wifi_title
    override val navChipId: Int = R.id.navWifiChip

    @SuppressLint("MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_wifi_permission_hint)
            )
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            val values = mutableListOf<String>()

            val connectionInfo = runCatching { wifiManager.connectionInfo }.getOrNull()
            values.add(
                formatWifiInfoProbe(
                    context,
                    context.getString(R.string.self_check_wifi_connection_info),
                    connectionInfo
                )
            )

            val transportInfo = readTransportWifiInfo(connectivityManager)
            values.add(
                formatWifiInfoProbe(
                    context,
                    context.getString(R.string.self_check_wifi_transport_info),
                    transportInfo
                )
            )

            val activeNetworkInfo = readActiveNetworkWifiSummary(connectivityManager)
            values.add(
                formatTextProbe(
                    context,
                    context.getString(R.string.self_check_wifi_active_network),
                    activeNetworkInfo
                )
            )

            @Suppress("DEPRECATION")
            val dhcpInfo = runCatching { wifiManager.dhcpInfo }.getOrNull()
            values.add(
                formatTextProbe(
                    context,
                    context.getString(R.string.self_check_wifi_dhcp_info),
                    formatDhcpInfo(context, dhcpInfo)
                )
            )

            val scans = runCatching { wifiManager.scanResults.orEmpty() }.getOrDefault(emptyList())
            val scanSamples = scans.take(8).joinToString("\n") { result ->
                val ssid = result.SSID.ifBlank { context.getString(R.string.self_check_unknown_name) }
                val bssid = result.BSSID?.takeUnless { it.isBlank() }
                    ?: context.getString(R.string.self_check_unknown_name)
                "$ssid\n$bssid"
            }
            values.add(
                formatTextProbe(
                    context,
                    context.getString(R.string.self_check_wifi_scan_results),
                    scanSamples.takeIf { it.isNotBlank() }
                        ?: context.resources.getQuantityString(R.plurals.self_check_wifi_status, 0, 0)
                )
            )

            val hasWifiData = listOfNotNull(
                connectionInfo?.takeIf { hasVisibleWifiIdentity(it) },
                transportInfo?.takeIf { hasVisibleWifiIdentity(it) },
                activeNetworkInfo,
                formatDhcpInfo(context, dhcpInfo),
                scanSamples.takeIf { it.isNotBlank() }
            ).isNotEmpty()

            Log.d(CheckDefinitions.PHONE_DIAG_TAG, "Wi-Fi check:\n" + values.joinToString("\n---\n"))
            CheckResult(
                if (hasWifiData) CheckState.VISIBLE else CheckState.BLOCKED,
                if (hasWifiData) {
                    context.resources.getQuantityString(R.plurals.self_check_wifi_status, scans.size, scans.size)
                } else {
                    context.getString(R.string.self_check_status_not_visible)
                },
                if (hasWifiData) values.joinToString("\n\n") else context.getString(R.string.self_check_wifi_hidden)
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun readTransportWifiInfo(connectivityManager: ConnectivityManager?): WifiInfo? {
        connectivityManager ?: return null
        return runCatching {
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            capabilities.transportInfo as? WifiInfo
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readActiveNetworkWifiSummary(connectivityManager: ConnectivityManager?): String? {
        connectivityManager ?: return null
        return runCatching {
            val networkInfo = connectivityManager.activeNetworkInfo ?: return null
            if (networkInfo.type != ConnectivityManager.TYPE_WIFI) return null
            listOfNotNull(
                networkInfo.extraInfo?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID },
                networkInfo.subtypeName?.takeUnless { it.isBlank() },
                networkInfo.detailedState?.name
            ).joinToString(" | ").takeUnless { it.isBlank() }
        }.getOrNull()
    }

    private fun formatWifiInfoProbe(context: Context, label: String, wifiInfo: WifiInfo?): String {
        if (wifiInfo == null || !hasVisibleWifiIdentity(wifiInfo)) {
            return "$label\n${context.getString(R.string.self_check_wifi_no_data)}"
        }
        val ssid = wifiInfo.ssid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
            ?: context.getString(R.string.self_check_unknown_name)
        val bssid = wifiInfo.bssid
            ?.takeUnless { it.isBlank() || it.equals(REDACTED_MAC, ignoreCase = true) }
            ?: context.getString(R.string.self_check_unknown_name)
        @Suppress("DEPRECATION")
        val mac = wifiInfo.macAddress
            ?.takeUnless { it.isBlank() || it.equals(REDACTED_MAC, ignoreCase = true) }
        return listOfNotNull(
            label,
            context.getString(R.string.self_check_wifi_current, ssid),
            context.getString(R.string.self_check_wifi_bssid, bssid),
            mac?.let { context.getString(R.string.self_check_wifi_mac, it) }
        ).joinToString("\n")
    }

    private fun formatTextProbe(context: Context, label: String, content: String?): String {
        val body = content?.takeUnless { it.isBlank() } ?: context.getString(R.string.self_check_wifi_no_data)
        return "$label\n$body"
    }

    @Suppress("DEPRECATION")
    private fun formatDhcpInfo(context: Context, dhcpInfo: android.net.DhcpInfo?): String? {
        dhcpInfo ?: return null
        if (dhcpInfo.ipAddress == 0 && dhcpInfo.gateway == 0 && dhcpInfo.serverAddress == 0) return null
        return listOf(
            context.getString(R.string.self_check_wifi_dhcp_ip, intToIp(dhcpInfo.ipAddress)),
            context.getString(R.string.self_check_wifi_dhcp_gateway, intToIp(dhcpInfo.gateway)),
            context.getString(R.string.self_check_wifi_dhcp_server, intToIp(dhcpInfo.serverAddress)),
            context.getString(R.string.self_check_wifi_dhcp_dns, intToIp(dhcpInfo.dns1), intToIp(dhcpInfo.dns2))
        ).joinToString("\n")
    }

    private fun hasVisibleWifiIdentity(wifiInfo: WifiInfo): Boolean {
        val ssid = wifiInfo.ssid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
        val bssid = wifiInfo.bssid
            ?.takeUnless { it.isBlank() || it.equals(REDACTED_MAC, ignoreCase = true) }
        return ssid != null || bssid != null
    }

    private fun intToIp(value: Int): String {
        return listOf(
            value and 0xff,
            value shr 8 and 0xff,
            value shr 16 and 0xff,
            value shr 24 and 0xff
        ).joinToString(".")
    }

    private companion object {
        private const val REDACTED_MAC = "02:00:00:00:00:00"
    }
}
