package asia.nana7mi.arirang.hook.network

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs
import asia.nana7mi.arirang.data.datastore.schema.VpnStatusConfigSchema
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONArray

internal data class VpnHookConfig(
    val enabled: Boolean = false,
    val hideVpnTransport: Boolean = true,
    val spoofedTransport: String = SPOOFED_TRANSPORT_WIFI,
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

internal object VpnHookConfigFile {

    fun create(): HookConfigFile<VpnHookConfig> = HookConfigFile(
        configName = ConfigIds.VPN_STATUS,
        prefsName = VpnStatusPrefs.PREFS_NAME,
        defaultValue = VpnHookConfig(),
        refreshIntervalMs = REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.VPN_STATUS,
                force = force,
                allowBind = true,
                logName = "VPN status"
            )
        },
        parseRealtimeSnapshot = ::parseSnapshot,
        readStoredConfig = ::readStored
    )

    private fun parseSnapshot(snapshot: String): VpnHookConfig? = runCatching {
        val schema = VpnStatusConfigSchema.fromJson(snapshot)
        VpnHookConfig(
            enabled = schema.enabled,
            hideVpnTransport = schema.hideVpnTransport,
            spoofedTransport = schema.spoofedTransport,
            hideAlwaysOnVpn = schema.hideAlwaysOnVpn,
            hideProxy = schema.hideProxy,
            exemptPackages = schema.exemptPackages.toSet()
        )
    }.onFailure {
        HookLog.w(HookLog.Module.CORE, "failed to parse VPN status snapshot: ${it.message}")
    }.getOrNull()

    private fun readStored(prefs: XSharedPreferences): VpnHookConfig {
        val defaults = VpnHookConfig()
        return VpnHookConfig(
            enabled = prefs.getBoolean(VpnStatusPrefs.KEY_ENABLED, defaults.enabled),
            hideVpnTransport = prefs.getBoolean(
                VpnStatusPrefs.KEY_HIDE_VPN_TRANSPORT,
                defaults.hideVpnTransport
            ),
            spoofedTransport = prefs.getString(VpnStatusPrefs.KEY_SPOOFED_TRANSPORT, null)
                ?.takeIf { it.isNotBlank() }
                ?: defaults.spoofedTransport,
            hideAlwaysOnVpn = prefs.getBoolean(
                VpnStatusPrefs.KEY_HIDE_ALWAYS_ON_VPN,
                defaults.hideAlwaysOnVpn
            ),
            hideProxy = prefs.getBoolean(VpnStatusPrefs.KEY_HIDE_PROXY, defaults.hideProxy),
            exemptPackages = prefs.getString(VpnStatusPrefs.KEY_EXEMPT_PACKAGES, null)
                ?.let(::parsePackages)
                .orEmpty()
        )
    }

    private fun parsePackages(json: String): Set<String> = runCatching {
        val array = JSONArray(json)
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    private const val REFRESH_INTERVAL_MS = 1_000L
}
