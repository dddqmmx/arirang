package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.annotations.SerializedName

data class ClipboardConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("defaultPolicy") val defaultPolicy: ClipboardPolicySchema = ClipboardPolicySchema.ALLOW,
    @SerializedName("appFilter") val appFilter: ClipboardAppFilterSchema = ClipboardAppFilterSchema.ALL,
    @SerializedName("appPolicies") val appPolicies: List<ClipboardAppPolicySchema> = emptyList(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): ClipboardConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val policies = root.get("appPolicies")?.asJsonArray?.map { element ->
                val item = element.asJsonObject
                ClipboardAppPolicySchema(
                    userId = item.get("userId")?.asInt
                        ?: throw IllegalArgumentException("Clipboard policy is missing userId"),
                    packageName = item.get("packageName")?.asString
                        ?: throw IllegalArgumentException("Clipboard policy is missing packageName"),
                    policy = ClipboardPolicySchema.valueOf(
                        item.get("policy")?.asString
                            ?: throw IllegalArgumentException("Clipboard policy is missing policy")
                    )
                )
            }.orEmpty()
            return ClipboardConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                defaultPolicy = ClipboardPolicySchema.valueOf(
                    root.get("defaultPolicy")?.asString ?: ClipboardPolicySchema.ALLOW.name
                ),
                appFilter = ClipboardAppFilterSchema.valueOf(
                    root.get("appFilter")?.asString ?: ClipboardAppFilterSchema.ALL.name
                ),
                appPolicies = policies,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("defaultPolicy", defaultPolicy.name)
        obj.addProperty("appFilter", appFilter.name)
        obj.add("appPolicies", GSON.toJsonTree(appPolicies))
        return GSON.toJson(obj)
    }
}

enum class ClipboardPolicySchema {
    ALLOW,
    DENY,
    ASK
}

enum class ClipboardAppFilterSchema {
    ALL,
    USER,
    SYSTEM
}

data class ClipboardAppPolicySchema(
    @SerializedName("userId") val userId: Int,
    @SerializedName("packageName") val packageName: String,
    @SerializedName("policy") val policy: ClipboardPolicySchema
)
