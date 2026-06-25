package asia.nana7mi.arirang.hook

import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
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

        XposedBridge.log("Arirang/HookManager: handleLoadPackage(${lpparam.packageName}) restrictHotSwitching=$restrictHotSwitching")
        modules
            .filter { it.matches(lpparam.packageName) }
            .filter { !restrictHotSwitching || it.isEnabled() }
            .forEach { module ->
                runCatching {
                    module.onHook(lpparam)
                }.onFailure {
                    HookLog.e(HookLog.Module.CORE, "module ${module.javaClass.simpleName} failed for ${lpparam.packageName}", it)
                }
            }
    }
}
