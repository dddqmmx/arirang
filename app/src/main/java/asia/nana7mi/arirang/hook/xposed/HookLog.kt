package asia.nana7mi.arirang.hook.xposed

import asia.nana7mi.arirang.BuildConfig

/**
 * The module's log.
 *
 * Output goes through [HookBridge] to the framework's log. Whether a given module logs at all is
 * decided by [HookLogSwitches], which is an ordinary config with its own sources and cadence.
 *
 * Reading a config logs, and logging reads a config — which is why this class used to carry a
 * thread-local flag to stop itself re-entering. That guard belongs to the config seam now, which
 * has one for every config rather than one per reader, so this is just formatting and a lookup.
 */
object HookLog {

    enum class Module(val key: String, val defaultEnabled: Boolean = true) {
        CORE("core"),
        CLIPBOARD("clipboard"),
        GMS("gms"),
        LOCATION("location"),
        PACKAGE_LIST("package_list"),
        SETTINGS("settings"),
        SIM("sim"),
        WIFI("wifi"),
        BLUETOOTH("bluetooth"),
        UNIQUE_ID("unique_id"),
        NOTIFY("notify")
    }

    internal const val ALL_KEY = "all"
    internal const val DEBUG_KEY = "debug"
    internal const val DEBUG_SUFFIX = "debug"

    fun i(module: Module, message: String) {
        write(module, "I", message)
    }

    fun w(module: Module, message: String) {
        write(module, "W", message)
    }

    fun e(module: Module, message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()
        write(module, "E", message + suffix)
    }

    fun d(module: Module, message: String) {
        if (isDebugEnabled(module)) {
            write(module, "D", message)
        }
    }

    fun isEnabled(module: Module): Boolean {
        val switches = HookLogSwitches.current()
        return switches[module.key]
            ?: switches[ALL_KEY]
            ?: module.defaultEnabled
    }

    private fun isDebugEnabled(module: Module): Boolean {
        val switches = HookLogSwitches.current()
        return switches["${module.key}.$DEBUG_SUFFIX"]
            ?: switches[DEBUG_KEY]
            ?: BuildConfig.DEBUG
    }

    private fun write(module: Module, level: String, message: String) {
        if (!isEnabled(module)) return
        HookBridge.log("Arirang/${module.key}/$level: $message")
    }
}
