package asia.nana7mi.arirang.hook.core

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Facade bridging Arirang hook modules to libxposed API 102.
 */
object HookBridge {

    @Volatile
    private var xposed: XposedInterface? = null

    fun init(xposedInterface: XposedInterface) {
        this.xposed = xposedInterface
    }

    val xposedInterface: XposedInterface?
        get() = xposed

    // ---- Remote Preferences ----

    fun getRemotePreferences(group: String): SharedPreferences {
        val xp = xposed ?: throw IllegalStateException("HookBridge not initialized with XposedInterface")
        return xp.getRemotePreferences(group)
    }

    // ---- Class Lookup ----

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return runCatching { findClass(className, classLoader) }.getOrNull()
    }

    // ---- Hook Installation ----

    fun hookMethod(hookMethod: Member, callback: HookCallback): XposedInterface.HookHandle {
        val origin = hookMethod as? Executable
            ?: throw IllegalArgumentException("hookMethod must be Method or Constructor: $hookMethod")
        val xp = xposed ?: throw IllegalStateException("HookBridge not initialized with XposedInterface")

        return xp.hook(origin)
            .setPriority(callback.priority)
            .intercept { chain ->
                val param = MethodHookParam(
                    executable = chain.executable,
                    thisObject = chain.thisObject,
                    args = chain.args.toTypedArray()
                )
                callback.beforeHookedMethod(param)
                if (param.returnEarly) {
                    if (param.throwable != null) throw param.throwable!!
                    return@intercept param.result
                }

                try {
                    param.result = chain.proceed(param.args)
                } catch (t: Throwable) {
                    param.throwable = t
                }

                callback.afterHookedMethod(param)
                if (param.throwable != null) throw param.throwable!!
                param.result
            }
    }

    fun hookAllMethods(
        hookClass: Class<*>,
        methodName: String,
        callback: HookCallback
    ): Set<XposedInterface.HookHandle> {
        val handles = mutableSetOf<XposedInterface.HookHandle>()
        var current: Class<*>? = hookClass
        val visited = mutableSetOf<String>()

        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name == methodName) {
                    val sig = method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + ")"
                    if (visited.add(sig)) {
                        handles.add(hookMethod(method, callback))
                    }
                }
            }
            current = current.superclass
        }
        return handles
    }

    fun hookAllConstructors(
        hookClass: Class<*>,
        callback: HookCallback
    ): Set<XposedInterface.HookHandle> {
        val handles = mutableSetOf<XposedInterface.HookHandle>()
        for (constructor in hookClass.declaredConstructors) {
            handles.add(hookMethod(constructor, callback))
        }
        return handles
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XposedInterface.HookHandle {
        require(parameterTypesAndCallback.isNotEmpty()) { "Parameter types and callback cannot be empty" }
        val callback = parameterTypesAndCallback.last() as? HookCallback
            ?: throw IllegalArgumentException("Last parameter must be a HookCallback")

        val parameterTypes = parameterTypesAndCallback.take(parameterTypesAndCallback.size - 1)
            .map {
                when (it) {
                    is Class<*> -> it
                    null -> throw IllegalArgumentException("Parameter type cannot be null")
                    else -> throw IllegalArgumentException("Expected Class<*>, got ${it.javaClass}")
                }
            }.toTypedArray()

        val method = findMethodExact(clazz, methodName, *parameterTypes)
            ?: throw NoSuchMethodException("Method $methodName(${parameterTypes.joinToString { it.name }}) not found in ${clazz.name}")

        return hookMethod(method, callback)
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XposedInterface.HookHandle {
        val clazz = findClass(className, classLoader)
        return findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
    }

    private fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(methodName, *parameterTypes)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    // ---- Logging ----

    fun log(message: String) {
        val xp = xposed
        if (xp != null) {
            xp.log(Log.INFO, "Arirang", message)
        } else {
            Log.i("Arirang", message)
        }
    }

    // ---- Instance Creation & Reflection ----

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = findMatchingConstructor(clazz, *args)
            ?: throw NoSuchMethodException("Matching constructor not found in ${clazz.name} for args [${args.joinToString()}]")
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) throw NullPointerException("Instance cannot be null for callMethod")
        val method = findMatchingMethod(instance.javaClass, methodName, false, *args)
            ?: throw NoSuchMethodException("Method $methodName not found in ${instance.javaClass.name} for args [${args.joinToString()}]")
        method.isAccessible = true
        return method.invoke(instance, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findMatchingMethod(clazz, methodName, true, *args)
            ?: throw NoSuchMethodException("Static method $methodName not found in ${clazz.name} for args [${args.joinToString()}]")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    // ---- Field Getters ----

    fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        val field = findField(instance.javaClass, fieldName)
            ?: throw NoSuchFieldException("Field $fieldName not found in ${instance.javaClass.name}")
        field.isAccessible = true
        return field.get(instance)
    }

    fun getIntField(instance: Any?, fieldName: String): Int {
        return (getObjectField(instance, fieldName) as? Number)?.toInt() ?: 0
    }

    fun getLongField(instance: Any?, fieldName: String): Long {
        return (getObjectField(instance, fieldName) as? Number)?.toLong() ?: 0L
    }

    fun getBooleanField(instance: Any?, fieldName: String): Boolean {
        return getObjectField(instance, fieldName) as? Boolean ?: false
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        val field = findField(clazz, fieldName)
            ?: throw NoSuchFieldException("Static field $fieldName not found in ${clazz.name}")
        field.isAccessible = true
        return field.get(null)
    }

    // ---- Field Setters ----

    fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
        if (instance == null) return
        val field = findField(instance.javaClass, fieldName)
            ?: throw NoSuchFieldException("Field $fieldName not found in ${instance.javaClass.name}")
        field.isAccessible = true
        field.set(instance, value)
    }

    fun setIntField(instance: Any?, fieldName: String, value: Int) {
        setObjectField(instance, fieldName, value)
    }

    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        val field = findField(clazz, fieldName)
            ?: throw NoSuchFieldException("Static field $fieldName not found in ${clazz.name}")
        field.isAccessible = true
        field.set(null, value)
    }

    // ---- Reflection Helpers ----

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMatchingConstructor(clazz: Class<*>, vararg args: Any?): Constructor<*>? {
        for (constructor in clazz.declaredConstructors) {
            if (isParametersCompatible(constructor.parameterTypes, args)) {
                return constructor
            }
        }
        return null
    }

    private fun findMatchingMethod(
        clazz: Class<*>,
        methodName: String,
        isStatic: Boolean,
        vararg args: Any?
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == methodName &&
                    Modifier.isStatic(method.modifiers) == isStatic &&
                    isParametersCompatible(method.parameterTypes, args)
                ) {
                    return method
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun isParametersCompatible(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        for (i in types.indices) {
            val arg = args[i]
            val type = types[i]
            if (arg == null) {
                if (type.isPrimitive) return false
            } else {
                val boxedType = boxType(type)
                if (!boxedType.isInstance(arg)) return false
            }
        }
        return true
    }

    private fun boxType(type: Class<*>): Class<*> {
        return when (type) {
            java.lang.Integer.TYPE -> Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> type
        }
    }
}
