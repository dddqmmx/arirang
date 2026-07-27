package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.AppConfigSchema

object AppPreferences {

    private const val PREFS_NAME = "arirang_prefs"

    private const val KEY_SETUP_COMPLETED = "is_setup_completed"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_LAST_MODIFIED = "last_modified"
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    fun setSetupCompleted(context: Context, isCompleted: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SETUP_COMPLETED, isCompleted)
            putLong(KEY_LAST_MODIFIED, System.currentTimeMillis())
        }
    }

    fun isSetupCompleted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setLanguage(context: Context, languageCode: String) {
        prefs(context).edit {
            putString(KEY_LANGUAGE, languageCode)
            putLong(KEY_LAST_MODIFIED, System.currentTimeMillis())
        }
    }

    fun getLanguage(context: Context): String? {
        return prefs(context).getString(KEY_LANGUAGE, null)
    }

    fun lastModified(context: Context): Long = prefs(context).getLong(KEY_LAST_MODIFIED, 0L)

    fun buildHookSnapshot(context: Context): String = AppConfigSchema(
        setupCompleted = isSetupCompleted(context),
        language = getLanguage(context) ?: "system",
        lastModified = lastModified(context)
    ).toJson()

    fun importSchema(context: Context, schema: AppConfigSchema) {
        require(schema.schemaVersion in 1..AppConfigSchema.SCHEMA_VERSION)
        require(schema.language in SUPPORTED_LANGUAGES) { "Unsupported language: ${schema.language}" }
        check(
            prefs(context).edit()
                .putBoolean(KEY_SETUP_COMPLETED, schema.setupCompleted)
                .putString(KEY_LANGUAGE, schema.language)
                .putLong(KEY_LAST_MODIFIED, System.currentTimeMillis())
                .commit()
        ) { "Unable to persist app preferences" }
    }

    /**
     * Language tags accepted by config import and validation. Single source of
     * truth: ConfigRegistry.validateApp reads this rather than keeping a copy.
     *
     * Must stay in step with the `language_codes` string-array that the settings
     * picker renders. NOTE: "ko" is offered and accepted here, but
     * `localeFilters` in app/build.gradle.kts ships only en/zh-rCN/ja and there
     * is no values-ko/, so selecting Korean currently falls back to the default
     * strings. Either add the translations or drop it from all three lists.
     */
    val SUPPORTED_LANGUAGES = setOf("system", "en", "zh-CN", "ja", "ko")

}
