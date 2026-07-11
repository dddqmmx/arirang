package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.annotations.SerializedName

data class DeviceInfoConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("presetId") val presetId: String = "",
    @SerializedName("brand") val brand: String = "",
    @SerializedName("manufacturer") val manufacturer: String = "",
    @SerializedName("model") val model: String = "",
    @SerializedName("device") val device: String = "",
    @SerializedName("product") val product: String = "",
    @SerializedName("board") val board: String = "",
    @SerializedName("hardware") val hardware: String = "",
    @SerializedName("display") val display: String = "",
    @SerializedName("host") val host: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("tags") val tags: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("user") val user: String = "",
    @SerializedName("fingerprint") val fingerprint: String = "",
    @SerializedName("time") val time: Long = 0L,
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): DeviceInfoConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            return DeviceInfoConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                presetId = root.get("presetId")?.asString ?: "",
                brand = root.get("brand")?.asString ?: "",
                manufacturer = root.get("manufacturer")?.asString ?: "",
                model = root.get("model")?.asString ?: "",
                device = root.get("device")?.asString ?: "",
                product = root.get("product")?.asString ?: "",
                board = root.get("board")?.asString ?: "",
                hardware = root.get("hardware")?.asString ?: "",
                display = root.get("display")?.asString ?: "",
                host = root.get("host")?.asString ?: "",
                id = root.get("id")?.asString ?: "",
                tags = root.get("tags")?.asString ?: "",
                type = root.get("type")?.asString ?: "",
                user = root.get("user")?.asString ?: "",
                fingerprint = root.get("fingerprint")?.asString ?: "",
                time = root.get("time")?.asLong ?: 0L,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("presetId", presetId)
        obj.addProperty("brand", brand)
        obj.addProperty("manufacturer", manufacturer)
        obj.addProperty("model", model)
        obj.addProperty("device", device)
        obj.addProperty("product", product)
        obj.addProperty("board", board)
        obj.addProperty("hardware", hardware)
        obj.addProperty("display", display)
        obj.addProperty("host", host)
        obj.addProperty("id", id)
        obj.addProperty("tags", tags)
        obj.addProperty("type", type)
        obj.addProperty("user", user)
        obj.addProperty("fingerprint", fingerprint)
        obj.addProperty("time", time)
        return GSON.toJson(obj)
    }
}
