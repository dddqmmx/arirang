package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class SystemSettingConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    /** Olson id, or empty to leave the real time zone alone. */
    @SerializedName("timeZoneId") val timeZoneId: String = "",
    /** BCP-47 tag, or empty to leave the real locale alone. */
    @SerializedName("languageTag") val languageTag: String = "",
    @SerializedName("perPackage") val perPackage: Map<String, SystemSettingOverrideSchema> = emptyMap(),
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
        private val DEFAULTS = SystemSettingConfigSchema()
        private val OVERRIDE_DEFAULTS = SystemSettingOverrideSchema()

        fun fromJson(json: String): SystemSettingConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val perPackage = mutableMapOf<String, SystemSettingOverrideSchema>()
            root.get("perPackage")?.asJsonObject?.entrySet()?.forEach { (packageName, value) ->
                val entry = value.asJsonObject
                perPackage[packageName] = SystemSettingOverrideSchema(
                    enabled = entry.get("enabled")?.asBoolean ?: OVERRIDE_DEFAULTS.enabled,
                    timeZoneId = entry.get("timeZoneId")?.asString ?: OVERRIDE_DEFAULTS.timeZoneId,
                    languageTag = entry.get("languageTag")?.asString ?: OVERRIDE_DEFAULTS.languageTag
                )
            }
            return SystemSettingConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: DEFAULTS.enabled,
                timeZoneId = root.get("timeZoneId")?.asString ?: DEFAULTS.timeZoneId,
                languageTag = root.get("languageTag")?.asString ?: DEFAULTS.languageTag,
                perPackage = perPackage,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("timeZoneId", timeZoneId)
        obj.addProperty("languageTag", languageTag)
        val perPackageObj = JsonObject()
        for ((packageName, override) in perPackage) {
            val entry = JsonObject()
            entry.addProperty("enabled", override.enabled)
            entry.addProperty("timeZoneId", override.timeZoneId)
            entry.addProperty("languageTag", override.languageTag)
            perPackageObj.add(packageName, entry)
        }
        obj.add("perPackage", perPackageObj)
        return GSON.toJson(obj)
    }
}

data class SystemSettingOverrideSchema(
    @SerializedName("enabled") val enabled: Boolean = true,
    /** Empty means this package follows [SystemSettingConfigSchema.timeZoneId]. */
    @SerializedName("timeZoneId") val timeZoneId: String = "",
    /** Empty means this package follows [SystemSettingConfigSchema.languageTag]. */
    @SerializedName("languageTag") val languageTag: String = ""
)
