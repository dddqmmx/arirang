package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.util.isValidMacAddress
import asia.nana7mi.arirang.hook.xposed.HookLog

internal data class BluetoothHookConfig(
    val enabled: Boolean = false,
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val unchangedConnectedDevices: Boolean = false,
    val connectedDevices: List<BluetoothDeviceProfile> = listOf(BluetoothDeviceProfile()),
    val hideConnectedDevices: Boolean = false,
    val unchangedScanResults: Boolean = false,
    val hideScanResults: Boolean = false,
    val scanResults: List<BluetoothDeviceProfile> = listOf(
        BluetoothDeviceProfile(name = "Nearby-BT", address = "02:00:00:DD:EE:FF")
    )
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "Arirang"
    }
}

internal data class BluetoothDeviceProfile(
    val name: String = BluetoothConfigPrefs.DEFAULT_DEVICE_NAME,
    val address: String = BluetoothConfigPrefs.DEFAULT_DEVICE_ADDRESS
)

private const val REFRESH_INTERVAL_MS = 300L

/**
 * The Bluetooth config, read by the Bluetooth module and — for the device name alone — by the
 * settings-provider and system-server modules, which now ask this store for that field instead of
 * standing up a second reader of the whole config in the same process.
 *
 * One decoder serves both sources: the snapshot says `deviceName`/`connectedDevices` and prefs
 * says `device_name`/`connected_devices`. The nested device objects agree on `name`/`address`
 * (both writers use `@SerializedName` for exactly that reason). The old pair of decoders also
 * filtered unusable device entries identically — that rule now exists once, which is why it can
 * no longer drift.
 */
internal object BluetoothConfig : ConfigSpec<BluetoothHookConfig>(
    id = ConfigIds.BLUETOOTH,
    typeName = "Bluetooth",
    logModule = HookLog.Module.BLUETOOTH,
    fallback = BluetoothHookConfig(),
    sources = listOf(
        ServiceSnapshotSource(ConfigIds.BLUETOOTH, logName = "Bluetooth"),
        RemotePrefsSource(BluetoothConfigPrefs.PREFS_NAME),
    ),
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
) {
    /** Any Bluetooth spoofing at all, for the surfaces that report a fixed value. */
    val enabled = guard("enabled") { it.enabled }

    /** The device name, for the fallback paths that only rename this device. */
    val deviceName = guard("deviceName") { it.enabled && it.deviceName.isNotBlank() }

    /** The bonded-device list, which `unchangedConnectedDevices` can freeze. */
    val connectedDevices = guard("connectedDevices") {
        it.enabled && !it.unchangedConnectedDevices
    }

    /**
     * Name lookups for the devices the bonded list reports.
     *
     * Narrower than [connectedDevices] on purpose: when the bonded devices are being hidden there
     * is no fake address for a name to correspond to, so renaming one would contradict the list.
     * The old code expressed this by writing the scan-and-hide pair inline, in the opposite
     * polarity to the scan-results equivalent three methods away.
     */
    val connectedDeviceNames = guard("connectedDeviceNames") {
        it.enabled && !it.unchangedConnectedDevices && !it.hideConnectedDevices
    }

    /** The scan-result surfaces, which `unchangedScanResults` can freeze. */
    val scanResults = guard("scanResults") { it.enabled && !it.unchangedScanResults }

    /**
     * The scan surfaces that only act while results are being *hidden*.
     *
     * Dropping a scan callback entirely is only right when the point is to hide it; when the
     * point is to substitute other networks, the callback has to run. Distinct from
     * [scanResults] on purpose — the two used to differ only by where `hideScanResults` appeared
     * in the condition.
     */
    val scanResultsOnlyWhenHidden = guard("scanResultsOnlyWhenHidden") {
        it.enabled && !it.unchangedScanResults && it.hideScanResults
    }
    /**
     * The device name to report, or null while the feature is off.
     *
     * For the readers that need only this — the system-server Bluetooth fallback and the settings
     * provider's two surfaces — each of which used to hold its own reader of the whole Bluetooth
     * config, with its own refresh clock, to answer it.
     */
    val spoofedDeviceName = field<String?>("spoofedDeviceName", default = null) { values ->
        values.boolean("enabled")?.takeIf { it }?.let {
            values.string("deviceName", "device_name")?.takeIf(String::isNotBlank)
        }
    }
}

private fun decode(values: ConfigValues): BluetoothHookConfig? {
    if (values.isEmpty()) return null
    return BluetoothHookConfig(
        enabled = values.boolean("enabled") ?: false,
        deviceName = values.string("deviceName", "device_name")
            ?.takeIf { it.isNotBlank() } ?: BluetoothHookConfig.DEFAULT_DEVICE_NAME,
        unchangedConnectedDevices = values.boolean(
            "unchangedConnectedDevices", "unchanged_connected_devices"
        ) ?: false,
        connectedDevices = decodeDevices(values, "connectedDevices", "connected_devices"),
        hideConnectedDevices = values.boolean("hideConnectedDevices", "hide_connected_devices") ?: false,
        unchangedScanResults = values.boolean("unchangedScanResults", "unchanged_scan_results") ?: false,
        hideScanResults = values.boolean("hideScanResults", "hide_scan_results") ?: false,
        scanResults = decodeDevices(values, "scanResults", "scan_results"),
    )
}

private fun decodeDevices(
    values: ConfigValues,
    vararg keys: String,
): List<BluetoothDeviceProfile> = values.objects(*keys)
    .map { device ->
        BluetoothDeviceProfile(
            name = device.string("name") ?: BluetoothConfigPrefs.DEFAULT_DEVICE_NAME,
            address = device.string("address") ?: BluetoothConfigPrefs.DEFAULT_DEVICE_ADDRESS,
        )
    }
    .filter { it.name.isNotBlank() && isValidMacAddress(it.address) }
