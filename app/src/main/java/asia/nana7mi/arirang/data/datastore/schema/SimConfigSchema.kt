package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

data class SimConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("hideSim") val hideSim: Boolean = false,
    @SerializedName("simProfiles") val simProfiles: List<SimProfileSchema> = emptyList(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): SimConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val gson = Gson()
            return SimConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                hideSim = root.get("hideSim")?.asBoolean ?: false,
                simProfiles = root.get("simProfiles")?.let { arr ->
                    gson.fromJson(arr, object : TypeToken<List<SimProfileSchema>>() {}.type)
                } ?: emptyList(),
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("hideSim", hideSim)
        obj.add("simProfiles", GSON.toJsonTree(simProfiles))
        return GSON.toJson(obj)
    }
}

data class SimProfileSchema(
    @SerializedName("id") val id: Int = -1,
    @SerializedName("iccId") val iccId: String = "",
    @SerializedName("simSlotIndex") val simSlotIndex: Int = 0,
    @SerializedName("displayName") val displayName: String = "",
    @SerializedName("carrierName") val carrierName: String = "",
    @SerializedName("countryIso") val countryIso: String = "",
    @SerializedName("mcc") val mcc: String = "",
    @SerializedName("mnc") val mnc: String = "",
    @SerializedName("imei") val imei: String = "",
    @SerializedName("number") val number: String = "",
    @SerializedName("cardId") val cardId: Int = 0,
    @SerializedName("cardString") val cardString: String = "",
    @SerializedName("nameSource") val nameSource: Int = 0,
    @SerializedName("iconTint") val iconTint: Int = 0,
    @SerializedName("roaming") val roaming: Int = 0,
    @SerializedName("isEmbedded") val isEmbedded: Boolean = false,
    @SerializedName("isOpportunistic") val isOpportunistic: Boolean = false,
    @SerializedName("isGroupDisabled") val isGroupDisabled: Boolean = false,
    @SerializedName("profileClass") val profileClass: Int = -1,
    @SerializedName("subType") val subType: Int = 0,
    @SerializedName("groupOwner") val groupOwner: String = "",
    @SerializedName("areUiccApplicationsEnabled") val areUiccApplicationsEnabled: Boolean = true,
    @SerializedName("portIndex") val portIndex: Int = 0,
    @SerializedName("usageSetting") val usageSetting: Int = 0,
    @SerializedName("carrierId") val carrierId: Int = -1
)
