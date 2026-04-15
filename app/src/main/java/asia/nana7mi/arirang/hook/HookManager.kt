package asia.nana7mi.arirang.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookManager : IXposedHookLoadPackage {
    private val modules = listOf(
        SystemServerHook(),
        FuckClipboard(),
//        FuckLocation(),
//        FuckPackageList(),
//        FuckSIM(),
        XposedActivation()
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        modules
            .filter { it.matches(lpparam.packageName) }
            .forEach { module ->
                runCatching {
                    module.onHook(lpparam)
                }
            }
    }
}