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
        XposedActivation()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
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
