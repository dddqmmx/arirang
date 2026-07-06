package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.annotations.SerializedName

data class SensorConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("hideAll") val hideAll: Boolean = false,
    @SerializedName("blacklistSize") val blacklistSize: Int = 0,
    @SerializedName("injectionSize") val injectionSize: Int = 0,
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): SensorConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            return SensorConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                hideAll = root.get("hideAll")?.asBoolean ?: false,
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
        obj.addProperty("blacklistSize", blacklistSize)
        obj.addProperty("injectionSize", injectionSize)
        return GSON.toJson(obj)
    }
}
