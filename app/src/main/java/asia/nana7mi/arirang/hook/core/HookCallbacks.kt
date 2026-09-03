package asia.nana7mi.arirang.hook.core

import java.lang.reflect.Executable

/**
 * Parameter passed to hook callbacks.
 */
class MethodHookParam(
    val executable: Executable,
    val thisObject: Any?,
    val args: Array<Any?>,
    var returnEarly: Boolean = false
) {
    var result: Any? = null
        set(value) {
            field = value
            returnEarly = true
        }

    var throwable: Throwable? = null
        set(value) {
            field = value
            returnEarly = true
        }

    private var extra: MutableMap<String, Any?>? = null

    fun setObjectExtra(key: String, value: Any?) {
        if (extra == null) extra = mutableMapOf()
        if (value == null) {
            extra?.remove(key)
        } else {
            extra?.put(key, value)
        }
    }

    fun getObjectExtra(key: String): Any? = extra?.get(key)

    fun hasThrowable(): Boolean = throwable != null
}

/**
 * Base callback for method/constructor hooks.
 */
abstract class HookCallback(val priority: Int = PRIORITY_DEFAULT) {
    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    companion object {
        const val PRIORITY_DEFAULT = 50
        const val PRIORITY_LOWEST = -10000
        const val PRIORITY_HIGHEST = 10000
    }
}

/** Runs [block] before the hooked method; assign `result` to short-circuit it. */
fun beforeHookedMethod(
    priority: Int = HookCallback.PRIORITY_DEFAULT,
    block: MethodHookParam.() -> Unit
): HookCallback = object : HookCallback(priority) {
    override fun beforeHookedMethod(param: MethodHookParam) {
        param.block()
    }
}

/** Runs [block] after the hooked method; read or rewrite `result` there. */
fun afterHookedMethod(
    priority: Int = HookCallback.PRIORITY_DEFAULT,
    block: MethodHookParam.() -> Unit
): HookCallback = object : HookCallback(priority) {
    override fun afterHookedMethod(param: MethodHookParam) {
        param.block()
    }
}

/** Runs [before] and/or [after] around the hooked method. */
fun hookedMethod(
    priority: Int = HookCallback.PRIORITY_DEFAULT,
    before: (MethodHookParam.() -> Unit)? = null,
    after: (MethodHookParam.() -> Unit)? = null
): HookCallback = object : HookCallback(priority) {
    override fun beforeHookedMethod(param: MethodHookParam) {
        before?.invoke(param)
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        after?.invoke(param)
    }
}
