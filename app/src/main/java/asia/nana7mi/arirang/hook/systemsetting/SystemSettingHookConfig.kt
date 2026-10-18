package asia.nana7mi.arirang.hook.systemsetting

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.xposed.HookLog
import org.json.JSONObject

private const val REFRESH_INTERVAL_MS = 1_000L

/**
 * Realtime view of the system-setting config for hooks.
 *
 * Deliberately typed as [SystemSettingPrefs.Config] rather than a hook-local mirror, so
 * `SystemSettingPrefs.resolveFor` — the single definition of how an override folds over the
 * global default — is the same code the manager screen uses to render what an app will see.
 *
 * The decoder below serves both sources. They disagree on spelling: the service snapshot (the
 * wire format in `SystemSettingConfigSchema`) says `timeZoneId`/`perPackage`, the prefs file says
 * `time_zone_id`/`per_package`, and each per-package override repeats the disagreement one level
 * down. Those aliases are stated once, here, instead of in a snapshot decoder and a prefs decoder
 * that had to be kept in step by hand.
 */
internal object SystemSettingConfig : ConfigSpec<SystemSettingPrefs.Config>(
    id = ConfigIds.SYSTEM_SETTING,
    typeName = "system setting",
    logModule = HookLog.Module.CORE,
    fallback = SystemSettingPrefs.Config(),
    sources = listOf(
        ServiceSnapshotSource(ConfigIds.SYSTEM_SETTING, logName = "system setting"),
        RemotePrefsSource(SystemSettingPrefs.PREFS_NAME),
    ),
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
) {
    /**
     * Whether any override applies at all.
     *
     * Which override applies to *which* package is `SystemSettingPrefs.resolveFor`'s question,
     * answered per package rather than per caller — these surfaces are keyed on the package in
     * their arguments, not on who is asking.
     */
    val enabled = guard("enabled") { it.enabled }
}

private fun decode(values: ConfigValues): SystemSettingPrefs.Config? {
    if (values.isEmpty()) return null
    return SystemSettingPrefs.Config(
        enabled = values.boolean("enabled") ?: false,
        timeZoneId = values.string("timeZoneId", "time_zone_id").orEmpty(),
        languageTag = values.string("languageTag", "language_tag").orEmpty(),
        perPackage = decodeOverrides(values.jsonObject("perPackage", "per_package")),
    )
}

private fun decodeOverrides(root: JSONObject?): Map<String, SystemSettingPrefs.Override> {
    if (root == null) return emptyMap()
    return buildMap {
        val names = root.keys()
        while (names.hasNext()) {
            val packageName = names.next()
            val entry = root.optJSONObject(packageName) ?: continue
            put(
                packageName,
                SystemSettingPrefs.Override(
                    enabled = entry.optBoolean(SystemSettingPrefs.KEY_ENABLED, true),
                    timeZoneId = entry.optString(SystemSettingPrefs.KEY_TIME_ZONE_ID)
                        .ifEmpty { entry.optString("timeZoneId") },
                    languageTag = entry.optString(SystemSettingPrefs.KEY_LANGUAGE_TAG)
                        .ifEmpty { entry.optString("languageTag") },
                )
            )
        }
    }
}
