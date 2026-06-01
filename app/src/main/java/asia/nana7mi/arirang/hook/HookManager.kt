package asia.nana7mi.arirang.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookManager : IXposedHookLoadPackage {
    private val modules = listOf(
        SystemServerHook(),
        FuckClipboard(),
        FuckSim(),
        FuckWifi(),
        FuckLocation(),
        FuckSettingsProvider(),
        FuckGms(),
        XposedActivation()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        modules
            .filter { it.matches(lpparam.packageName) }
            .forEach { module ->
                runCatching {
                    // Check isEnabled only if it doesn't need to be reactive inside the module.
                    // For now, let's keep installing hooks if matches, but ensure modules check isEnabled internally.
                    // UNLESS the user wants a hard-cut off.
                    // The user said "unify api or abstraction layer", so I should probably use it.
                    module.onHook(lpparam)
                }.onFailure {
                    HookLog.e(HookLog.Module.CORE, "module ${module.javaClass.simpleName} failed for ${lpparam.packageName}", it)
                }
            }
    }
}
