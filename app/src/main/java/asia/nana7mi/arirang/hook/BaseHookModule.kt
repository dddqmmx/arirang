package asia.nana7mi.arirang.hook

import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XC_MethodHook

abstract class BaseHookModule(
    private val targetPackages: Set<String> = emptySet(),
    private val matchSystem: Boolean = false,
    private val matchClient: Boolean = false
): HookModule {
    protected fun beforeHookedMethod(
        priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    protected fun afterHookedMethod(
        priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook(priority) {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    protected fun hookedMethod(
        priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
        before: (XC_MethodHook.MethodHookParam.() -> Unit)? = null,
        after: (XC_MethodHook.MethodHookParam.() -> Unit)? = null
    ): XC_MethodHook {
        return object : XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                before?.invoke(param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                after?.invoke(param)
            }
        }
    }

    override fun matches(packageName: String): Boolean {
        if (matchSystem && packageName == "android") return true
        if (matchClient && BuildConfig.APPLICATION_ID == packageName) return true
        return packageName in targetPackages
    }

    override fun isEnabled(): Boolean = true

    protected fun isEnabledHook(
        priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
        before: (XC_MethodHook.MethodHookParam.() -> Unit)? = null,
        after: (XC_MethodHook.MethodHookParam.() -> Unit)? = null
    ): XC_MethodHook {
        return object : XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isEnabled()) {
                    before?.invoke(param)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (isEnabled()) {
                    after?.invoke(param)
                }
            }
        }
    }
}
