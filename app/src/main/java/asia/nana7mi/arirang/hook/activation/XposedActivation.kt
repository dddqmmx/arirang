package asia.nana7mi.arirang.hook.activation

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge

import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedActivation : BaseHookModule(matchClient = true) {
    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val point = HookBridge.findClass("asia.nana7mi.arirang.ui.fragment.HomeFragment", lpparam.classLoader)
        HookBridge.findAndHookMethod(point, "isXposedActivation", beforeHookedMethod {
            result = true
        })
    }
}
