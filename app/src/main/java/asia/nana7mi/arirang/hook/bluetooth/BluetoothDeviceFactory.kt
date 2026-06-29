package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.BaseHookModule

import android.bluetooth.BluetoothDevice

/**
 * Creates a [BluetoothDevice] with a spoofed address.
 *
 * AOSP [BluetoothDevice] has a package-private `BluetoothDevice(String)`
 * constructor on Android 12-16. On builds where the constructor signature
 * differs, fall back to `BluetoothDevice(String, int)`.
 */
internal fun createFakeBluetoothDevice(device: BluetoothDeviceProfile): BluetoothDevice? {
    val bt = runCatching {
        BaseHookModule.newInstance(BluetoothDevice::class.java, device.address)
    }.getOrNull() ?: runCatching {
        BaseHookModule.newInstance(
            BluetoothDevice::class.java,
            device.address,
            0 // ADDRESS_TYPE_PUBLIC
        )
    }.getOrNull() ?: return null

    runCatching { BaseHookModule.setObjectField(bt, "mName", device.name) }
    runCatching { BaseHookModule.setObjectField(bt, "mAlias", device.name) }

    return bt as BluetoothDevice?
}

internal fun macToBytes(mac: String): ByteArray {
    val bytes = ByteArray(6)
    val hex = mac.replace(":", "")
    if (hex.length != 12) return bytes
    for (i in 0 until 6) {
        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}
