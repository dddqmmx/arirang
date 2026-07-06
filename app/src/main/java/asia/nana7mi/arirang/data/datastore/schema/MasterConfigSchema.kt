package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class MasterConfigSchema(
    @SerializedName("version") override val lastModified: Long = 0L,
    @SerializedName("schemaVersion") override val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("enabled") val enabled: Boolean = true,

    @SerializedName("globalConfigVersion") val globalConfigVersion: Long = 0L,
    @SerializedName("globalConfigSnapshot") val globalConfigSnapshot: String = "",

    @SerializedName("deviceInfoEnabled") val deviceInfoEnabled: Boolean = false,
    @SerializedName("devicePresetId") val devicePresetId: String = "",
    @SerializedName("buildBrand") val buildBrand: String = "",
    @SerializedName("buildManufacturer") val buildManufacturer: String = "",
    @SerializedName("buildModel") val buildModel: String = "",
    @SerializedName("buildDevice") val buildDevice: String = "",
    @SerializedName("buildProduct") val buildProduct: String = "",
    @SerializedName("buildBoard") val buildBoard: String = "",
    @SerializedName("buildHardware") val buildHardware: String = "",
    @SerializedName("buildDisplay") val buildDisplay: String = "",
    @SerializedName("buildHost") val buildHost: String = "",
    @SerializedName("buildId") val buildId: String = "",
    @SerializedName("buildTags") val buildTags: String = "",
    @SerializedName("buildType") val buildType: String = "",
    @SerializedName("buildUser") val buildUser: String = "",
    @SerializedName("buildFingerprint") val buildFingerprint: String = "",
    @SerializedName("buildTime") val buildTime: Long = 0L,

    @SerializedName("uniqueIdentifierEnabled") val uniqueIdentifierEnabled: Boolean = false,
    @SerializedName("androidId") val androidId: String = "",
    @SerializedName("gaid") val gaid: String = "",
    @SerializedName("widevineDrmId") val widevineDrmId: String = "",
    @SerializedName("appSetId") val appSetId: String = "",
    @SerializedName("serial") val serial: String = "",
    @SerializedName("imeiBySlot") val imeiBySlot: Map<Int, String> = emptyMap(),
    @SerializedName("tacBySlot") val tacBySlot: Map<Int, String> = emptyMap(),
    @SerializedName("uniqueIdentifierConfigVersion") val uniqueIdentifierConfigVersion: Long = 0L,
    @SerializedName("uniqueIdentifierConfigSnapshot") val uniqueIdentifierConfigSnapshot: String = "",

    @SerializedName("gsmSimOperatorIsoCountry") val gsmSimOperatorIsoCountry: String = "",
    @SerializedName("gsmOperatorIsoCountry") val gsmOperatorIsoCountry: String = "",
    @SerializedName("gsmSimOperatorNumeric") val gsmSimOperatorNumeric: String = "",
    @SerializedName("gsmOperatorNumeric") val gsmOperatorNumeric: String = "",
    @SerializedName("gsmSimOperatorAlpha") val gsmSimOperatorAlpha: String = "",
    @SerializedName("gsmOperatorAlpha") val gsmOperatorAlpha: String = "",
    @SerializedName("simConfigVersion") val simConfigVersion: Long = 0L,
    @SerializedName("simConfigSnapshot") val simConfigSnapshot: String = "",

    @SerializedName("hookLogConfigVersion") val hookLogConfigVersion: Long = 0L,
    @SerializedName("hookLogConfigSnapshot") val hookLogConfigSnapshot: String = "",

    @SerializedName("wifiConfigVersion") val wifiConfigVersion: Long = 0L,
    @SerializedName("wifiConfigSnapshot") val wifiConfigSnapshot: String = "",

    @SerializedName("bluetoothConfigVersion") val bluetoothConfigVersion: Long = 0L,
    @SerializedName("bluetoothConfigSnapshot") val bluetoothConfigSnapshot: String = "",

    @SerializedName("locationConfigVersion") val locationConfigVersion: Long = 0L,
    @SerializedName("locationConfigSnapshot") val locationConfigSnapshot: String = "",

    @SerializedName("packageListConfigVersion") val packageListConfigVersion: Long = 0L,
    @SerializedName("packageListConfigSnapshot") val packageListConfigSnapshot: String = "",

    @SerializedName("sensorConfigEnabled") val sensorConfigEnabled: Boolean = false,
    @SerializedName("sensorHideAll") val sensorHideAll: Boolean = false,
    @SerializedName("sensorGlobalVendorReplacement") val sensorGlobalVendorReplacement: String = "",
    @SerializedName("sensorVendorKeywords") val sensorVendorKeywords: List<String> = emptyList(),
    @SerializedName("sensorBlacklist") val sensorBlacklist: List<SensorBlacklistEntrySchema> = emptyList(),
    @SerializedName("sensorOverrides") val sensorOverrides: List<SensorOverrideEntrySchema> = emptyList(),
    @SerializedName("sensorInjections") val sensorInjections: List<SensorInjectionEntrySchema> = emptyList(),
    @SerializedName("sensorPrecisionRules") val sensorPrecisionRules: List<SensorPrecisionRuleSchema> = emptyList(),
    @SerializedName("sensorConfigVersion") val sensorConfigVersion: Long = 0L,
    @SerializedName("sensorConfigSnapshot") val sensorConfigSnapshot: String = ""
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun toJson(schema: MasterConfigSchema): String {
            return GSON.toJson(schema)
        }
    }

    override fun toJson(): String = GSON.toJson(this)
}

data class SensorBlacklistEntrySchema(
    @SerializedName("type") val type: Int = 0,
    @SerializedName("nameContains") val nameContains: String = "",
    @SerializedName("vendorContains") val vendorContains: String = ""
)

data class SensorOverrideEntrySchema(
    @SerializedName("matchType") val matchType: Int = 0,
    @SerializedName("matchNameContains") val matchNameContains: String = "",
    @SerializedName("matchVendorContains") val matchVendorContains: String = "",
    @SerializedName("newName") val newName: String = "",
    @SerializedName("newVendor") val newVendor: String = "",
    @SerializedName("newType") val newType: Int = 0,
    @SerializedName("newHandle") val newHandle: Int = 0
)

data class SensorInjectionEntrySchema(
    @SerializedName("name") val name: String = "",
    @SerializedName("vendor") val vendor: String = "",
    @SerializedName("type") val type: Int = 0,
    @SerializedName("handle") val handle: Int = 0
)

data class SensorPrecisionRuleSchema(
    @SerializedName("type") val type: Int = 0,
    @SerializedName("level") val level: Int = 0
)
