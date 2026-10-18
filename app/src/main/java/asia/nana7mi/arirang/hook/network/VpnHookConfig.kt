package asia.nana7mi.arirang.hook.network

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.xposed.HookLog

internal data class VpnHookConfig(
    val enabled: Boolean = false,
    val hideVpnTransport: Boolean = true,
    val spoofedTransport: String = SPOOFED_TRANSPORT_WIFI,
    val hideVpnInterfaces: Boolean = true,
    val hideAlwaysOnVpn: Boolean = true,
    val hideProxy: Boolean = false,
    /** Packages that keep seeing the real state. */
    val exemptPackages: Set<String> = emptySet()
) {
    /**
     * Whether this config should act for a caller owning [callerPackages].
     *
     * A shared UID is exempt if *any* of its packages is listed: the packages
     * share one process, so there is no way to serve them different answers, and
     * an explicit exemption is the stronger signal of the two.
     */
    fun appliesTo(callerPackages: List<String>): Boolean =
        enabled && callerPackages.none { it in exemptPackages }

    companion object {
        const val SPOOFED_TRANSPORT_UNCHANGED = "UNCHANGED"
        const val SPOOFED_TRANSPORT_WIFI = "WIFI"
        const val SPOOFED_TRANSPORT_CELLULAR = "CELLULAR"
        const val SPOOFED_TRANSPORT_ETHERNET = "ETHERNET"
    }
}

private const val REFRESH_INTERVAL_MS = 1_000L

/**
 * The VPN/proxy config, read by both the VPN module and the settings-provider module.
 *
 * They each used to stand up their own reader of it in the same process, with independent
 * refresh clocks, so the two could act on two different values of one config.
 *
 * One decoder serves both sources: the snapshot says `hideVpnTransport`/`exemptPackages` and the
 * prefs file says `hide_vpn_transport`/`exempt_packages`.
 */
internal object VpnConfig : ConfigSpec<VpnHookConfig>(
    id = ConfigIds.VPN_STATUS,
    typeName = "VPN status",
    logModule = HookLog.Module.CORE,
    fallback = VpnHookConfig(),
    sources = listOf(
        ServiceSnapshotSource(ConfigIds.VPN_STATUS, logName = "VPN status"),
        RemotePrefsSource(VpnStatusPrefs.PREFS_NAME),
    ),
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
) {
    /** The module is switched on. Whether it governs *this* caller is the site's own question. */
    val enabled = guard("enabled") { it.enabled }

    /** The transport surfaces: the reported network type must stop saying VPN. */
    val vpnTransport = guard("vpnTransport") { it.enabled && it.hideVpnTransport }

    /** The proxy surfaces. A separate switch from the transport one, and a separate site. */
    val proxy = guard("proxy") { it.enabled && it.hideProxy }
}

private fun decode(values: ConfigValues): VpnHookConfig? {
    if (values.isEmpty()) return null
    val defaults = VpnHookConfig()
    return VpnHookConfig(
        enabled = values.boolean("enabled") ?: defaults.enabled,
        hideVpnTransport = values.boolean("hideVpnTransport", "hide_vpn_transport")
            ?: defaults.hideVpnTransport,
        spoofedTransport = values.string("spoofedTransport", "spoofed_transport")
            ?: defaults.spoofedTransport,
        hideVpnInterfaces = values.boolean("hideVpnInterfaces", "hide_vpn_interfaces")
            ?: defaults.hideVpnInterfaces,
        hideAlwaysOnVpn = values.boolean("hideAlwaysOnVpn", "hide_always_on_vpn")
            ?: defaults.hideAlwaysOnVpn,
        hideProxy = values.boolean("hideProxy", "hide_proxy") ?: defaults.hideProxy,
        // The snapshot carries a JSON array, prefs a set; strings() reads either.
        exemptPackages = values.strings("exemptPackages", "exempt_packages").toSet(),
    )
}
