package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.BaseHookModule

import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

internal class WifiSystemServiceHooks(
    private val serviceHooks: WifiServiceHooks
) {
    private val hookedWifiSystemServiceClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    @Volatile
    private var systemServiceManagerHookInstalled = false

    fun hookWifiSystemServiceManager(classLoader: ClassLoader) {
        synchronized(this) {
            if (systemServiceManagerHookInstalled) return
            systemServiceManagerHookInstalled = true
        }

        val managerClass = BaseHookModule.findClassIfExists(
            "com.android.server.SystemServiceManager",
            classLoader
        )
        if (managerClass == null) {
            HookLog.w(HookLog.Module.WIFI, "SystemServiceManager class not found in $classLoader")
            return
        }

        BaseHookModule.hookAllMethods(managerClass, "startServiceFromJar", afterHookedMethod {
            val className = args.firstOrNull() as? String ?: return@afterHookedMethod
            val jarPath = args.getOrNull(1) as? String
            if (className != WIFI_SYSTEM_SERVICE_CLASS) return@afterHookedMethod

            val wifiService = result ?: run {
                HookLog.w(HookLog.Module.WIFI, "WifiService startServiceFromJar returned null")
                return@afterHookedMethod
            }
            HookLog.i(
                HookLog.Module.WIFI,
                "WifiService started from jar serviceClass=$className jarPath=$jarPath class=${wifiService.javaClass.name} classLoader=${wifiService.javaClass.classLoader}"
            )
            hookWifiSystemServiceClass(wifiService.javaClass)
            serviceHooks.hookWifiServiceInstance(wifiService)
        })
        HookLog.i(HookLog.Module.WIFI, "installed SystemServiceManager.startServiceFromJar watcher for Wi-Fi service")
    }

    private fun hookWifiSystemServiceClass(wifiServiceClass: Class<*>) {
        synchronized(hookedWifiSystemServiceClasses) {
            if (!hookedWifiSystemServiceClasses.add(wifiServiceClass)) return
        }

        wifiServiceClass.declaredMethods
            .filter { it.name == "onStart" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                BaseHookModule.hookMethod(method, afterHookedMethod {
                    HookLog.i(HookLog.Module.WIFI, "WifiService.onStart observed; resolving WifiServiceImpl")
                    serviceHooks.hookWifiServiceInstance(thisObject)
                })
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
