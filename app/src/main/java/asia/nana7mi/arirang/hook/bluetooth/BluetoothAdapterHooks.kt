package asia.nana7mi.arirang.hook.bluetooth

import android.bluetooth.BluetoothDevice
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.util.macToBytes
import java.util.Collections
import java.util.WeakHashMap

internal class BluetoothAdapterHooks {
    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    fun hookAdapterService(classLoader: ClassLoader): Boolean {
        val serviceClass = HookBridge.findClassIfExists(
            ADAPTER_SERVICE_CLASS,
            classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.BLUETOOTH, "Class not found: $ADAPTER_SERVICE_CLASS")
            return false
        }

        if (!hookedClasses.add(serviceClass)) return true

        HookLog.i(HookLog.Module.BLUETOOTH, "Found $ADAPTER_SERVICE_CLASS, hooking methods...")

        serviceClass.declaredMethods.forEach { method ->
            when {
                method.name == "getAddress" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getAddress()")
                    HookBridge.hookMethod(method, BluetoothConfig.enabled.after {
                        if (result == null) return@after
                        result = SPOOFED_LOCAL_MAC
                    })
                }

                method.name == "getBondedDevices" &&
                    method.returnsListOfBluetoothDevice() -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getBondedDevices()")
                    HookBridge.hookMethod(method, BluetoothConfig.connectedDevices.after { config ->
                        val devices = if (config.hideConnectedDevices) {
                            emptyList()
                        } else {
                            if (!containsBluetoothDevices(result)) return@after
                            createFakeBluetoothDevices(config.connectedDevices) ?: return@after
                        }
                        method.coerceBluetoothDevices(devices)?.let { result = it }
                    })
                }

                method.name == "getRemoteName" &&
                    method.parameterTypes.size == 1 &&
                    BluetoothDevice::class.java.isAssignableFrom(method.parameterTypes[0]) -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getRemoteName()")
                    HookBridge.hookMethod(method, BluetoothConfig.connectedDeviceNames.after { config ->
                        if (result == null) return@after
                        val device = args[0] as? BluetoothDevice ?: return@after
                        val address = runCatching { device.address }.getOrNull() ?: return@after
                        val spoofed = config.connectedDevices.firstOrNull {
                            it.address.equals(address, ignoreCase = true)
                        } ?: return@after
                        result = spoofed.name
                    })
                }
            }
        }
        return true
    }

    fun hookAdapterProperties(classLoader: ClassLoader): Boolean {
        val propertiesClass = HookBridge.findClassIfExists(
            ADAPTER_PROPERTIES_CLASS,
            classLoader
        ) ?: HookBridge.findClassIfExists(
            "com.android.bluetooth.btservice.AdapterProperties",
            classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.BLUETOOTH, "Class not found: $ADAPTER_PROPERTIES_CLASS")
            return false
        }

        if (!hookedClasses.add(propertiesClass)) return true

        HookLog.i(HookLog.Module.BLUETOOTH, "Found ${propertiesClass.name}, hooking methods...")
        propertiesClass.declaredMethods.forEach { method ->
            when {
                method.name == "getName" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking ${propertiesClass.name}.getName()")
                    HookBridge.hookMethod(method, BluetoothConfig.deviceName.after { config ->
                        val original = result as? String
                        result = config.deviceName
                        HookLog.d(
                            HookLog.Module.BLUETOOTH,
                            "AdapterProperties.getName(): $original -> ${config.deviceName}"
                        )
                    })
                }

                method.name == "getAddress" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == ByteArray::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking ${propertiesClass.name}.getAddress()")
                    HookBridge.hookMethod(method, BluetoothConfig.enabled.after {
                        result = macToBytes(SPOOFED_LOCAL_MAC)
                    })
                }

                method.name == "getBondedDevices" && method.returnsBluetoothDeviceCollection() -> {
                    HookBridge.hookMethod(method, BluetoothConfig.connectedDevices.after { config ->
                        val devices = if (config.hideConnectedDevices) {
                            emptyList()
                        } else {
                            createFakeBluetoothDevices(config.connectedDevices) ?: return@after
                        }
                        method.coerceBluetoothDevices(devices)?.let { result = it }
                    })
                }
            }
        }
        return true
    }

    private fun createFakeBluetoothDevices(profiles: List<BluetoothDeviceProfile>): List<BluetoothDevice>? {
        val devices = profiles.map(::createFakeBluetoothDevice)
        if (devices.any { it == null }) return null
        return devices.filterNotNull()
    }

    private fun containsBluetoothDevices(value: Any?): Boolean {
        return when {
            value == null -> false
            value.javaClass.isArray -> java.lang.reflect.Array.getLength(value) > 0
            value is Collection<*> -> value.isNotEmpty()
            else -> false
        }
    }


}
