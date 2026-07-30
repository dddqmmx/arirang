package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.JsonArray
import com.google.gson.annotations.SerializedName

data class VpnStatusConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("hideVpnTransport") val hideVpnTransport: Boolean = true,
    /**
     * `VpnStatusPrefs.SpoofedTransport` name. Kept as a string so an unknown
     * value from a newer build decodes rather than throwing; `VpnStatusPrefs`
     * maps anything unrecognised back to its own default, so this literal
     * drifting out of step with that default cannot misbehave silently.
     */
    @SerializedName("spoofedTransport") val spoofedTransport: String = "WIFI",
    @SerializedName("hideVpnInterfaces") val hideVpnInterfaces: Boolean = true,
    @SerializedName("hideAlwaysOnVpn") val hideAlwaysOnVpn: Boolean = true,
    @SerializedName("hideProxy") val hideProxy: Boolean = false,
    @SerializedName("exemptPackages") val exemptPackages: List<String> = emptyList(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Declared defaults, used as the decode fallback for absent fields so the
         * two cannot drift apart. schemaVersion/lastModified are excluded
         * deliberately: an absent schemaVersion must stay 0 ("unversioned") so
         * ManagedConfig still rejects legacy payloads.
         */
        private val DEFAULTS = VpnStatusConfigSchema()

        fun fromJson(json: String): VpnStatusConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val exempt = root.get("exemptPackages")?.asJsonArray?.mapNotNull {
                runCatching { it.asString }.getOrNull()
            } ?: DEFAULTS.exemptPackages
            return VpnStatusConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: DEFAULTS.enabled,
                hideVpnTransport = root.get("hideVpnTransport")?.asBoolean ?: DEFAULTS.hideVpnTransport,
                spoofedTransport = root.get("spoofedTransport")?.asString ?: DEFAULTS.spoofedTransport,
                hideVpnInterfaces = root.get("hideVpnInterfaces")?.asBoolean ?: DEFAULTS.hideVpnInterfaces,
                hideAlwaysOnVpn = root.get("hideAlwaysOnVpn")?.asBoolean ?: DEFAULTS.hideAlwaysOnVpn,
                hideProxy = root.get("hideProxy")?.asBoolean ?: DEFAULTS.hideProxy,
                exemptPackages = exempt,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("hideVpnTransport", hideVpnTransport)
        obj.addProperty("spoofedTransport", spoofedTransport)
        obj.addProperty("hideVpnInterfaces", hideVpnInterfaces)
        obj.addProperty("hideAlwaysOnVpn", hideAlwaysOnVpn)
        obj.addProperty("hideProxy", hideProxy)
        val array = JsonArray()
        exemptPackages.forEach(array::add)
        obj.add("exemptPackages", array)
        return GSON.toJson(obj)
    }
}
