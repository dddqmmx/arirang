package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.HookBridge

import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

internal class WifiServiceHooks(
    private val currentConfig: () -> WifiHookConfig
) {
    private val hookedServiceClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedScanRequestProxyClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    fun hookWifiService(classLoader: ClassLoader) {
        val serviceClasses = WIFI_SERVICE_CLASS_NAMES.mapNotNull { className ->
            HookBridge.findClassIfExists(className, classLoader).also { foundClass ->
                HookLog.d(
                    HookLog.Module.WIFI,
                    "lookup $className in $classLoader -> ${foundClass?.name ?: "null"}"
                )
            }
        }.distinct()

        if (serviceClasses.isEmpty()) {
            HookLog.w(
                HookLog.Module.WIFI,
                "Wi-Fi service class not found in $classLoader; waiting for SystemServiceManager.startServiceFromJar"
            )
            return
        }

        serviceClasses.forEach { hookWifiServiceClass(classLoader, it) }
    }

    fun hookWifiServiceInstance(wifiService: Any) {
        val impl = runCatching {
            HookBridge.getObjectField(wifiService, "mImpl")
        }.getOrNull() ?: wifiService.javaClass.declaredFields
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(wifiService)?.takeIf { it.javaClass.name == WIFI_SERVICE_IMPL_CLASS }
                }.getOrNull()
            }

        if (impl == null) {
            HookLog.w(
                HookLog.Module.WIFI,
                "WifiService mImpl not found fields=${wifiService.javaClass.declaredFields.joinToString { "${it.type.name} ${it.name}" }}"
            )
            return
        }

        HookLog.i(
            HookLog.Module.WIFI,
            "found WifiServiceImpl instance class=${impl.javaClass.name} classLoader=${impl.javaClass.classLoader}"
        )
        hookWifiServiceClass(impl.javaClass.classLoader ?: wifiService.javaClass.classLoader, impl.javaClass)
        hookScanRequestProxyFromWifiServiceImpl(impl)
    }

    private fun hookScanRequestProxyFromWifiServiceImpl(wifiServiceImpl: Any) {
        val scanRequestProxy = runCatching {
            HookBridge.getObjectField(wifiServiceImpl, "mScanRequestProxy")
        }.getOrNull()

        if (scanRequestProxy == null) {
            HookLog.w(
                HookLog.Module.WIFI,
                "WifiServiceImpl mScanRequestProxy not found fields=${wifiServiceImpl.javaClass.declaredFields.joinToString { "${it.type.name} ${it.name}" }}"
            )
            return
        }

        HookLog.i(
            HookLog.Module.WIFI,
            "found ScanRequestProxy instance class=${scanRequestProxy.javaClass.name} classLoader=${scanRequestProxy.javaClass.classLoader}"
        )
        hookScanRequestProxyClass(scanRequestProxy.javaClass)
    }

    private fun hookScanRequestProxyClass(scanRequestProxyClass: Class<*>) {
        synchronized(hookedScanRequestProxyClasses) {
            if (!hookedScanRequestProxyClasses.add(scanRequestProxyClass)) {
                HookLog.d(HookLog.Module.WIFI, "ScanRequestProxy class already hooked: ${scanRequestProxyClass.name}")
                return
            }
        }

        val candidateMethods = scanRequestProxyClass.declaredMethods
            .filter { it.name == "getScanResults" }
        HookLog.i(
            HookLog.Module.WIFI,
            "ScanRequestProxy ${scanRequestProxyClass.name} candidates=${candidateMethods.joinToString { it.signature() }}"
        )

        var hookedScanResults = 0
        candidateMethods
            .filter { it.returnsList() }
            .forEach { method ->
                HookBridge.hookMethod(method, beforeHookedMethod {
                    val config = currentConfig()
                    if (!config.enabled) return@beforeHookedMethod
                    HookLog.i(HookLog.Module.WIFI, "spoof ScanRequestProxy.getScanResults via ${method.signature()}")
                    result = spoofedScanResults(config)
                })
                hookedScanResults++
            }

        HookLog.i(
            HookLog.Module.WIFI,
            "hooked ScanRequestProxy ${scanRequestProxyClass.name} scanResults=$hookedScanResults"
        )
    }

    private fun hookWifiServiceClass(classLoader: ClassLoader, serviceClass: Class<*>) {
        synchronized(hookedServiceClasses) {
            if (!hookedServiceClasses.add(serviceClass)) {
                HookLog.d(HookLog.Module.WIFI, "Wi-Fi service class already hooked: ${serviceClass.name}")
                return
            }
        }

        var hookedConnectionInfo = 0
        var hookedScanResults = 0
        val candidateMethods = serviceClass.declaredMethods
            .filter { it.name == "getConnectionInfo" || it.name == "getScanResults" }
        HookLog.i(
            HookLog.Module.WIFI,
            "Wi-Fi service ${serviceClass.name} candidates=${candidateMethods.joinToString { it.signature() }}"
        )

        candidateMethods.forEach { method ->
            when {
                method.name == "getConnectionInfo" && method.returnsWifiInfo() -> {
                    HookBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        HookLog.i(HookLog.Module.WIFI, "spoof getConnectionInfo via ${method.signature()}")
                        result = spoofedWifiInfo(config)
                    })
                    hookedConnectionInfo++
                }

                method.name == "getScanResults" && method.returnsScanResults() -> {
                    HookBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        HookLog.i(HookLog.Module.WIFI, "spoof getScanResults via ${method.signature()}")
                        result = method.wrapScanResults(classLoader, spoofedScanResults(config))
                    })
                    hookedScanResults++
                }
            }
        }

        HookLog.i(
            HookLog.Module.WIFI,
            "hooked Wi-Fi service ${serviceClass.name} connectionInfo=$hookedConnectionInfo scanResults=$hookedScanResults"
        )
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
