package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.HookBridge

import android.bluetooth.BluetoothDevice
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

internal class BluetoothAdapterHooks(
    private val currentConfig: () -> BluetoothHookConfig
) {
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
                    HookBridge.hookMethod(method, afterHookedMethod {
                        if (hasThrowable()) return@afterHookedMethod
                        if (result == null) return@afterHookedMethod
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        result = SPOOFED_LOCAL_MAC
                    })
                }

                method.name == "getBondedDevices" &&
                    method.returnsListOfBluetoothDevice() -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getBondedDevices()")
                    HookBridge.hookMethod(method, afterHookedMethod {
                        if (hasThrowable()) return@afterHookedMethod
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        val devices = if (config.hideConnectedDevices) {
                            emptyList()
                        } else {
                            if (!containsBluetoothDevices(result)) return@afterHookedMethod
                            createFakeBluetoothDevices(config.connectedDevices) ?: return@afterHookedMethod
                        }
                        method.coerceBluetoothDevices(devices)?.let { result = it }
                    })
                }

                method.name == "getRemoteName" &&
                    method.parameterTypes.size == 1 &&
                    BluetoothDevice::class.java.isAssignableFrom(method.parameterTypes[0]) -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getRemoteName()")
                    HookBridge.hookMethod(method, afterHookedMethod {
                        if (hasThrowable()) return@afterHookedMethod
                        if (result == null) return@afterHookedMethod
                        val config = currentConfig()
                        if (!config.enabled || config.hideConnectedDevices) return@afterHookedMethod
                        val device = args[0] as? BluetoothDevice ?: return@afterHookedMethod
                        val address = runCatching { device.address }.getOrNull() ?: return@afterHookedMethod
                        val spoofed = config.connectedDevices.firstOrNull {
                            it.address.equals(address, ignoreCase = true)
                        } ?: return@afterHookedMethod
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
                    HookBridge.hookMethod(method, afterHookedMethod {
                        if (hasThrowable()) return@afterHookedMethod
                        val config = currentConfig()
                        val original = result as? String
                        if (!config.enabled) {
                            HookLog.d(
                                HookLog.Module.BLUETOOTH,
                                "AdapterProperties.getName() hit but config.enabled=false (original=$original)"
                            )
                            return@afterHookedMethod
                        }
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
                    HookBridge.hookMethod(method, afterHookedMethod {
                        if (hasThrowable()) return@afterHookedMethod
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        result = macToBytes(SPOOFED_LOCAL_MAC)
                    })
                }

                method.name == "getBondedDevices" && method.returnsBluetoothDeviceCollection() -> {
                    HookBridge.hookMethod(method, afterHookedMethod {
                        if (hasThrowable()) return@afterHookedMethod
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        val devices = if (config.hideConnectedDevices) {
                            emptyList()
                        } else {
                            createFakeBluetoothDevices(config.connectedDevices) ?: return@afterHookedMethod
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

    private fun beforeHookedMethod(
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    private fun afterHookedMethod(
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }
}
