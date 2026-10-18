package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.xposed.HookBridge

import android.bluetooth.BluetoothDevice
import asia.nana7mi.arirang.hook.util.isValidMacAddress

/**
 * Creates a [BluetoothDevice] with a spoofed address.
 *
 * AOSP [BluetoothDevice] has a package-private `BluetoothDevice(String)`
 * constructor on Android 12-16. On builds where the constructor signature
 * differs, fall back to `BluetoothDevice(String, int)`.
 */
internal fun createFakeBluetoothDevice(device: BluetoothDeviceProfile): BluetoothDevice? {
    if (!isValidMacAddress(device.address)) return null
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


