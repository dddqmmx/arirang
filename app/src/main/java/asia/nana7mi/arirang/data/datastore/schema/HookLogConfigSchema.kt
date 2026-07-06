package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class HookLogConfigSchema(
    @SerializedName("core") val core: Boolean = true,
    @SerializedName("clipboard") val clipboard: Boolean = true,
    @SerializedName("gms") val gms: Boolean = true,
    @SerializedName("location") val location: Boolean = true,
    @SerializedName("packageList") val packageList: Boolean = true,
    @SerializedName("settings") val settings: Boolean = true,
    @SerializedName("sim") val sim: Boolean = true,
    @SerializedName("wifi") val wifi: Boolean = true,
    @SerializedName("bluetooth") val bluetooth: Boolean = true,
    @SerializedName("uniqueId") val uniqueId: Boolean = true,
    @SerializedName("notify") val notify: Boolean = true,
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): HookLogConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            return HookLogConfigSchema(
                core = root.get("core")?.asBoolean ?: true,
                clipboard = root.get("clipboard")?.asBoolean ?: true,
                gms = root.get("gms")?.asBoolean ?: true,
                location = root.get("location")?.asBoolean ?: true,
                packageList = root.get("packageList")?.asBoolean ?: true,
                settings = root.get("settings")?.asBoolean ?: true,
                sim = root.get("sim")?.asBoolean ?: true,
                wifi = root.get("wifi")?.asBoolean ?: true,
                bluetooth = root.get("bluetooth")?.asBoolean ?: true,
                uniqueId = root.get("uniqueId")?.asBoolean ?: true,
                notify = root.get("notify")?.asBoolean ?: true,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("core", core)
        obj.addProperty("clipboard", clipboard)
        obj.addProperty("gms", gms)
        obj.addProperty("location", location)
        obj.addProperty("packageList", packageList)
        obj.addProperty("settings", settings)
        obj.addProperty("sim", sim)
        obj.addProperty("wifi", wifi)
        obj.addProperty("bluetooth", bluetooth)
        obj.addProperty("uniqueId", uniqueId)
        obj.addProperty("notify", notify)
        return GSON.toJson(obj)
    }
}
