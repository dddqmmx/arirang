package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.api.HookPackageParam

/**
 * Rewrites current Wi-Fi identity and nearby Wi-Fi scan results at the Wi-Fi
 * service and Connectivity framework layers, so callers observe spoofed data
 * through WifiManager and ConnectivityManager (including transportInfo).
 */
class FuckWifi : BaseHookModule(
    targetPackages = setOf("android", "com.android.wifi")
) {
    private val serviceHooks = WifiServiceHooks()
    private val systemServiceHooks = WifiSystemServiceHooks(serviceHooks)
    private val connectivityHooks = WifiConnectivityHooks()

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

    private fun currentConfig(): WifiHookConfig = WifiConfig.current()
}
