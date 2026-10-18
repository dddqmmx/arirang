package asia.nana7mi.arirang.hook.activation

import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.beforeHookedMethod
import asia.nana7mi.arirang.hook.api.HookPackageParam

// 用于检测客户端xposed是否生效
class XposedActivation : BaseHookModule(matchClient = true) {
    override fun onHook(param: HookPackageParam) {
        val point = HookBridge.findClass("asia.nana7mi.arirang.ui.fragment.HomeFragment", param.classLoader)
        HookBridge.findAndHookMethod(point, "isXposedActivation", beforeHookedMethod {
            result = true
        })
    }
}
