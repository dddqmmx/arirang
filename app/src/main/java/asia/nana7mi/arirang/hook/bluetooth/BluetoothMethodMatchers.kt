package asia.nana7mi.arirang.hook.bluetooth

import android.bluetooth.BluetoothDevice
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
