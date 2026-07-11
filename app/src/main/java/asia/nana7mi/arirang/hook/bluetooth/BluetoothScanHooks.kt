package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.HookBridge

import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class BluetoothScanHooks(
    private val currentConfig: () -> BluetoothHookConfig
) {
    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val nextProfile = AtomicInteger()

    fun hookScanController(classLoader: ClassLoader): Boolean {
        val names = listOf(
            SCAN_CONTROLLER_CLASS,
            "com.android.bluetooth.gatt.ScanController",
            "com.android.bluetooth.le_scan.ScanController"
        )
        var anyHooked = false
        names.distinct().forEach { className ->
            val scanControllerClass = HookBridge.findClassIfExists(className, classLoader)
                ?: return@forEach

            if (!hookedClasses.add(scanControllerClass)) {
                anyHooked = true
                return@forEach
            }

            scanControllerClass.declaredMethods.forEach { method ->
                if (method.name == "onScanResult" && method.isKnownControllerScanResult()
                ) {
                    HookBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        if (config.hideScanResults) {
                            result = null
                            return@beforeHookedMethod
                        }
                        if (config.scanResults.isEmpty()) return@beforeHookedMethod
                        val profile = config.scanResults[
                            Math.floorMod(nextProfile.getAndIncrement(), config.scanResults.size)
                        ]
                        args[CONTROLLER_ADDRESS_INDEX] = profile.address
                        args[CONTROLLER_ADV_DATA_INDEX] = advertisingData(profile.name)
                    })
                }

                if ((method.name == "onBatchScanReports" || method.name == "onBatchScanReportsInternal") &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size >= 3
                ) {
                    HookBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled || !config.hideScanResults) return@beforeHookedMethod
                        result = null
                    })
                }
            }
            anyHooked = true
        }
        return anyHooked
    }

    fun hookGattService(classLoader: ClassLoader): Boolean {
        val gattServiceClass = HookBridge.findClassIfExists(GATT_SERVICE_CLASS, classLoader)
            ?: return false

        if (!hookedClasses.add(gattServiceClass)) return true

        gattServiceClass.declaredMethods.forEach { method ->
            if (method.name == "onScanResult" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2
            ) {
                HookBridge.hookMethod(method, beforeHookedMethod {
                    val config = currentConfig()
                    if (!config.enabled || !config.hideScanResults) return@beforeHookedMethod
                    result = null
                })
            }
        }
        return true
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

    private fun advertisingData(name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).take(MAX_LOCAL_NAME_BYTES).toByteArray()
        return byteArrayOf(
            2, 0x01, 0x06,
            (nameBytes.size + 1).toByte(), 0x09
        ) + nameBytes
    }

    private fun java.lang.reflect.Method.isKnownControllerScanResult(): Boolean {
        if (returnType != Void.TYPE || parameterTypes.size != CONTROLLER_SCAN_RESULT_PARAMETER_COUNT) {
            return false
        }
        return parameterTypes[0] == Int::class.javaPrimitiveType &&
            parameterTypes[1] == Int::class.javaPrimitiveType &&
            parameterTypes[CONTROLLER_ADDRESS_INDEX] == String::class.java &&
            (3..8).all { parameterTypes[it] == Int::class.javaPrimitiveType } &&
            parameterTypes[CONTROLLER_ADV_DATA_INDEX] == ByteArray::class.java &&
            parameterTypes[10] == String::class.java
    }

    private companion object {
        private const val MAX_LOCAL_NAME_BYTES = 26
        private const val CONTROLLER_SCAN_RESULT_PARAMETER_COUNT = 11
        private const val CONTROLLER_ADDRESS_INDEX = 2
        private const val CONTROLLER_ADV_DATA_INDEX = 9
    }
}
