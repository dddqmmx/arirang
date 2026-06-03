package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object BluetoothConfigPrefs {
    const val PREFS_NAME = "bluetooth_config_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_MODIFIED = "last_modified"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_CONNECTED_DEVICES = "connected_devices"
    const val KEY_HIDE_CONNECTED_DEVICES = "hide_connected_devices"
    const val KEY_HIDE_SCAN_RESULTS = "hide_scan_results"
    const val KEY_SCAN_RESULTS = "scan_results"

    const val DEFAULT_DEVICE_NAME = "Arirang-BT"
    const val DEFAULT_DEVICE_ADDRESS = "02:00:00:AA:BB:CC"

    private val gson = Gson()

    data class Device(
        val name: String = DEFAULT_DEVICE_NAME,
        val address: String = DEFAULT_DEVICE_ADDRESS
    )

    data class Config(
        val enabled: Boolean = false,
        val deviceName: String = "Arirang",
        val connectedDevices: List<Device> = listOf(Device()),
        val hideConnectedDevices: Boolean = false,
        val hideScanResults: Boolean = false,
        val scanResults: List<Device> = listOf(Device(name = "Nearby-BT", address = "02:00:00:DD:EE:FF"))
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context)
        val connectedDevices = parseDevices(prefs.getString(KEY_CONNECTED_DEVICES, null))
        val scanResults = parseDevices(prefs.getString(KEY_SCAN_RESULTS, null))
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            deviceName = prefs.getString(KEY_DEVICE_NAME, "Arirang") ?: "Arirang",
            connectedDevices = connectedDevices,
            hideConnectedDevices = prefs.getBoolean(KEY_HIDE_CONNECTED_DEVICES, false),
            hideScanResults = prefs.getBoolean(KEY_HIDE_SCAN_RESULTS, false),
            scanResults = scanResults
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_DEVICE_NAME, config.deviceName)
            putString(KEY_CONNECTED_DEVICES, gson.toJson(config.connectedDevices))
            putBoolean(KEY_HIDE_CONNECTED_DEVICES, config.hideConnectedDevices)
            putBoolean(KEY_HIDE_SCAN_RESULTS, config.hideScanResults)
            putString(KEY_SCAN_RESULTS, gson.toJson(config.scanResults))
        }
        SubmoduleConfigFiles.write(context)
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        val connectedDevices = JSONArray().apply {
            config.connectedDevices.forEach { device ->
                put(
                    JSONObject()
                        .put("name", device.name)
                        .put("address", device.address)
                )
            }
        }
        val scanResults = JSONArray().apply {
            config.scanResults.forEach { device ->
                put(
                    JSONObject()
                        .put("name", device.name)
                        .put("address", device.address)
                )
            }
        }
        return JSONObject()
            .put(KEY_ENABLED, config.enabled)
            .put(KEY_LAST_MODIFIED, lastModified(context))
            .put(KEY_DEVICE_NAME, config.deviceName)
            .put(KEY_CONNECTED_DEVICES, connectedDevices)
            .put(KEY_HIDE_CONNECTED_DEVICES, config.hideConnectedDevices)
            .put(KEY_HIDE_SCAN_RESULTS, config.hideScanResults)
            .put(KEY_SCAN_RESULTS, scanResults)
            .toString()
    }

    fun defaultDevice(index: Int = 0, isNearby: Boolean = false): Device {
        val namePrefix = if (isNearby) "Nearby-BT" else "Arirang-BT"
        if (index == 0) return Device(name = namePrefix)
        val suffix = index.coerceIn(1, 99)
        return Device(
            name = "$namePrefix-$suffix",
            address = "02:00:00:%02x:%02x:%02x".format(if (isNearby) 0xDD else 0xAA, 0xBB, suffix)
        )
    }

    private fun parseDevices(json: String?): List<Device> {
        if (json.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<Device>>() {}.type
        return runCatching { gson.fromJson<List<Device>>(json, type) }
            .getOrNull()
            .orEmpty()
            .filter { it.name.isNotBlank() || it.address.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
}
