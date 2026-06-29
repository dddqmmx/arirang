package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.BaseHookModule

import android.bluetooth.BluetoothDevice
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

internal class BluetoothAdapterHooks(
    private val currentConfig: () -> BluetoothHookConfig
) {
    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    @Volatile
    private var spoofedDeviceMap: Map<String, BluetoothDeviceProfile> = emptyMap()

    fun hookAdapterService(classLoader: ClassLoader): Boolean {
        val serviceClass = BaseHookModule.findClassIfExists(
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
                    BaseHookModule.hookMethod(method, afterHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        result = SPOOFED_LOCAL_MAC
                    })
                }

                method.name == "getBondedDevices" &&
                    method.returnsListOfBluetoothDevice() -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getBondedDevices()")
                    BaseHookModule.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        if (config.hideConnectedDevices) {
                            result = emptyList<BluetoothDevice>()
                            return@beforeHookedMethod
                        }
                        refreshSpoofedDeviceMap(config)
                        result = config.connectedDevices.mapNotNull { createFakeBluetoothDevice(it) }
                    })
                }

                method.name == "getRemoteName" &&
                    method.parameterTypes.size == 1 &&
                    BluetoothDevice::class.java.isAssignableFrom(method.parameterTypes[0]) -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getRemoteName()")
                    BaseHookModule.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        val device = args[0] as? BluetoothDevice ?: return@beforeHookedMethod
                        val address = runCatching { device.address }.getOrNull() ?: return@beforeHookedMethod
                        val spoofed = spoofedDeviceMap[address] ?: return@beforeHookedMethod
                        result = spoofed.name
                    })
                }
            }
        }
        return true
    }

    fun hookAdapterProperties(classLoader: ClassLoader): Boolean {
        val propertiesClass = BaseHookModule.findClassIfExists(
            ADAPTER_PROPERTIES_CLASS,
            classLoader
        ) ?: BaseHookModule.findClassIfExists(
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
                    BaseHookModule.hookMethod(method, afterHookedMethod {
                        val config = currentConfig()
                        val original = result as? String
                        if (!config.enabled) {
                            HookLog.i(
                                HookLog.Module.BLUETOOTH,
                                "AdapterProperties.getName() hit but config.enabled=false (original=$original)"
                            )
                            return@afterHookedMethod
                        }
                        result = config.deviceName
                        HookLog.i(
                            HookLog.Module.BLUETOOTH,
                            "AdapterProperties.getName(): $original -> ${config.deviceName}"
                        )
                    })
                }

                method.name == "getAddress" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == ByteArray::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking ${propertiesClass.name}.getAddress()")
                    BaseHookModule.hookMethod(method, afterHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        result = macToBytes(SPOOFED_LOCAL_MAC)
                    })
                }

                method.name == "getBondedDevices" && method.returnsBluetoothDeviceCollection() -> {
                    BaseHookModule.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        if (config.hideConnectedDevices) {
                            result = if (method.returnType.isArray) {
                                java.lang.reflect.Array.newInstance(BluetoothDevice::class.java, 0)
                            } else {
                                emptyList<BluetoothDevice>()
                            }
                            return@beforeHookedMethod
                        }
                        refreshSpoofedDeviceMap(config)
                        val devices = config.connectedDevices.mapNotNull { createFakeBluetoothDevice(it) }
                        if (method.returnType.isArray) {
                            val array = java.lang.reflect.Array.newInstance(BluetoothDevice::class.java, devices.size)
                            devices.forEachIndexed { index, device ->
                                java.lang.reflect.Array.set(array, index, device)
                            }
                            result = array
                        } else {
                            result = devices
                        }
                    })
                }
            }
        }
        return true
    }

    private fun refreshSpoofedDeviceMap(config: BluetoothHookConfig) {
        spoofedDeviceMap = config.connectedDevices.associateBy { it.address }
            .ifEmpty { emptyMap() }
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
