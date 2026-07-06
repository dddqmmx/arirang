package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

data class BluetoothConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("deviceName") val deviceName: String = "Arirang",
    @SerializedName("connectedDevices") val connectedDevices: List<BluetoothDeviceSchema> = emptyList(),
    @SerializedName("hideConnectedDevices") val hideConnectedDevices: Boolean = false,
    @SerializedName("hideScanResults") val hideScanResults: Boolean = false,
    @SerializedName("scanResults") val scanResults: List<BluetoothDeviceSchema> = emptyList(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): BluetoothConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val gson = Gson()
            return BluetoothConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                deviceName = root.get("deviceName")?.asString ?: "",
                connectedDevices = root.get("connectedDevices")?.let {
                    gson.fromJson(it, object : TypeToken<List<BluetoothDeviceSchema>>() {}.type)
                } ?: emptyList(),
                hideConnectedDevices = root.get("hideConnectedDevices")?.asBoolean ?: false,
                hideScanResults = root.get("hideScanResults")?.asBoolean ?: false,
                scanResults = root.get("scanResults")?.let {
                    gson.fromJson(it, object : TypeToken<List<BluetoothDeviceSchema>>() {}.type)
                } ?: emptyList(),
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("deviceName", deviceName)
        obj.add("connectedDevices", GSON.toJsonTree(connectedDevices))
        obj.addProperty("hideConnectedDevices", hideConnectedDevices)
        obj.addProperty("hideScanResults", hideScanResults)
        obj.add("scanResults", GSON.toJsonTree(scanResults))
        return GSON.toJson(obj)
    }
}

data class BluetoothDeviceSchema(
    @SerializedName("name") val name: String = "",
    @SerializedName("address") val address: String = ""
)
