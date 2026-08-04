package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.SystemSettingConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.SystemSettingOverrideSchema
import org.json.JSONObject
import java.util.Date

/**
 * Time zone and language reported to applications, with per-package overrides.
 *
 * Shaped after [LocationConfigPrefs]: a global default plus a `perPackage` map
 * of overrides. An empty string means "do not change" at the global level and
 * "follow the global value" inside an override, which is why neither field is
 * nullable — the distinction is between *no opinion* and *an opinion*, and an
 * empty string carries that without a third state.
 *
 * The **time zone half reaches the native submodule** via [SubmoduleConfigFiles]:
 * the resolved per-package map is written into `arirang-submodule/config.json`
 * and applied as a per-process property-area CoW at specialize time (see
 * `submodule/doc/timezone_per_app_research.md`). The language half is served by
 * the system_server `LocaleManagerService` hook. As with [VpnStatusPrefs] there
 * is no MODE_PRIVATE migration (new config).
 */
object SystemSettingPrefs {
    const val PREFS_NAME = "system_setting_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_MODIFIED = "last_modified"
    const val KEY_TIME_ZONE_ID = "time_zone_id"
    const val KEY_LANGUAGE_TAG = "language_tag"
    const val KEY_PER_PACKAGE = "per_package"

    data class Config(
        val enabled: Boolean = false,
        /** Empty means the real time zone is left alone. */
        val timeZoneId: String = "",
        /** BCP-47 tag; empty means the real locale is left alone. */
        val languageTag: String = "",
        val perPackage: Map<String, Override> = emptyMap()
    )

    data class Override(
        val enabled: Boolean = true,
        /** Empty means this package follows [Config.timeZoneId]. */
        val timeZoneId: String = "",
        /** Empty means this package follows [Config.languageTag]. */
        val languageTag: String = ""
    )

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            timeZoneId = prefs.getString(KEY_TIME_ZONE_ID, null)?.sanitizedTimeZone().orEmpty(),
            languageTag = prefs.getString(KEY_LANGUAGE_TAG, null)?.sanitizedLanguage().orEmpty(),
            perPackage = parseOverrides(prefs.getString(KEY_PER_PACKAGE, null))
        )
    }

    fun saveConfig(context: Context, config: Config) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_TIME_ZONE_ID, config.timeZoneId.sanitizedTimeZone())
            putString(KEY_LANGUAGE_TAG, config.languageTag.sanitizedLanguage())
            putString(KEY_PER_PACKAGE, overridesToJson(config.perPackage).toString())
        }
        // Push the resolved time zone map to the native submodule so targets get
        // their per-app property-area CoW at their next process start.
        SubmoduleConfigFiles.write(context, systemSettingConfig = config)
    }

    fun importSchema(context: Context, schema: SystemSettingConfigSchema) {
        require(schema.schemaVersion in 1..SystemSettingConfigSchema.SCHEMA_VERSION) {
            "Unsupported system setting schema version: ${schema.schemaVersion}"
        }
        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                timeZoneId = schema.timeZoneId.sanitizedTimeZone(),
                languageTag = schema.languageTag.sanitizedLanguage(),
                perPackage = schema.perPackage.entries.asSequence()
                    .filter { PACKAGE_NAME.matches(it.key) }
                    .take(MAX_PACKAGE_OVERRIDES)
                    .associate { (packageName, override) ->
                        packageName to Override(
                            enabled = override.enabled,
                            timeZoneId = override.timeZoneId.sanitizedTimeZone(),
                            languageTag = override.languageTag.sanitizedLanguage()
                        )
                    }
            )
        )
    }

    fun lastModified(context: Context): Long = prefs(context).getLong(KEY_LAST_MODIFIED, 0L)

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return SystemSettingConfigSchema(
            enabled = config.enabled,
            timeZoneId = config.timeZoneId,
            languageTag = config.languageTag,
            perPackage = config.perPackage.mapValues { (_, override) ->
                SystemSettingOverrideSchema(
                    enabled = override.enabled,
                    timeZoneId = override.timeZoneId,
                    languageTag = override.languageTag
                )
            },
            lastModified = lastModified(context)
        ).toJson()
    }

    /**
     * The values that actually apply to [packageName], after folding the
     * override over the global defaults.
     *
     * Kept here rather than in the hook so the manager screen and the eventual
     * hook agree by construction about what "follow the default" resolves to.
     */
    fun resolveFor(config: Config, packageName: String): Override {
        if (!config.enabled) return Override(enabled = false)
        val global = Override(
            enabled = true,
            timeZoneId = config.timeZoneId,
            languageTag = config.languageTag
        )
        val override = config.perPackage[packageName] ?: return global
        if (!override.enabled) return Override(enabled = false)
        return Override(
            enabled = true,
            timeZoneId = override.timeZoneId.ifEmpty { config.timeZoneId },
            languageTag = override.languageTag.ifEmpty { config.languageTag }
        )
    }

    private fun parseOverrides(json: String?): Map<String, Override> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    val entry = root.optJSONObject(packageName) ?: continue
                    put(
                        packageName,
                        Override(
                            enabled = entry.optBoolean(KEY_ENABLED, true),
                            timeZoneId = entry.optString(KEY_TIME_ZONE_ID).sanitizedTimeZone(),
                            languageTag = entry.optString(KEY_LANGUAGE_TAG).sanitizedLanguage()
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun overridesToJson(overrides: Map<String, Override>): JSONObject {
        return JSONObject().apply {
            overrides.toSortedMap().forEach { (packageName, override) ->
                put(
                    packageName,
                    JSONObject()
                        .put(KEY_ENABLED, override.enabled)
                        .put(KEY_TIME_ZONE_ID, override.timeZoneId)
                        .put(KEY_LANGUAGE_TAG, override.languageTag)
                )
            }
        }
    }

    /**
     * Accepts anything shaped like an Olson id without checking it against
     * `TimeZone.getAvailableIDs()`.
     *
     * A backup taken on one device can legitimately name a zone the importing
     * device does not know, and rejecting it there would fail the whole import.
     * An id the platform cannot resolve degrades to "leave the real zone alone"
     * when the hook applies it.
     */
    private fun String.sanitizedTimeZone(): String =
        trim().take(MAX_TIME_ZONE_LENGTH).takeIf { it.isEmpty() || TIME_ZONE_ID.matches(it) }.orEmpty()

    private fun String.sanitizedLanguage(): String =
        trim().take(MAX_LANGUAGE_TAG_LENGTH).takeIf { it.isEmpty() || LANGUAGE_TAG.matches(it) }.orEmpty()

    internal const val MAX_PACKAGE_OVERRIDES = 2_048
    internal const val MAX_TIME_ZONE_LENGTH = 64
    internal const val MAX_LANGUAGE_TAG_LENGTH = 35
    internal val TIME_ZONE_ID = Regex("^[A-Za-z][A-Za-z0-9_+/-]*$")
    internal val LANGUAGE_TAG = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

    private fun prefs(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
