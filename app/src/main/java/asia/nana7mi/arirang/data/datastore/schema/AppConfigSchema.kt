package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.annotations.SerializedName

data class AppConfigSchema(
    @SerializedName("setupCompleted") val setupCompleted: Boolean = false,
    @SerializedName("language") val language: String = "system",
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {
    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): AppConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            return AppConfigSchema(
                setupCompleted = root.get("setupCompleted")?.asBoolean ?: false,
                language = root.get("language")?.asString ?: "system",
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val root = baseJson()
        root.addProperty("setupCompleted", setupCompleted)
        root.addProperty("language", language)
        return GSON.toJson(root)
    }
}
