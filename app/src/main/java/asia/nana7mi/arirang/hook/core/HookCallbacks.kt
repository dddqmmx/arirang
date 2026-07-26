package asia.nana7mi.arirang.hook.core

import de.robv.android.xposed.XC_MethodHook

/*
 * Builders for the three XC_MethodHook shapes this project uses.
 *
 * These are top-level rather than members of [BaseHookModule] because hook code
 * is split between HookModule entrypoints (`Fuck*`) and plain installer
 * collaborators (`*Hooks`, `*Factory`). Only the former could reach `protected`
 * members, so every collaborator previously had to re-declare a private copy or
 * hand-roll `object : XC_MethodHook()` — eight classes did, and FuckClipboard
 * ended up using both styles in one file.
 *
 * Being top-level, they are reached by import from anywhere, and the lambda
 * labels (`return@afterHookedMethod`) read the same at every call site.
 */

/** Runs [block] before the hooked method; assign `result` to short-circuit it. */
fun beforeHookedMethod(
    priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
    block: XC_MethodHook.MethodHookParam.() -> Unit
): XC_MethodHook = object : XC_MethodHook(priority) {
    override fun beforeHookedMethod(param: MethodHookParam) {
        param.block()
    }
}

/** Runs [block] after the hooked method; read or rewrite `result` there. */
fun afterHookedMethod(
    priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
    block: XC_MethodHook.MethodHookParam.() -> Unit
): XC_MethodHook = object : XC_MethodHook(priority) {
    override fun afterHookedMethod(param: MethodHookParam) {
        param.block()
    }
}

/** Runs [before] and/or [after] around the hooked method. */
fun hookedMethod(
    priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
    before: (XC_MethodHook.MethodHookParam.() -> Unit)? = null,
    after: (XC_MethodHook.MethodHookParam.() -> Unit)? = null
): XC_MethodHook = object : XC_MethodHook(priority) {
    override fun beforeHookedMethod(param: MethodHookParam) {
        before?.invoke(param)
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        after?.invoke(param)
    }
}
