package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

data class IdentifierConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("androidId") val androidId: String = "",
    @SerializedName("gaid") val gaid: String = "",
    @SerializedName("widevineDrmId") val widevineDrmId: String = "",
    @SerializedName("appSetId") val appSetId: String = "",
    @SerializedName("serial") val serial: String = "",
    @SerializedName("imeiBySlot") val imeiBySlot: Map<Int, String> = emptyMap(),
    @SerializedName("tacBySlot") val tacBySlot: Map<Int, String> = emptyMap(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): IdentifierConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val gson = Gson()
            return IdentifierConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                androidId = root.get("androidId")?.asString ?: "",
                gaid = root.get("gaid")?.asString ?: "",
                widevineDrmId = root.get("widevineDrmId")?.asString ?: "",
                appSetId = root.get("appSetId")?.asString ?: "",
                serial = root.get("serial")?.asString ?: "",
                imeiBySlot = root.get("imeiBySlot")?.let {
                    gson.fromJson(it, object : TypeToken<Map<Int, String>>() {}.type)
                } ?: emptyMap(),
                tacBySlot = root.get("tacBySlot")?.let {
                    gson.fromJson(it, object : TypeToken<Map<Int, String>>() {}.type)
                } ?: emptyMap(),
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("androidId", androidId)
        obj.addProperty("gaid", gaid)
        obj.addProperty("widevineDrmId", widevineDrmId)
        obj.addProperty("appSetId", appSetId)
        obj.addProperty("serial", serial)
        obj.add("imeiBySlot", GSON.toJsonTree(imeiBySlot))
        obj.add("tacBySlot", GSON.toJsonTree(tacBySlot))
        return GSON.toJson(obj)
    }
}
