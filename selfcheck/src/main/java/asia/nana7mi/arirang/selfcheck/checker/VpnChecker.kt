package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class VpnChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_vpn_title
    override val navChipId: Int = R.id.navVpnChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            
            val vpnNetworks = connectivityManager?.allNetworks.orEmpty().filter { network ->
                val caps = connectivityManager?.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                        (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            }

            val activeNetwork = connectivityManager?.activeNetwork
            val activeCaps = connectivityManager?.getNetworkCapabilities(activeNetwork)
            val isActiveVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                    (activeCaps != null && !activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))

            val vpnInterfaces = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && VPN_INTERFACE_PREFIXES.any { prefix -> it.name.startsWith(prefix, ignoreCase = true) } }
            }.getOrDefault(emptyList())

            val alwaysOnPackage = runCatching {
                Settings.Secure.getString(context.contentResolver, ALWAYS_ON_VPN_KEY)
            }.getOrNull()?.takeUnless { it.isBlank() }

            val systemProxy = runCatching {
                System.getProperty("http.proxyHost")?.takeUnless { it.isBlank() }?.let { host ->
                    val port = System.getProperty("http.proxyPort")
                    "$host:$port"
                }
            }.getOrNull()

            val isVpnDetected = vpnNetworks.isNotEmpty() || isActiveVpn || vpnInterfaces.isNotEmpty() || alwaysOnPackage != null

            if (!isVpnDetected) {
                CheckResult(
                    CheckState.BLOCKED,
                    context.getString(R.string.self_check_status_not_visible),
                    context.getString(R.string.self_check_vpn_hidden)
                )
            } else {
                val details = buildList {
                    add("=== Summary ===")
                    if (isActiveVpn) add("• [Active] Current default network is VPN")
                    if (alwaysOnPackage != null) add("• Always-on VPN: $alwaysOnPackage")
                    if (systemProxy != null) add("• System HTTP Proxy: $systemProxy")
                    add("")

                    vpnNetworks.forEach { network ->
                        val caps = connectivityManager?.getNetworkCapabilities(network)
                        val lp = connectivityManager?.getLinkProperties(network)
                        add(formatNetworkDetails(network, caps, lp))
                        add("")
                    }

                    if (vpnInterfaces.isNotEmpty()) {
                        add("=== Virtual Interfaces ===")
                        vpnInterfaces.forEach { ni ->
                            add(formatInterfaceDetails(ni))
                            add("")
                        }
                    }
                }
                CheckResult(
                    CheckState.VISIBLE,
                    context.getString(R.string.self_check_status_visible),
                    details.joinToString("\n").trim()
                )
            }
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun formatNetworkDetails(network: Network, caps: NetworkCapabilities?, lp: LinkProperties?): String {
        return buildString {
            appendLine("[Network $network]")
            appendLine("  Interface: ${lp?.interfaceName ?: "Unknown"}")
            
            // Capabilities
            if (caps != null) {
                val flags = buildList {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("INTERNET")
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("NOT_METERED")
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) add("NOT_VPN") else add("VPN")
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("VALIDATED")
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)) add("TRUSTED")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) add("FOREGROUND")
                    }
                }
                appendLine("  Capabilities: ${flags.joinToString(", ")}")
                
                val down = caps.linkDownstreamBandwidthKbps
                val up = caps.linkUpstreamBandwidthKbps
                if (down > 0 || up > 0) {
                    appendLine("  Bandwidth: ↓${down}kbps / ↑${up}kbps")
                }

                // Use reflection for potentially hidden or SDK-sensitive fields
                runCatching {
                    val method = caps.javaClass.getMethod("getUnderlyingNetworks")
                    val underlying = method.invoke(caps)
                    val resultList = when (underlying) {
                        is List<*> -> underlying
                        is Array<*> -> underlying.toList()
                        else -> null
                    }
                    if (!resultList.isNullOrEmpty()) {
                        appendLine("  Underlying: ${resultList.joinToString()}")
                    }
                }

                runCatching {
                    val info = caps.transportInfo
                    if (info != null && info.javaClass.name.contains("VpnTransportInfo")) {
                        val typeField = info.javaClass.getDeclaredField("type")
                        typeField.isAccessible = true
                        appendLine("  VPN Type: ${getVpnTypeName(typeField.get(info) as Int)}")
                    }
                }
            }

            // Link Properties
            if (lp != null) {
                lp.mtu.takeIf { it > 0 }?.let { appendLine("  MTU: $it") }
                
                val ips = lp.linkAddresses.map { it.address.hostAddress }.filterNotNull()
                if (ips.isNotEmpty()) appendLine("  IPs: ${ips.joinToString(", ")}")

                val dns = lp.dnsServers.map { it.hostAddress }.filterNotNull()
                if (dns.isNotEmpty()) appendLine("  DNS: ${dns.joinToString(", ")}")

                lp.domains?.takeUnless { it.isBlank() }?.let { appendLine("  Domains: $it") }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        val method = lp.javaClass.getMethod("getDhcpServerAddress")
                        val addr = method.invoke(lp) as? java.net.InetAddress
                        addr?.hostAddress?.let { appendLine("  DHCP Srv: $it") }
                    }
                }

                val routes = lp.routes.map { it.toString() }
                if (routes.isNotEmpty()) {
                    appendLine("  Routes (${routes.size}):")
                    routes.take(5).forEach { appendLine("    $it") }
                    if (routes.size > 5) appendLine("    ... and ${routes.size - 5} more")
                }

                lp.httpProxy?.let {
                    appendLine("  Proxy: ${it.host}:${it.port}")
                }
            }
        }.trimEnd()
    }

    private fun formatInterfaceDetails(ni: NetworkInterface): String {
        return buildString {
            appendLine("[Interface ${ni.name}]")
            val displayName = ni.displayName
            if (displayName != ni.name) appendLine("  Display: $displayName")
            
            val flags = buildList {
                if (ni.isUp) add("UP")
                if (ni.isLoopback) add("LOOPBACK")
                if (ni.isPointToPoint) add("P2P")
                if (ni.supportsMulticast()) add("MULTICAST")
                if (ni.isVirtual) add("VIRTUAL")
            }
            appendLine("  Flags: ${flags.joinToString(", ")}")
            
            ni.hardwareAddress?.let { addr ->
                val hex = addr.joinToString(":") { "%02X".format(it) }
                if (hex != "00:00:00:00:00:00") appendLine("  HW Addr: $hex")
            }

            val addresses = ni.inetAddresses.asSequence().map { it.hostAddress }.filterNotNull().toList()
            if (addresses.isNotEmpty()) appendLine("  Addrs: ${addresses.joinToString(", ")}")
            
            appendLine("  Index: ${ni.index} | MTU: ${ni.mtu}")
        }.trimEnd()
    }

    private fun getVpnTypeName(type: Int): String = when (type) {
        1 -> "IKEV2"
        2 -> "IPSEC_USER_PASS"
        3 -> "IPSEC_PSK"
        4 -> "IPSEC_RSA"
        else -> "TYPE_$type"
    }

    private companion object {
        private const val ALWAYS_ON_VPN_KEY = "always_on_vpn_app"
        private val VPN_INTERFACE_PREFIXES = listOf("tun", "ppp", "wg", "utun", "ipsec", "tap")
    }
}
