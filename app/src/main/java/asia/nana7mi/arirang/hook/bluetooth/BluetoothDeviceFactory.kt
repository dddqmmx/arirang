package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.HookBridge

import android.bluetooth.BluetoothDevice

/**
 * Creates a [BluetoothDevice] with a spoofed address.
 *
 * AOSP [BluetoothDevice] has a package-private `BluetoothDevice(String)`
 * constructor on Android 12-16. On builds where the constructor signature
 * differs, fall back to `BluetoothDevice(String, int)`.
 */
internal fun createFakeBluetoothDevice(device: BluetoothDeviceProfile): BluetoothDevice? {
    if (!isValidBluetoothAddress(device.address)) return null
    val bt = runCatching {
        HookBridge.newInstance(BluetoothDevice::class.java, device.address)
    }.getOrNull() ?: runCatching {
        HookBridge.newInstance(
            BluetoothDevice::class.java,
            device.address,
            0 // ADDRESS_TYPE_PUBLIC
        )
    }.getOrNull() ?: return null

    runCatching { HookBridge.setObjectField(bt, "mName", device.name) }
    runCatching { HookBridge.setObjectField(bt, "mAlias", device.name) }

    return bt as BluetoothDevice?
}

internal fun macToBytes(mac: String): ByteArray {
    require(isValidBluetoothAddress(mac)) { "Invalid Bluetooth address" }
    val bytes = ByteArray(6)
    val hex = mac.replace(":", "")
    if (hex.length != 12) return bytes
    for (i in 0 until 6) {
        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

internal fun isValidBluetoothAddress(value: String): Boolean = BLUETOOTH_ADDRESS.matches(value)

private val BLUETOOTH_ADDRESS = Regex("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
