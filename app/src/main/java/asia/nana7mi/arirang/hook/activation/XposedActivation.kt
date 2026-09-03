package asia.nana7mi.arirang.hook.activation

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.beforeHookedMethod
import asia.nana7mi.arirang.hook.core.HookPackageParam

// 用于检测客户端xposed是否生效
class XposedActivation : BaseHookModule(matchClient = true) {
    override fun onHook(param: HookPackageParam) {
        val point = HookBridge.findClass("asia.nana7mi.arirang.ui.fragment.HomeFragment", param.classLoader)
        HookBridge.findAndHookMethod(point, "isXposedActivation", beforeHookedMethod {
            result = true
        })
    }
}
