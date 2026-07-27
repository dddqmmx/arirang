package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

data class WifiConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("currentSsid") val currentSsid: String = "114514",
    @SerializedName("currentBssid") val currentBssid: String = "02:00:00:11:45:14",
    @SerializedName("ipAddress") val ipAddress: String = "192.168.1.100",
    @SerializedName("gateway") val gateway: String = "192.168.1.1",
    @SerializedName("dns1") val dns1: String = "192.168.1.1",
    @SerializedName("dns2") val dns2: String = "8.8.8.8",
    @SerializedName("hideScanResults") val hideScanResults: Boolean = false,
    @SerializedName("scanResults") val scanResults: List<WifiScanNetworkSchema> = emptyList(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 2

        /**
         * Declared defaults, used as the decode fallback for absent fields.
         *
         * Referencing this rather than repeating literals keeps the two in step:
         * they had drifted for all six string fields, so a snapshot missing e.g.
         * `dns2` decoded to "" and then failed ConfigRegistry's IPV4 check.
         *
         * schemaVersion and lastModified are deliberately excluded — an absent
         * schemaVersion must stay 0 ("unversioned") so ManagedConfig's version
         * check still rejects legacy payloads instead of silently accepting them.
         */
        private val DEFAULTS = WifiConfigSchema()

        fun fromJson(json: String): WifiConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val gson = Gson()
            return WifiConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: DEFAULTS.enabled,
                currentSsid = root.get("currentSsid")?.asString ?: DEFAULTS.currentSsid,
                currentBssid = root.get("currentBssid")?.asString ?: DEFAULTS.currentBssid,
                ipAddress = root.get("ipAddress")?.asString ?: DEFAULTS.ipAddress,
                gateway = root.get("gateway")?.asString ?: DEFAULTS.gateway,
                dns1 = root.get("dns1")?.asString ?: DEFAULTS.dns1,
                dns2 = root.get("dns2")?.asString ?: DEFAULTS.dns2,
                hideScanResults = root.get("hideScanResults")?.asBoolean ?: DEFAULTS.hideScanResults,
                scanResults = root.get("scanResults")?.let {
                    gson.fromJson(it, object : TypeToken<List<WifiScanNetworkSchema>>() {}.type)
                } ?: emptyList(),
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("currentSsid", currentSsid)
        obj.addProperty("currentBssid", currentBssid)
        obj.addProperty("ipAddress", ipAddress)
        obj.addProperty("gateway", gateway)
        obj.addProperty("dns1", dns1)
        obj.addProperty("dns2", dns2)
        obj.addProperty("hideScanResults", hideScanResults)
        obj.add("scanResults", GSON.toJsonTree(scanResults))
        return GSON.toJson(obj)
    }
}

data class WifiScanNetworkSchema(
    @SerializedName("ssid") val ssid: String = "",
    @SerializedName("bssid") val bssid: String = ""
)
