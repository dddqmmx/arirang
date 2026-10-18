package asia.nana7mi.arirang.hook.packagelist

import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.xposed.HookLog
import org.json.JSONArray

private const val PREFS_NAME = "clipboard_visibility_prefs"
private const val REFRESH_INTERVAL_MS = 300L

internal data class Template(
    val id: String,
    val parentId: String?,
    val visiblePackages: Set<String>,
    val isBlacklist: Boolean
)

internal data class AppRule(
    val mode: String,
    val templateId: String?,
    val visiblePackages: Set<String>
)

internal data class ResolvedTemplate(
    val packages: Set<String>,
    val isBlacklist: Boolean
)

/**
 * The package-visibility config, decoded.
 *
 * The rule itself lives here rather than in the module, because the module asks one question —
 * "may this caller see this package?" — and everything needed to answer it is this state plus the
 * uid the question is about.
 */
internal data class PackageListState(
    val enabled: Boolean = false,
    val defaultMode: String = MODE_ALL_VISIBLE,
    val defaultTemplateId: String? = null,
    val templates: Map<String, Template> = emptyMap(),
    val appRules: Map<String, AppRule> = emptyMap(),
    val resolvedTemplates: Map<String, ResolvedTemplate> = emptyMap(),
    val timestamp: Long = INITIAL_TIMESTAMP
) {

    fun shouldKeepForPackages(
        callingUid: Int,
        callingPackages: Set<String>,
        targetPackage: String
    ): Boolean {
        if (!enabled) return true
        if (Math.floorMod(callingUid, PER_USER_RANGE) < FIRST_APPLICATION_UID) return true
        if (targetPackage == "android" || targetPackage == BuildConfig.APPLICATION_ID) return true

        // Callers whose UID resolves to no package (isolated/sandboxed processes such as
        // Chrome/WebView renderer sandboxes and GMS chimera containers) must fail open.
        // Failing closed hides every package from them, including their own, which crashes
        // the process during app startup (LoadedApk.initializeJavaContextClassLoader).
        if (callingPackages.isEmpty()) return true
        if (BuildConfig.APPLICATION_ID in callingPackages) return true
        if (targetPackage in callingPackages) return true

        // A shared UID must not borrow a more permissive sibling package rule.
        return callingPackages.all { shouldKeep(it, targetPackage) }
    }

    private fun shouldKeep(callingPackage: String, targetPackage: String): Boolean {
        if (callingPackage == targetPackage) return true

        val rule = appRules[callingPackage]
        val requestedMode = rule?.mode ?: MODE_DEFAULT
        val resolvedMode = if (requestedMode == MODE_DEFAULT) defaultMode else requestedMode
        val resolvedTemplateId =
            if (requestedMode == MODE_DEFAULT) defaultTemplateId else rule?.templateId

        return when (resolvedMode) {
            MODE_ALL_VISIBLE -> true
            MODE_ALL_HIDDEN -> false
            MODE_TEMPLATE -> keepByTemplate(resolvedTemplateId, targetPackage)
            MODE_CUSTOM -> rule?.visiblePackages?.contains(targetPackage) == true
            else -> true
        }
    }

    private fun keepByTemplate(templateId: String?, targetPackage: String): Boolean {
        val resolved = templateId?.let(resolvedTemplates::get) ?: return true
        return if (resolved.isBlacklist) {
            targetPackage !in resolved.packages
        } else {
            targetPackage in resolved.packages
        }
    }
}

/**
 * The package-visibility config.
 *
 * One decoder serves both sources. They spell every key differently — the snapshot says
 * `defaultMode`/`templates`/`appRules`, the prefs file says
 * `default_display_mode`/`visibility_templates`/`visibility_app_rules` — and templates and rules
 * arrive as a real JSON array from one and as JSON *text* from the other. Each source used to
 * have its own parser for that, which is how a rule could mean one thing on the snapshot path and
 * another on the prefs path.
 *
 * `allowBind` is false: this config is read from hooks that run on every PackageManager call, so
 * starting the service bind is the system-server module's job, not this one's.
 */
internal object PackageListConfig : ConfigSpec<PackageListState>(
    id = ConfigIds.PACKAGE_LIST,
    typeName = "Package List",
    logModule = HookLog.Module.PACKAGE_LIST,
    fallback = PackageListState(),
    sources = listOf(
        ServiceSnapshotSource(
            configName = ConfigIds.PACKAGE_LIST,
            logName = "Package List",
            allowBind = false,
        ),
        RemotePrefsSource(PREFS_NAME),
    ),
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    decode = ::decode,
) {
    /**
     * Any filtering at all.
     *
     * Every PackageManager hook asks this first, to skip the caller lookup when the feature is
     * off. `shouldKeepForPackages` checks it again for the callers that reach it directly.
     */
    val enabled = guard("enabled") { it.enabled }
}

private fun decode(values: ConfigValues): PackageListState? {
    if (values.isEmpty()) return null
    val templates = parseTemplates(values.jsonArray(KEY_TEMPLATES, KEY_TEMPLATES_LEGACY))
    return PackageListState(
        enabled = values.boolean(KEY_ENABLED) ?: false,
        defaultMode = values.string(KEY_DEFAULT_MODE, KEY_DEFAULT_MODE_LEGACY) ?: MODE_ALL_VISIBLE,
        defaultTemplateId = values.string(KEY_DEFAULT_TEMPLATE_ID, KEY_DEFAULT_TEMPLATE_ID_LEGACY),
        templates = templates,
        appRules = parseAppRules(values.jsonArray(KEY_APP_RULES, KEY_APP_RULES_LEGACY)),
        resolvedTemplates = templates.keys.associateWith { resolveTemplate(it, templates) },
        timestamp = values.long(KEY_LAST_MODIFIED, KEY_LAST_MODIFIED_LEGACY) ?: INITIAL_TIMESTAMP,
    )
}

private fun parseTemplates(array: JSONArray?): Map<String, Template> {
    if (array == null) return emptyMap()
    return buildMap {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            put(
                id,
                Template(
                    id = id,
                    parentId = obj.optString("parentId").takeIf { it.isNotBlank() },
                    visiblePackages = obj.optJSONArray("visiblePackages").asStringSet(),
                    isBlacklist = obj.optString("listMode", MODE_WHITELIST) == MODE_BLACKLIST
                )
            )
        }
    }
}

private fun parseAppRules(array: JSONArray?): Map<String, AppRule> {
    if (array == null) return emptyMap()
    return buildMap {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val packageName = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
            put(
                packageName,
                AppRule(
                    mode = obj.optString("mode", MODE_DEFAULT),
                    templateId = obj.optString("templateId").takeIf { it.isNotBlank() },
                    visiblePackages = obj.optJSONArray("visiblePackages").asStringSet()
                )
            )
        }
    }
}

private fun resolveTemplate(templateId: String, templates: Map<String, Template>): ResolvedTemplate {
    val visited = mutableSetOf<String>()
    val packages = mutableSetOf<String>()
    val startingTemplate = templates[templateId]
    var current = startingTemplate
    while (current != null && visited.add(current.id)) {
        packages.addAll(current.visiblePackages)
        current = current.parentId?.let(templates::get)
    }
    // The selected (child) template mode governs the inherited package set. This matches
    // the manager UI resolver and makes mixed-mode inheritance deterministic.
    return ResolvedTemplate(packages, startingTemplate?.isBlacklist ?: false)
}

private fun JSONArray?.asStringSet(): Set<String> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private const val KEY_ENABLED = "enabled"
private const val KEY_LAST_MODIFIED = "lastModified"
private const val KEY_DEFAULT_MODE = "defaultMode"
private const val KEY_DEFAULT_TEMPLATE_ID = "defaultTemplateId"
private const val KEY_TEMPLATES = "templates"
private const val KEY_APP_RULES = "appRules"

private const val KEY_LAST_MODIFIED_LEGACY = "last_modified"
private const val KEY_DEFAULT_MODE_LEGACY = "default_display_mode"
private const val KEY_DEFAULT_TEMPLATE_ID_LEGACY = "default_template_id"
private const val KEY_TEMPLATES_LEGACY = "visibility_templates"
private const val KEY_APP_RULES_LEGACY = "visibility_app_rules"

private const val MODE_DEFAULT = "DEFAULT"
private const val MODE_ALL_VISIBLE = "ALL_VISIBLE"
private const val MODE_ALL_HIDDEN = "ALL_HIDDEN"
private const val MODE_TEMPLATE = "TEMPLATE"
private const val MODE_CUSTOM = "CUSTOM"
private const val MODE_WHITELIST = "WHITELIST"
private const val MODE_BLACKLIST = "BLACKLIST"

private const val INITIAL_TIMESTAMP = -1L
private const val PER_USER_RANGE = 100_000
private const val FIRST_APPLICATION_UID = 10_000
