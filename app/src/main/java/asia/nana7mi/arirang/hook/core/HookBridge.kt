package asia.nana7mi.arirang.hook.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member

/**
 * Thin facade over [XposedHelpers] and [XposedBridge].
 *
 * Every Xposed utility call in the project should go through this object so that
 * the framework dependency is confined to a single file. If we ever swap the
 * underlying hook engine (LSPatch, custom bridge, …), only this file needs to
 * change.
 */
object HookBridge {

    // ---- class lookup ----

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return XposedHelpers.findClass(className, classLoader)
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return XposedHelpers.findClassIfExists(className, classLoader)
    }

    // ---- hook installation ----

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook {
        return XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook {
        return XposedHelpers.findAndHookMethod(
            className, classLoader, methodName, *parameterTypesAndCallback
        )
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

    // ---- logging ----

    fun log(message: String) {
        XposedBridge.log(message)
    }

    // ---- instance creation ----

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        return XposedHelpers.newInstance(clazz, *args)
    }

    // ---- method invocation ----

    fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        return XposedHelpers.callMethod(instance, methodName, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        return XposedHelpers.callStaticMethod(clazz, methodName, *args)
    }

    // ---- field getters ----

    fun getObjectField(instance: Any?, fieldName: String): Any? {
        return XposedHelpers.getObjectField(instance, fieldName)
    }

    fun getIntField(instance: Any?, fieldName: String): Int {
        return XposedHelpers.getIntField(instance, fieldName)
    }

    fun getLongField(instance: Any?, fieldName: String): Long {
        return XposedHelpers.getLongField(instance, fieldName)
    }

    fun getBooleanField(instance: Any?, fieldName: String): Boolean {
        return XposedHelpers.getBooleanField(instance, fieldName)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        return XposedHelpers.getStaticObjectField(clazz, fieldName)
    }

    // ---- field setters ----

    fun setObjectField(instance: Any, fieldName: String, value: Any?) {
        XposedHelpers.setObjectField(instance, fieldName, value)
    }

    fun setIntField(instance: Any, fieldName: String, value: Int) {
        XposedHelpers.setIntField(instance, fieldName, value)
    }

    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        XposedHelpers.setStaticObjectField(clazz, fieldName, value)
    }
}
