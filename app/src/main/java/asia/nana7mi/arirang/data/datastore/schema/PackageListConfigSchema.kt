package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

data class PackageListConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("defaultMode") val defaultMode: String = "ALL_VISIBLE",
    @SerializedName("defaultTemplateId") val defaultTemplateId: String? = null,
    @SerializedName("templates") val templates: List<PackageListTemplateSchema> = emptyList(),
    @SerializedName("appRules") val appRules: List<PackageListAppRuleSchema> = emptyList(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): PackageListConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val gson = Gson()
            return PackageListConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                defaultMode = root.get("defaultMode")?.asString ?: "ALL_VISIBLE",
                defaultTemplateId = root.get("defaultTemplateId")?.asString,
                templates = root.get("templates")?.let {
                    gson.fromJson(it, object : TypeToken<List<PackageListTemplateSchema>>() {}.type)
                } ?: emptyList(),
                appRules = root.get("appRules")?.let {
                    gson.fromJson(it, object : TypeToken<List<PackageListAppRuleSchema>>() {}.type)
                } ?: emptyList(),
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("defaultMode", defaultMode)
        if (defaultTemplateId != null) {
            obj.addProperty("defaultTemplateId", defaultTemplateId)
        }
        obj.add("templates", GSON.toJsonTree(templates))
        obj.add("appRules", GSON.toJsonTree(appRules))
        return GSON.toJson(obj)
    }
}

data class PackageListTemplateSchema(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("parentId") val parentId: String? = null,
    @SerializedName("visiblePackages") val visiblePackages: List<String> = emptyList(),
    @SerializedName("listMode") val listMode: String = "WHITELIST"
)

data class PackageListAppRuleSchema(
    @SerializedName("packageName") val packageName: String = "",
    @SerializedName("mode") val mode: String = "ALL_VISIBLE",
    @SerializedName("templateId") val templateId: String? = null,
    @SerializedName("visiblePackages") val visiblePackages: List<String> = emptyList()
)
