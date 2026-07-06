package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.annotations.SerializedName

data class GlobalConfigSchema(
    @SerializedName("restrictHotSwitching") val restrictHotSwitching: Boolean = false,
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): GlobalConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            return GlobalConfigSchema(
                restrictHotSwitching = root.get("restrictHotSwitching")?.asBoolean ?: false,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("restrictHotSwitching", restrictHotSwitching)
        return GSON.toJson(obj)
    }
}
