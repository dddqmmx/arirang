package asia.nana7mi.arirang.hook.core


import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs
import asia.nana7mi.arirang.hook.activation.XposedActivation
import asia.nana7mi.arirang.hook.bluetooth.FuckBluetooth
import asia.nana7mi.arirang.hook.clipboard.FuckClipboard
import asia.nana7mi.arirang.hook.gms.FuckGms
import asia.nana7mi.arirang.hook.location.FuckLocation
import asia.nana7mi.arirang.hook.packagelist.FuckPackageList
import asia.nana7mi.arirang.hook.process.FuckProcess
import asia.nana7mi.arirang.hook.settings.FuckSettingsProvider
import asia.nana7mi.arirang.hook.sim.FuckSim
import asia.nana7mi.arirang.hook.system.SystemServerHook
import asia.nana7mi.arirang.hook.wifi.FuckWifi
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookManager : IXposedHookLoadPackage {
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
        XposedActivation()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        /*
         * DESIGN PRINCIPLE: Arirang is a system-level privacy model.
         *
         * We aim to intercept and rewrite data at the source (system_server, phone process)
         * rather than injecting hooks into arbitrary third-party applications. This ensures
         * maximum performance and compatibility while maintaining a clean application
         * runtime environment.
         */
        val prefs = HookConfigFile.xSharedPreferences(GlobalConfigPrefs.PREFS_NAME)
        val restrictHotSwitching = prefs.getBoolean(GlobalConfigPrefs.KEY_RESTRICT_HOT_SWITCHING, false)

        HookLog.d(HookLog.Module.CORE, "handleLoadPackage(${lpparam.packageName}) restrictHotSwitching=$restrictHotSwitching")
        modules
            .filter { it.matches(lpparam.packageName) }
            .filter { module ->
                !restrictHotSwitching || module.requiresRuntimeConfigInstall() || module.isEnabled()
            }
            .forEach { module ->
                runCatching {
                    module.onHook(lpparam)
                }.onFailure {
                    HookLog.e(HookLog.Module.CORE, "module ${module.javaClass.simpleName} failed for ${lpparam.packageName}", it)
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
