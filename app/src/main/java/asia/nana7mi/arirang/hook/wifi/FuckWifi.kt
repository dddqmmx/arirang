package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Rewrites current Wi-Fi identity and nearby Wi-Fi scan results at the Wi-Fi
 * service layer, so callers observe spoofed data through normal framework APIs.
 */
class FuckWifi : BaseHookModule(
    targetPackages = setOf("android", "com.android.wifi")
) {
    private val configStore = WifiConfigStore()
    private val serviceHooks = WifiServiceHooks(::currentConfig)
    private val systemServiceHooks = WifiSystemServiceHooks(serviceHooks)

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            HookLog.i(
                HookLog.Module.WIFI,
                "installing Wi-Fi hooks for ${lpparam.packageName} classLoader=${lpparam.classLoader}"
            )
            serviceHooks.hookWifiService(lpparam.classLoader)
            systemServiceHooks.hookWifiSystemServiceManager(lpparam.classLoader)
            HookLog.i(HookLog.Module.WIFI, "Wi-Fi privacy hook installed for ${lpparam.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.WIFI, "Wi-Fi privacy hook failed for ${lpparam.packageName}", it)
        }
    }

    private fun currentConfig(): WifiHookConfig {
        return configStore.current()
    }
}
