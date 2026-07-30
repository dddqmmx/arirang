package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.VpnStatusConfigSchema
import org.json.JSONArray
import java.util.Date

/**
 * What applications are allowed to learn about VPN and proxy state.
 *
 * Unlike the older stores there is no `migratePrivatePrefsIfNeeded` here: this
 * config is new, so there is no MODE_PRIVATE generation to carry forward.
 *
 * [saveConfig] deliberately does not call [SubmoduleConfigFiles.write] either.
 * Everything here is decided in the Java framework layer, so none of it reaches
 * the native submodule, and `config.json` has a hard 64 KiB ceiling that
 * silently disables the whole native layer when exceeded.
 */
object VpnStatusPrefs {
    const val PREFS_NAME = "vpn_status_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_MODIFIED = "last_modified"
    const val KEY_HIDE_VPN_TRANSPORT = "hide_vpn_transport"
    const val KEY_SPOOFED_TRANSPORT = "spoofed_transport"
    const val KEY_HIDE_VPN_INTERFACES = "hide_vpn_interfaces"
    const val KEY_HIDE_ALWAYS_ON_VPN = "hide_always_on_vpn"
    const val KEY_HIDE_PROXY = "hide_proxy"
    const val KEY_EXEMPT_PACKAGES = "exempt_packages"

    /** What a network should claim to be once its VPN transport is removed. */
    enum class SpoofedTransport {
        /** Report the network with the VPN transport simply dropped. */
        UNCHANGED,
        WIFI,
        CELLULAR,
        ETHERNET
    }

    data class Config(
        val enabled: Boolean = false,
        val hideVpnTransport: Boolean = true,
        val spoofedTransport: SpoofedTransport = SpoofedTransport.WIFI,
        val hideVpnInterfaces: Boolean = true,
        val hideAlwaysOnVpn: Boolean = true,
        val hideProxy: Boolean = false,
        /** Packages that keep seeing the real VPN state. */
        val exemptPackages: Set<String> = emptySet()
    )

    private val DEFAULTS = Config()

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, DEFAULTS.enabled),
            hideVpnTransport = prefs.getBoolean(KEY_HIDE_VPN_TRANSPORT, DEFAULTS.hideVpnTransport),
            spoofedTransport = prefs.getString(KEY_SPOOFED_TRANSPORT, null)
                .toSpoofedTransport(DEFAULTS.spoofedTransport),
            hideVpnInterfaces = prefs.getBoolean(KEY_HIDE_VPN_INTERFACES, DEFAULTS.hideVpnInterfaces),
            hideAlwaysOnVpn = prefs.getBoolean(KEY_HIDE_ALWAYS_ON_VPN, DEFAULTS.hideAlwaysOnVpn),
            hideProxy = prefs.getBoolean(KEY_HIDE_PROXY, DEFAULTS.hideProxy),
            exemptPackages = parsePackages(prefs.getString(KEY_EXEMPT_PACKAGES, null))
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putBoolean(KEY_HIDE_VPN_TRANSPORT, config.hideVpnTransport)
            putString(KEY_SPOOFED_TRANSPORT, config.spoofedTransport.name)
            putBoolean(KEY_HIDE_VPN_INTERFACES, config.hideVpnInterfaces)
            putBoolean(KEY_HIDE_ALWAYS_ON_VPN, config.hideAlwaysOnVpn)
            putBoolean(KEY_HIDE_PROXY, config.hideProxy)
            putString(KEY_EXEMPT_PACKAGES, packagesToJson(config.exemptPackages))
        }
    }

    fun importSchema(context: Context, schema: VpnStatusConfigSchema) {
        require(schema.schemaVersion in 1..VpnStatusConfigSchema.SCHEMA_VERSION) {
            "Unsupported VPN status schema version: ${schema.schemaVersion}"
        }
        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                hideVpnTransport = schema.hideVpnTransport,
                spoofedTransport = schema.spoofedTransport.toSpoofedTransport(DEFAULTS.spoofedTransport),
                hideVpnInterfaces = schema.hideVpnInterfaces,
                hideAlwaysOnVpn = schema.hideAlwaysOnVpn,
                hideProxy = schema.hideProxy,
                exemptPackages = schema.exemptPackages.sanitizedPackages()
            )
        )
    }

    fun lastModified(context: Context): Long = prefs(context).getLong(KEY_LAST_MODIFIED, 0L)

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return VpnStatusConfigSchema(
            enabled = config.enabled,
            hideVpnTransport = config.hideVpnTransport,
            spoofedTransport = config.spoofedTransport.name,
            hideVpnInterfaces = config.hideVpnInterfaces,
            hideAlwaysOnVpn = config.hideAlwaysOnVpn,
            hideProxy = config.hideProxy,
            exemptPackages = config.exemptPackages.sorted(),
            lastModified = lastModified(context)
        ).toJson()
    }

    private fun String?.toSpoofedTransport(fallback: SpoofedTransport): SpoofedTransport =
        runCatching { SpoofedTransport.valueOf(this ?: return fallback) }.getOrDefault(fallback)

    private fun parsePackages(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(json)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { PACKAGE_NAME.matches(it) }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun packagesToJson(packages: Set<String>): String =
        JSONArray(packages.sorted()).toString()

    private fun Collection<String>.sanitizedPackages(): Set<String> = asSequence()
        .map(String::trim)
        .filter(PACKAGE_NAME::matches)
        .distinct()
        .take(MAX_EXEMPT_PACKAGES)
        .toCollection(linkedSetOf())

    internal const val MAX_EXEMPT_PACKAGES = 4_096
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

    private fun prefs(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
