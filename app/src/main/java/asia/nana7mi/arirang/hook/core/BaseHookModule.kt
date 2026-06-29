package asia.nana7mi.arirang.hook.core

import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member

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

    companion object {
        fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
            return XposedHelpers.findClass(className, classLoader)
        }

        fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
            return XposedHelpers.findClassIfExists(className, classLoader)
        }

        fun findAndHookMethod(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypesAndCallback: Any?
        ): XC_MethodHook.Unhook {
            return XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
        }

        fun hookMethod(hookMethod: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
            return XposedBridge.hookMethod(hookMethod, callback)
        }

        fun hookAllMethods(
            hookClass: Class<*>,
            methodName: String,
            callback: XC_MethodHook
        ): Set<XC_MethodHook.Unhook> {
            return XposedBridge.hookAllMethods(hookClass, methodName, callback)
        }

        fun hookAllConstructors(
            hookClass: Class<*>,
            callback: XC_MethodHook
        ): Set<XC_MethodHook.Unhook> {
            return XposedBridge.hookAllConstructors(hookClass, callback)
        }

        fun log(message: String) {
            XposedBridge.log(message)
        }

        fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
            return XposedHelpers.newInstance(clazz, *args)
        }

        fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
            return XposedHelpers.callMethod(instance, methodName, *args)
        }

        fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
            return XposedHelpers.callStaticMethod(clazz, methodName, *args)
        }

        fun getObjectField(instance: Any?, fieldName: String): Any? {
            return XposedHelpers.getObjectField(instance, fieldName)
        }

        fun getIntField(instance: Any?, fieldName: String): Int {
            return XposedHelpers.getIntField(instance, fieldName)
        }

        fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
            return XposedHelpers.getStaticObjectField(clazz, fieldName)
        }

        fun setObjectField(instance: Any, fieldName: String, value: Any?) {
            XposedHelpers.setObjectField(instance, fieldName, value)
        }

        fun setIntField(instance: Any, fieldName: String, value: Int) {
            XposedHelpers.setIntField(instance, fieldName, value)
        }
    }
}
