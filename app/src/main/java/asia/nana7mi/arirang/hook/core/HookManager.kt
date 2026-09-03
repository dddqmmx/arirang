package asia.nana7mi.arirang.hook.core

import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs
import asia.nana7mi.arirang.hook.activation.XposedActivation
import asia.nana7mi.arirang.hook.bluetooth.FuckBluetooth
import asia.nana7mi.arirang.hook.clipboard.FuckClipboard
import asia.nana7mi.arirang.hook.gms.FuckGms
import asia.nana7mi.arirang.hook.location.FuckLocation
import asia.nana7mi.arirang.hook.network.FuckVpnStatus
import asia.nana7mi.arirang.hook.packagelist.FuckPackageList
import asia.nana7mi.arirang.hook.process.FuckProcess
import asia.nana7mi.arirang.hook.settings.FuckSettingsProvider
import asia.nana7mi.arirang.hook.sim.FuckSim
import asia.nana7mi.arirang.hook.system.SystemServerHook
import asia.nana7mi.arirang.hook.systemsetting.FuckAppLocale
import asia.nana7mi.arirang.hook.wifi.FuckWifi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class HookManager : XposedModule() {
    private val modules = listOf(
        SystemServerHook(),
        FuckClipboard(),
        FuckSim(),
        FuckWifi(),
        FuckBluetooth(),
        FuckLocation(),
        FuckSettingsProvider(),
        FuckGms(),
        FuckProcess(),
        FuckPackageList(),
        FuckVpnStatus(),
        FuckAppLocale(),
        XposedActivation()
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookBridge.init(this)
        HookLog.i(HookLog.Module.CORE, "libxposed module loaded in ${param.processName}, api=$apiVersion")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        HookBridge.init(this)
        dispatchPackageLoaded(
            HookPackageParam(
                packageName = "android",
                classLoader = param.classLoader,
                processName = "system_server"
            )
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        HookBridge.init(this)
        dispatchPackageLoaded(
            HookPackageParam(
                packageName = param.packageName,
                classLoader = param.classLoader,
                processName = param.applicationInfo.processName
            )
        )
    }

    private fun dispatchPackageLoaded(pkgParam: HookPackageParam) {
        val prefs = runCatching {
            HookConfigFile.xSharedPreferences(GlobalConfigPrefs.PREFS_NAME)
        }.getOrNull()
        val restrictHotSwitching = prefs?.getBoolean(GlobalConfigPrefs.KEY_RESTRICT_HOT_SWITCHING, false) ?: false

        HookLog.d(
            HookLog.Module.CORE,
            "dispatchPackageLoaded(${pkgParam.packageName}) restrictHotSwitching=$restrictHotSwitching"
        )
        modules
            .filter { it.matches(pkgParam.packageName) }
            .filter { module ->
                !restrictHotSwitching || module.requiresRuntimeConfigInstall() || module.isEnabled()
            }
            .forEach { module ->
                runCatching {
                    module.onHook(pkgParam)
                }.onFailure {
                    HookLog.e(
                        HookLog.Module.CORE,
                        "module ${module.javaClass.simpleName} failed for ${pkgParam.packageName}",
                        it
                    )
                }
            }
    }

    private fun HookModule.requiresRuntimeConfigInstall(): Boolean {
        // These modules use private realtime snapshots that are unavailable until their host
        // process has installed the hook and connected to ArirangService. Filtering them before
        // onHook() would make the disabled default permanent when hot-switch restriction is on.
        return this is FuckGms || this is FuckSim || this is FuckPackageList
    }
}
