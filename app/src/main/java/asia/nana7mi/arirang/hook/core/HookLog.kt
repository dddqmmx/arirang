package asia.nana7mi.arirang.hook.core


import android.os.SystemClock
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.schema.HookLogConfigSchema
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    private const val PROPERTY_PREFIX = "persist.arirang.log."
    private const val CACHE_TTL_MS = 1_000L

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedSwitches: Map<String, Boolean> = emptyMap()

    private val resolvingConfig = ThreadLocal.withInitial { false }
    private val refreshInFlight = AtomicBoolean(false)
    private val refreshExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ArirangHookLogConfig").apply { isDaemon = true }
    }

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
        val switches = currentSwitches()
        return switches[module.key]
            ?: switches["all"]
            ?: module.defaultEnabled
    }

    private fun isDebugEnabled(module: Module): Boolean {
        val switches = currentSwitches()
        return switches["${module.key}.debug"]
            ?: switches["debug"]
            ?: BuildConfig.DEBUG
    }

    private fun write(module: Module, level: String, message: String) {
        if (resolvingConfig.get() == true || !isEnabled(module)) return
        HookBridge.log("Arirang/${module.key}/$level: $message")
    }

    private fun currentSwitches(): Map<String, Boolean> {
        val now = SystemClock.uptimeMillis()
        val cached = cachedSwitches
        if (cached.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) return cached
        refreshSwitchesAsync()
        return cached
    }

    private fun refreshSwitchesAsync() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        refreshExecutor.execute {
            resolvingConfig.set(true)
            try {
                val updated = buildMap {
                    val json = runCatching { hookLogSnapshotJson() }.onFailure {
                        HookBridge.log("Arirang/HookLog/E: failed to read hook log snapshot: ${it.message}")
                    }.getOrNull()
                    readSwitch("all")?.let { put("all", it) }
                    readSwitch("debug")?.let { put("debug", it) }
                    Module.entries.forEach { module ->
                        val configured = json?.optBoolean(module.key, module.defaultEnabled)
                        if (configured != null) {
                            put(module.key, configured)
                        }
                        readSwitch(module.key)?.let { put(module.key, it) }
                        readSwitch("${module.key}.debug")?.let { put("${module.key}.debug", it) }
                    }
                }
                cachedSwitches = updated
            } catch (t: Throwable) {
                HookBridge.log("Arirang/HookLog/E: failed to refresh hook log config: ${t.message}")
            } finally {
                cachedAt = SystemClock.uptimeMillis()
                resolvingConfig.set(false)
                refreshInFlight.set(false)
            }
        }
    }

    private fun hookLogSnapshotJson(): JSONObject? {
        ArirangClient.readConfigSnapshot(
            configName = ConfigIds.HOOK_LOG,
            allowBind = true,
            logName = "hook log"
        )?.let { snapshot ->
            runCatching { HookLogConfigSchema.fromJson(snapshot) }.getOrNull()?.let { schema ->
                return JSONObject().apply {
                    put("core", schema.core)
                    put("clipboard", schema.clipboard)
                    put("gms", schema.gms)
                    put("location", schema.location)
                    put("package_list", schema.packageList)
                    put("settings", schema.settings)
                    put("sim", schema.sim)
                    put("wifi", schema.wifi)
                    put("bluetooth", schema.bluetooth)
                    put("unique_id", schema.uniqueId)
                    put("notify", schema.notify)
                }
            }
        }

        return runCatching {
            HookConfigFile.xSharedPreferences(HookLogSettings.PREFS_NAME).takeIf {
                it.file.canRead()
            }?.let { prefs ->
                JSONObject().apply {
                    Module.entries.forEach { module ->
                        put(module.key, prefs.getBoolean(HookLogSettings.prefKey(module.key), module.defaultEnabled))
                    }
                }
            }
        }.getOrNull()
    }

    private fun readSwitch(key: String): Boolean? {
        val value = runCatching {
            HookBridge.callStaticMethod(
                HookBridge.findClass("android.os.SystemProperties", null),
                "get",
                PROPERTY_PREFIX + key,
                ""
            ) as? String
        }.getOrNull()?.trim()?.lowercase().orEmpty()

        return when (value) {
            "1", "true", "y", "yes", "on", "enable", "enabled" -> true
            "0", "false", "n", "no", "off", "disable", "disabled" -> false
            else -> null
        }
    }
}
