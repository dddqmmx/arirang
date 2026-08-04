package asia.nana7mi.arirang.selfcheck.checker

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Verifies that every in-process default-locale channel agrees.
 *
 * The per-app language spoof (`FuckAppLocale`) rewrites package locales in
 * system_server so this process's Configuration and LocaleManager query both
 * report the spoofed tag. Resources / `Locale.getDefault()` / `LocaleList`
 * defaults must then all line up. A mismatch between channels is the
 * observable leak this check surfaces (e.g. Configuration was never applied
 * while LocaleManager still returns the spoofed query result).
 */
class LocaleChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_locale_title
    override val navChipId: Int = R.id.navLocaleChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var leaked = false

        val configPrimary = runCatching {
            primaryTag(context.resources.configuration)
        }.getOrNull()
        lines.add(context.getString(R.string.self_check_locale_config, configPrimary ?: "-"))

        val localeDefault = runCatching { Locale.getDefault().toLanguageTag() }.getOrNull()
        lines.add(context.getString(R.string.self_check_locale_java, localeDefault ?: "-"))

        val localeListDefault = runCatching {
            primaryTag(LocaleList.getDefault())
        }.getOrNull()
        lines.add(context.getString(R.string.self_check_locale_list, localeListDefault ?: "-"))

        val appLocales = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                null
            } else {
                val manager = context.getSystemService(LocaleManager::class.java) ?: return@runCatching null
                val list = manager.applicationLocales
                if (list.isEmpty) {
                    // Empty means "follow system"; surface that explicitly so a
                    // spoof that never reached Configuration is visible as a
                    // mismatch against a non-empty Configuration primary.
                    ""
                } else {
                    primaryTag(list)
                }
            }
        }.getOrNull()
        lines.add(
            context.getString(
                R.string.self_check_locale_app,
                when (appLocales) {
                    null -> "-"
                    "" -> context.getString(R.string.self_check_locale_app_empty)
                    else -> appLocales
                }
            )
        )

        val resourcesConfig = runCatching {
            primaryTag(context.applicationContext.resources.configuration)
        }.getOrNull()
        lines.add(context.getString(R.string.self_check_locale_app_config, resourcesConfig ?: "-"))

        // Compare language+script+region only: Configuration may carry a full
        // BCP-47 tag while Locale.getDefault() omits extensions, and an empty
        // LocaleManager result means "system default" which should equal the
        // Configuration primary when no app-specific override is in force.
        val normalized = linkedMapOf(
            "config" to configPrimary?.let(::normalizeTag),
            "java" to localeDefault?.let(::normalizeTag),
            "localeList" to localeListDefault?.let(::normalizeTag),
            "appConfig" to resourcesConfig?.let(::normalizeTag)
        )
        // LocaleManager empty → treat as matching the Configuration primary
        // (platform semantics: no app override). Non-empty must agree.
        if (appLocales != null) {
            normalized["appLocales"] = if (appLocales.isEmpty()) {
                configPrimary?.let(::normalizeTag)
            } else {
                normalizeTag(appLocales)
            }
        }

        val present = normalized.values.filterNotNull()
        if (present.isNotEmpty() && present.distinct().size > 1) {
            leaked = true
        }

        val mismatchMarker = context.getString(R.string.self_check_channel_mismatch)
        val expected = present.firstOrNull().orEmpty()
        normalized.forEach { (label, value) ->
            if (value != null && expected.isNotEmpty() && value != expected) {
                lines.add(
                    context.getString(R.string.self_check_locale_mismatch, label, value, mismatchMarker)
                )
            }
        }

        when {
            present.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                context.getString(R.string.self_check_locale_hidden)
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "Locale CheckResult: \n${it.content}") }
            leaked -> CheckResult(
                CheckState.LEAKED,
                context.getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "Locale CheckResult: \n${it.content}") }
            else -> CheckResult(
                CheckState.VISIBLE,
                context.getString(R.string.self_check_status_consistent),
                lines.joinToString("\n")
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "Locale CheckResult: \n${it.content}") }
        }
    }

    private fun primaryTag(configuration: Configuration): String? {
        val locales = configuration.locales
        if (locales.isEmpty) return null
        return locales[0]?.toLanguageTag()
    }

    private fun primaryTag(locales: LocaleList): String? {
        if (locales.isEmpty) return null
        return locales[0]?.toLanguageTag()
    }

    /**
     * Collapse BCP-47 tags to language[-script][-region] lower-cased so that
     * `en-US` and `en-US-u-fw-mon` compare equal, and `zh-Hans-CN` stays
     * distinct from `zh-Hant-TW`.
     */
    private fun normalizeTag(tag: String): String {
        if (tag.isEmpty()) return tag
        return runCatching {
            val locale = Locale.forLanguageTag(tag)
            buildString {
                append(locale.language.lowercase(Locale.ROOT))
                if (locale.script.isNotEmpty()) {
                    append('-')
                    append(locale.script)
                }
                if (locale.country.isNotEmpty()) {
                    append('-')
                    append(locale.country.uppercase(Locale.ROOT))
                }
            }.ifEmpty { tag.lowercase(Locale.ROOT) }
        }.getOrDefault(tag.lowercase(Locale.ROOT))
    }
}
