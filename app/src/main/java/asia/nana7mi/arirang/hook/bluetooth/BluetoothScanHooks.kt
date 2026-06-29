package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.BaseHookModule

import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

internal class BluetoothScanHooks(
    private val currentConfig: () -> BluetoothHookConfig
) {
    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    fun hookScanController(classLoader: ClassLoader): Boolean {
        val names = listOf(
            SCAN_CONTROLLER_CLASS,
            "com.android.bluetooth.gatt.ScanController",
            "com.android.bluetooth.le_scan.ScanController"
        )
        var anyHooked = false
        names.distinct().forEach { className ->
            val scanControllerClass = BaseHookModule.findClassIfExists(className, classLoader)
                ?: return@forEach

            if (!hookedClasses.add(scanControllerClass)) {
                anyHooked = true
                return@forEach
            }

            scanControllerClass.declaredMethods.forEach { method ->
                if ((method.name == "onScanResult" || method.name == "onScanResultInternal") &&
                    method.parameterTypes.size >= 5
                ) {
                    BaseHookModule.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled || !config.hideScanResults) return@beforeHookedMethod
                        result = null
                    })
                }

                if ((method.name == "onBatchScanReports" || method.name == "onBatchScanReportsInternal") &&
                    method.parameterTypes.size >= 3
                ) {
                    BaseHookModule.hookMethod(method, beforeHookedMethod {
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
        val gattServiceClass = BaseHookModule.findClassIfExists(GATT_SERVICE_CLASS, classLoader)
            ?: return false

        if (!hookedClasses.add(gattServiceClass)) return true

        gattServiceClass.declaredMethods.forEach { method ->
            if (method.name == "onScanResult" && method.parameterTypes.size == 2) {
                BaseHookModule.hookMethod(method, beforeHookedMethod {
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
}
