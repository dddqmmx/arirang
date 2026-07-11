package asia.nana7mi.arirang.hook.bluetooth

import android.bluetooth.BluetoothDevice
import asia.nana7mi.arirang.hook.core.HookBridge
import java.lang.reflect.Method

// Java type erasure prevents generic inspection, so these helpers are always
// used together with a specific method name check.
internal fun Method.returnsListOfBluetoothDevice(): Boolean {
    return List::class.java.isAssignableFrom(returnType) ||
        Set::class.java.isAssignableFrom(returnType)
}

internal fun Method.returnsBluetoothDeviceCollection(): Boolean {
    val componentType = returnType.componentType
    return (returnType.isArray &&
        componentType != null &&
        BluetoothDevice::class.java.isAssignableFrom(componentType)) ||
        returnsListOfBluetoothDevice()
}

internal fun Method.coerceBluetoothDevices(devices: List<BluetoothDevice>): Any? {
    if (returnType.isArray) {
        val component = returnType.componentType ?: return null
        val array = java.lang.reflect.Array.newInstance(component, devices.size)
        devices.forEachIndexed { index, device -> java.lang.reflect.Array.set(array, index, device) }
        return array
    }
    if (returnType.isAssignableFrom(LinkedHashSet::class.java)) return LinkedHashSet(devices)
    if (returnType.isAssignableFrom(ArrayList::class.java)) return ArrayList(devices)
    return runCatching { HookBridge.newInstance(returnType, devices) }.getOrNull()
}
