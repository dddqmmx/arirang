package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.annotations.SerializedName

data class SensorConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("hideAll") val hideAll: Boolean = false,
    @SerializedName("precisionBySensorType") val precisionBySensorType: Map<Int, Int> = emptyMap(),
    @SerializedName("sensorEntries") val sensorEntries: List<SensorEntrySchema> = emptyList(),
    @SerializedName("vendorReplacement") val vendorReplacement: String = "",
    @SerializedName("vendorKeywords") val vendorKeywords: String = "",
    @SerializedName("blacklistSize") val blacklistSize: Int = 0,
    @SerializedName("injectionSize") val injectionSize: Int = 0,
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 2

        fun fromJson(json: String): SensorConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            return SensorConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                hideAll = root.get("hideAll")?.asBoolean ?: false,
                precisionBySensorType = root.get("precisionBySensorType")?.let {
                    GSON.fromJson(it, object : com.google.gson.reflect.TypeToken<Map<Int, Int>>() {}.type)
                } ?: emptyMap(),
                sensorEntries = root.get("sensorEntries")?.let {
                    GSON.fromJson(it, object : com.google.gson.reflect.TypeToken<List<SensorEntrySchema>>() {}.type)
                } ?: emptyList(),
                vendorReplacement = root.get("vendorReplacement")?.asString ?: "",
                vendorKeywords = root.get("vendorKeywords")?.asString ?: "",
                blacklistSize = root.get("blacklistSize")?.asInt ?: 0,
                injectionSize = root.get("injectionSize")?.asInt ?: 0,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("hideAll", hideAll)
        obj.add("precisionBySensorType", GSON.toJsonTree(precisionBySensorType))
        obj.add("sensorEntries", GSON.toJsonTree(sensorEntries))
        obj.addProperty("vendorReplacement", vendorReplacement)
        obj.addProperty("vendorKeywords", vendorKeywords)
        obj.addProperty("blacklistSize", blacklistSize)
        obj.addProperty("injectionSize", injectionSize)
        return GSON.toJson(obj)
    }
}

data class SensorEntrySchema(
    @SerializedName("name") val name: String = "",
    @SerializedName("vendor") val vendor: String = "",
    @SerializedName("type") val type: Int = 0,
    @SerializedName("hidden") val hidden: Boolean = false,
    @SerializedName("isCustom") val isCustom: Boolean = false,
    @SerializedName("id") val id: String = ""
)
