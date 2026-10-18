package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.afterHookedMethod
import java.util.Collections
import java.util.WeakHashMap
import asia.nana7mi.arirang.hook.target.Targets

internal class WifiSystemServiceHooks(
    private val serviceHooks: WifiServiceHooks
) {
    private val hookedWifiSystemServiceClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    /**
     * `WifiService` is started from a jar by `SystemServiceManager`, so the started instance is
     * the way to its class — it is not resolvable from the loader handed to `onHook` yet.
     */
    fun hookWifiSystemServiceManager(classLoader: ClassLoader) {
        Targets.onSystemServiceStarted(WIFI_SYSTEM_SERVICE_CLASS, classLoader) { wifiService ->
            HookLog.i(
                HookLog.Module.WIFI,
                "WifiService started class=${wifiService.javaClass.name} classLoader=${wifiService.javaClass.classLoader}"
            )
            hookWifiSystemServiceClass(wifiService.javaClass)
            serviceHooks.hookWifiServiceInstance(wifiService)
        }
    }

    private fun hookWifiSystemServiceClass(wifiServiceClass: Class<*>) {
        synchronized(hookedWifiSystemServiceClasses) {
            if (!hookedWifiSystemServiceClasses.add(wifiServiceClass)) return
        }

        wifiServiceClass.declaredMethods
            .filter { it.name == "onStart" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                HookBridge.hookMethod(method, afterHookedMethod {
                    HookLog.i(HookLog.Module.WIFI, "WifiService.onStart observed; resolving WifiServiceImpl")
                    val target = thisObject ?: return@afterHookedMethod
                    serviceHooks.hookWifiServiceInstance(target)
                })
            }
    }

}
