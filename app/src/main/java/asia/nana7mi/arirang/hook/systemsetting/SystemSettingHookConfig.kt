package asia.nana7mi.arirang.hook.systemsetting

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.data.datastore.schema.SystemSettingConfigSchema
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject

/**
 * Realtime view of the system-setting config for hooks.
 *
 * Deliberately typed as [SystemSettingPrefs.Config] rather than a hook-local
 * mirror, so `SystemSettingPrefs.resolveFor` — the single definition of how an
 * override folds over the global default — is the same code the manager screen
 * uses to render what an app will see.
 */
internal object SystemSettingHookConfigFile {

    fun create(): HookConfigFile<SystemSettingPrefs.Config> = HookConfigFile(
        configName = ConfigIds.SYSTEM_SETTING,
        prefsName = SystemSettingPrefs.PREFS_NAME,
        defaultValue = SystemSettingPrefs.Config(),
        refreshIntervalMs = REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.SYSTEM_SETTING,
                force = force,
                allowBind = true,
                logName = "system setting"
            )
        },
        parseRealtimeSnapshot = ::parseSnapshot,
        readStoredConfig = ::readStored
    )

    private fun parseSnapshot(snapshot: String): SystemSettingPrefs.Config? = runCatching {
        val schema = SystemSettingConfigSchema.fromJson(snapshot)
        SystemSettingPrefs.Config(
            enabled = schema.enabled,
            timeZoneId = schema.timeZoneId,
            languageTag = schema.languageTag,
            perPackage = schema.perPackage.mapValues { (_, override) ->
                SystemSettingPrefs.Override(
                    enabled = override.enabled,
                    timeZoneId = override.timeZoneId,
                    languageTag = override.languageTag
                )
            }
        )
    }.onFailure {
        HookLog.w(HookLog.Module.CORE, "failed to parse system setting snapshot: ${it.message}")
    }.getOrNull()

    private fun readStored(prefs: XSharedPreferences): SystemSettingPrefs.Config {
        return SystemSettingPrefs.Config(
            enabled = prefs.getBoolean(SystemSettingPrefs.KEY_ENABLED, false),
            timeZoneId = prefs.getString(SystemSettingPrefs.KEY_TIME_ZONE_ID, null).orEmpty(),
            languageTag = prefs.getString(SystemSettingPrefs.KEY_LANGUAGE_TAG, null).orEmpty(),
            perPackage = prefs.getString(SystemSettingPrefs.KEY_PER_PACKAGE, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(::parseOverrides)
                .orEmpty()
        )
    }

    private fun parseOverrides(json: String): Map<String, SystemSettingPrefs.Override> = runCatching {
        val root = JSONObject(json)
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val entry = root.optJSONObject(packageName) ?: continue
                put(
                    packageName,
                    SystemSettingPrefs.Override(
                        enabled = entry.optBoolean(SystemSettingPrefs.KEY_ENABLED, true),
                        timeZoneId = entry.optString(SystemSettingPrefs.KEY_TIME_ZONE_ID),
                        languageTag = entry.optString(SystemSettingPrefs.KEY_LANGUAGE_TAG)
                    )
                )
            }
        }
    }.onFailure {
        HookLog.w(HookLog.Module.CORE, "failed to parse stored per-package system settings: ${it.message}")
    }.getOrDefault(emptyMap())

    private const val REFRESH_INTERVAL_MS = 1_000L
}
