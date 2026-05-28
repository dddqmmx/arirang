package asia.nana7mi.arirang.hook

import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedActivation : BaseHookModule(matchClient = true) {
    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val point = XposedHelpers.findClass("asia.nana7mi.arirang.ui.fragment.HomeFragment", lpparam.classLoader)
        XposedHelpers.findAndHookMethod(point, "isXposedActivation", beforeHookedMethod {
            result = true
        })
    }
}
