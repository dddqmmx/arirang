package asia.nana7mi.arirang.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookManager : IXposedHookLoadPackage {
    private val modules = listOf(
        SystemServerHook(),
        FuckClipboard(),
        FuckSim(),
        FuckSettingsProvider(),
        FuckGms(),
        XposedActivation()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        modules
            .filter { it.matches(lpparam.packageName) }
            .forEach { module ->
                runCatching {
                    module.onHook(lpparam)
                }.onFailure {
                    HookLog.e(HookLog.Module.CORE, "module ${module.javaClass.simpleName} failed for ${lpparam.packageName}", it)
                }
            }
    }
}
