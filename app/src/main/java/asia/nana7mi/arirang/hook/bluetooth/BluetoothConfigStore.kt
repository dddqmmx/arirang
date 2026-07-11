package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.schema.BluetoothConfigSchema
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class BluetoothHookConfig(
    val enabled: Boolean = false,
    val deviceName: String = "Arirang",
    val connectedDevices: List<BluetoothDeviceProfile> = listOf(BluetoothDeviceProfile()),
    val hideConnectedDevices: Boolean = false,
    val hideScanResults: Boolean = false,
    val scanResults: List<BluetoothDeviceProfile> = listOf(
        BluetoothDeviceProfile(name = "Nearby-BT", address = "02:00:00:DD:EE:FF")
    )
)

internal data class BluetoothDeviceProfile(
    val name: String = BluetoothConfigPrefs.DEFAULT_DEVICE_NAME,
    val address: String = BluetoothConfigPrefs.DEFAULT_DEVICE_ADDRESS
)

internal class BluetoothConfigStore {
    private val configFile = HookConfigFile(
        configName = ConfigIds.BLUETOOTH,
        prefsName = BluetoothConfigPrefs.PREFS_NAME,
        defaultValue = BluetoothHookConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.BLUETOOTH,
                force = force,
                allowBind = true,
                logName = "Bluetooth"
            )
        },
        parseRealtimeSnapshot = ::parseSnapshot,
        readStoredConfig = ::readStored
    )

    fun current(): BluetoothHookConfig = configFile.current()

    private fun parseSnapshot(snapshot: String): BluetoothHookConfig? {
        return runCatching {
            val schema = BluetoothConfigSchema.fromJson(snapshot)
            BluetoothHookConfig(
                enabled = schema.enabled,
                deviceName = schema.deviceName
                    .takeIf { it.isNotBlank() } ?: "Arirang",
                connectedDevices = schema.connectedDevices.map { BluetoothDeviceProfile(it.name, it.address) }
                    .filter { it.name.isNotBlank() && isValidBluetoothAddress(it.address) },
                hideConnectedDevices = schema.hideConnectedDevices,
                hideScanResults = schema.hideScanResults,
                scanResults = schema.scanResults.map { BluetoothDeviceProfile(it.name, it.address) }
                    .filter { it.name.isNotBlank() && isValidBluetoothAddress(it.address) }
            )
        }.onFailure {
            HookLog.w(HookLog.Module.BLUETOOTH, "failed to parse Bluetooth config snapshot: ${it.message}")
        }.getOrNull()
    }

    private fun readStored(prefs: XSharedPreferences): BluetoothHookConfig {
        return BluetoothHookConfig(
            enabled = prefs.getBoolean(BluetoothConfigPrefs.KEY_ENABLED, false),
            deviceName = prefs.getString(BluetoothConfigPrefs.KEY_DEVICE_NAME, null)
                ?.takeIf { it.isNotBlank() } ?: "Arirang",
            connectedDevices = parseDevices(
                prefs.getString(BluetoothConfigPrefs.KEY_CONNECTED_DEVICES, null)
            ),
            hideConnectedDevices = prefs.getBoolean(
                BluetoothConfigPrefs.KEY_HIDE_CONNECTED_DEVICES,
                false
            ),
            hideScanResults = prefs.getBoolean(BluetoothConfigPrefs.KEY_HIDE_SCAN_RESULTS, false),
            scanResults = parseDevices(prefs.getString(BluetoothConfigPrefs.KEY_SCAN_RESULTS, null))
        )
    }

    private fun parseDevices(json: String?): List<BluetoothDeviceProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                BluetoothDeviceProfile(
                    name = item.optString("name", BluetoothConfigPrefs.DEFAULT_DEVICE_NAME),
                    address = item.optString("address", BluetoothConfigPrefs.DEFAULT_DEVICE_ADDRESS)
                )
            }
        }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() && isValidBluetoothAddress(it.address) }
    }

    private companion object {
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
    }
}
