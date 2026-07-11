package asia.nana7mi.arirang.hook.packagelist

import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import org.json.JSONArray
import org.json.JSONObject

internal class PackageListHookConfig(private val prefsName: String) {
    private data class State(
        val enabled: Boolean = false,
        val defaultMode: String = MODE_ALL_VISIBLE,
        val defaultTemplateId: String? = null,
        val templates: Map<String, Template> = emptyMap(),
        val appRules: Map<String, AppRule> = emptyMap(),
        val resolvedTemplates: Map<String, ResolvedTemplate> = emptyMap(),
        val timestamp: Long = INITIAL_TIMESTAMP
    )

    private data class ResolvedTemplate(
        val packages: Set<String>,
        val isBlacklist: Boolean
    )

    @Volatile
    private var state = State()

    val enabled: Boolean
        get() = state.enabled

    private val pref by lazy {
        HookConfigFile.xSharedPreferences(prefsName)
    }

    @Synchronized
    fun loadIfUpdated() {
        val snapshot = ArirangClient.readConfigSnapshot(
            configName = ConfigIds.PACKAGE_LIST,
            force = false,
            allowBind = false,
            logName = "Package List"
        )

        if (!snapshot.isNullOrBlank()) {
            loadFromSnapshot(snapshot)
        } else {
            loadFromPrefs()
        }
    }

    fun shouldKeepForPackages(
        callingUid: Int,
        callingPackages: Set<String>,
        targetPackage: String
    ): Boolean {
        val current = state
        if (!current.enabled) return true
        if (Math.floorMod(callingUid, PER_USER_RANGE) < 10000) return true
        if (targetPackage == "android" || targetPackage == BuildConfig.APPLICATION_ID) return true

        if (callingPackages.isEmpty()) return false
        if (BuildConfig.APPLICATION_ID in callingPackages) return true
        if (targetPackage in callingPackages) return true

        // A shared UID must not borrow a more permissive sibling package rule.
        return callingPackages.all { shouldKeep(current, it, targetPackage) }
    }

    private fun loadFromSnapshot(snapshot: String) {
        runCatching {
            val json = JSONObject(snapshot)
            val newTimestamp = json.optLongCompat(KEY_LAST_MODIFIED, KEY_LAST_MODIFIED_LEGACY)
            if (newTimestamp == state.timestamp) return

            val templates = parseTemplates(json.optArrayCompat(KEY_TEMPLATES, KEY_TEMPLATES_LEGACY))
            val appRules = parseAppRules(json.optArrayCompat(KEY_APP_RULES, KEY_APP_RULES_LEGACY))
            val updated = buildState(
                enabled = json.optBoolean(KEY_ENABLED, false),
                defaultMode = json.optStringCompat(KEY_DEFAULT_MODE, KEY_DEFAULT_MODE_LEGACY)
                    ?: MODE_ALL_VISIBLE,
                defaultTemplateId = json.optStringCompat(
                    KEY_DEFAULT_TEMPLATE_ID,
                    KEY_DEFAULT_TEMPLATE_ID_LEGACY
                ),
                templates = templates,
                appRules = appRules,
                timestamp = newTimestamp
            )
            state = updated

            HookLog.i(
                HookLog.Module.PACKAGE_LIST,
                "reloaded snapshot enabled=${updated.enabled} defaultMode=${updated.defaultMode}"
            )
        }.onFailure {
            HookLog.e(HookLog.Module.PACKAGE_LIST, "failed to parse package list snapshot", it)
        }
    }

    private fun loadFromPrefs() {
        if (!pref.file.canRead()) {
            if (state.timestamp != PREFS_UNREADABLE_TIMESTAMP) {
                HookLog.d(
                    HookLog.Module.PACKAGE_LIST,
                    "config snapshot empty and XSharedPreferences file is not readable"
                )
                state = State(timestamp = PREFS_UNREADABLE_TIMESTAMP)
            }
            return
        }

        pref.reload()
        val newTimestamp = pref.getLong(KEY_LAST_MODIFIED_LEGACY, INITIAL_TIMESTAMP)
        if (newTimestamp == state.timestamp) return

        val updated = buildState(
            enabled = pref.getBoolean(KEY_ENABLED, false),
            defaultMode = pref.getString(KEY_DEFAULT_MODE_LEGACY, MODE_ALL_VISIBLE) ?: MODE_ALL_VISIBLE,
            defaultTemplateId = pref.getString(KEY_DEFAULT_TEMPLATE_ID_LEGACY, null),
            templates = parseTemplates(parseJsonArray(pref.getString(KEY_TEMPLATES_LEGACY, null))),
            appRules = parseAppRules(parseJsonArray(pref.getString(KEY_APP_RULES_LEGACY, null))),
            timestamp = newTimestamp
        )
        state = updated

        HookLog.i(
            HookLog.Module.PACKAGE_LIST,
            "reloaded XSharedPreferences enabled=${updated.enabled} defaultMode=${updated.defaultMode}"
        )
    }

    private fun buildState(
        enabled: Boolean,
        defaultMode: String,
        defaultTemplateId: String?,
        templates: Map<String, Template>,
        appRules: Map<String, AppRule>,
        timestamp: Long
    ): State {
        return State(
            enabled = enabled,
            defaultMode = defaultMode,
            defaultTemplateId = defaultTemplateId,
            templates = templates,
            appRules = appRules,
            resolvedTemplates = templates.keys.associateWith { resolveTemplate(it, templates) },
            timestamp = timestamp
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

    private fun shouldKeep(current: State, callingPackage: String, targetPackage: String): Boolean {
        if (callingPackage == targetPackage) return true

        val rule = current.appRules[callingPackage]
        val requestedMode = rule?.mode ?: MODE_DEFAULT
        val resolvedMode = if (requestedMode == MODE_DEFAULT) current.defaultMode else requestedMode
        val resolvedTemplateId = if (requestedMode == MODE_DEFAULT) current.defaultTemplateId else rule?.templateId

        return when (resolvedMode) {
            MODE_ALL_VISIBLE -> true
            MODE_ALL_HIDDEN -> false
            MODE_TEMPLATE -> keepByTemplate(current, resolvedTemplateId, targetPackage)
            MODE_CUSTOM -> rule?.visiblePackages?.contains(targetPackage) == true
            else -> true
        }
    }

    private fun keepByTemplate(current: State, templateId: String?, targetPackage: String): Boolean {
        val resolved = templateId?.let(current.resolvedTemplates::get) ?: return true
        return if (resolved.isBlacklist) {
            targetPackage !in resolved.packages
        } else {
            targetPackage in resolved.packages
        }
    }

    private fun JSONObject.optLongCompat(primary: String, legacy: String): Long {
        return when {
            has(primary) -> optLong(primary, INITIAL_TIMESTAMP)
            has(legacy) -> optLong(legacy, INITIAL_TIMESTAMP)
            else -> INITIAL_TIMESTAMP
        }
    }

    private fun JSONObject.optStringCompat(primary: String, legacy: String): String? {
        return when {
            has(primary) -> optString(primary).takeIf { it.isNotBlank() }
            has(legacy) -> optString(legacy).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun JSONObject.optArrayCompat(primary: String, legacy: String): JSONArray? {
        val value = when {
            has(primary) -> opt(primary)
            has(legacy) -> opt(legacy)
            else -> null
        }
        return when (value) {
            is JSONArray -> value
            is String -> value.takeIf { it.isNotBlank() }?.let(::JSONArray)
            else -> null
        }
    }

    private fun parseJsonArray(raw: String?): JSONArray? {
        return raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONArray(it) }.getOrNull() }
    }

    private fun JSONArray?.asStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private data class Template(
        val id: String,
        val parentId: String?,
        val visiblePackages: Set<String>,
        val isBlacklist: Boolean
    )

    private data class AppRule(
        val mode: String,
        val templateId: String?,
        val visiblePackages: Set<String>
    )

    private companion object {
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
        private const val PREFS_UNREADABLE_TIMESTAMP = -2L
        private const val PER_USER_RANGE = 100_000
    }
}
