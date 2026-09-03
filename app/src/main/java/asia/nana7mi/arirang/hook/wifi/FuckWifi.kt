package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.HookPackageParam

/**
 * Rewrites current Wi-Fi identity and nearby Wi-Fi scan results at the Wi-Fi
 * service and Connectivity framework layers, so callers observe spoofed data
 * through WifiManager and ConnectivityManager (including transportInfo).
 */
class FuckWifi : BaseHookModule(
    targetPackages = setOf("android", "com.android.wifi")
) {
    private val configStore = WifiConfigStore()
    private val serviceHooks = WifiServiceHooks(::currentConfig)
    private val systemServiceHooks = WifiSystemServiceHooks(serviceHooks)
    private val connectivityHooks = WifiConnectivityHooks(::currentConfig)

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(param: HookPackageParam) {
        runCatching {
            HookLog.i(
                HookLog.Module.WIFI,
                "installing Wi-Fi hooks for ${param.packageName} classLoader=${param.classLoader}"
            )
            serviceHooks.hookWifiService(param.classLoader)
            systemServiceHooks.hookWifiSystemServiceManager(param.classLoader)
            connectivityHooks.hookConnectivitySurfaces(param.classLoader)
            HookLog.i(HookLog.Module.WIFI, "Wi-Fi privacy hook installed for ${param.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.WIFI, "Wi-Fi privacy hook failed for ${param.packageName}", it)
        }
    }

    private fun currentConfig(): WifiHookConfig {
        return configStore.current()
    }
}
