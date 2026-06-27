package asia.nana7mi.arirang.hook.packagelist

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog

import asia.nana7mi.arirang.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

internal class PackageListHookConfig(private val prefsName: String) {
    var enabled = false
        private set

    private var defaultMode = MODE_ALL_VISIBLE
    private var defaultTemplateId: String? = null
    private val templates = mutableMapOf<String, Template>()
    private val appRules = mutableMapOf<String, AppRule>()
    private var lastLoadedTimestamp = -1L

    private val pref by lazy {
        HookConfigFile.xSharedPreferences(prefsName)
    }

    fun loadIfUpdated() {
        val snapshot = ArirangClient.readConfigSnapshot(
            configName = "package_list",
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
        callingPackages: List<String>,
        targetPackage: String
    ): Boolean {
        if (!enabled) return true
        if (callingUid < 10000) return true
        if (targetPackage == "android" || targetPackage == BuildConfig.APPLICATION_ID) return true

        if (callingPackages.isEmpty()) return true
        if (callingPackages.contains(BuildConfig.APPLICATION_ID)) return true

        return callingPackages.all { shouldKeep(it, targetPackage) }
    }

    private fun loadFromSnapshot(snapshot: String) {
        runCatching {
            val json = JSONObject(snapshot)
            val newTimestamp = json.optLong(KEY_LAST_MODIFIED, -1L)
            if (newTimestamp == lastLoadedTimestamp) return
            lastLoadedTimestamp = newTimestamp

            enabled = json.optBoolean(KEY_ENABLED, false)
            defaultMode = json.optString(KEY_DEFAULT_MODE, MODE_ALL_VISIBLE)
            defaultTemplateId = json.optString(KEY_DEFAULT_TEMPLATE_ID).takeIf { it.isNotEmpty() }
            parseTemplates(json.optString(KEY_TEMPLATES).takeIf { it.isNotEmpty() })
            parseAppRules(json.optString(KEY_APP_RULES).takeIf { it.isNotEmpty() })

            HookLog.i(
                HookLog.Module.PACKAGE_LIST,
                "reloaded snapshot enabled=$enabled defaultMode=$defaultMode defaultTemplateId=$defaultTemplateId"
            )
        }.onFailure {
            HookLog.e(HookLog.Module.PACKAGE_LIST, "failed to parse package list snapshot", it)
        }
    }

    private fun loadFromPrefs() {
        if (!pref.file.canRead()) {
            if (lastLoadedTimestamp != PREFS_UNREADABLE_TIMESTAMP) {
                HookLog.d(
                    HookLog.Module.PACKAGE_LIST,
                    "config snapshot empty and XSharedPreferences file ${pref.file.absolutePath} is not readable"
                )
                lastLoadedTimestamp = PREFS_UNREADABLE_TIMESTAMP
                enabled = false
                templates.clear()
                appRules.clear()
            }
            return
        }

        pref.reload()
        val newTimestamp = pref.getLong(KEY_LAST_MODIFIED, -1L)
        if (newTimestamp == lastLoadedTimestamp) return
        lastLoadedTimestamp = newTimestamp

        enabled = pref.getBoolean(KEY_ENABLED, false)
        defaultMode = pref.getString(KEY_DEFAULT_MODE, MODE_ALL_VISIBLE) ?: MODE_ALL_VISIBLE
        defaultTemplateId = pref.getString(KEY_DEFAULT_TEMPLATE_ID, null)
        parseTemplates(pref.getString(KEY_TEMPLATES, null))
        parseAppRules(pref.getString(KEY_APP_RULES, null))

        HookLog.i(
            HookLog.Module.PACKAGE_LIST,
            "reloaded XSharedPreferences enabled=$enabled defaultMode=$defaultMode defaultTemplateId=$defaultTemplateId"
        )
    }

    private fun parseTemplates(templatesJson: String?) {
        templates.clear()
        if (templatesJson.isNullOrBlank()) return

        runCatching {
            val array = JSONArray(templatesJson)
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val id = obj.getString("id")
                val visiblePackages = obj.optJSONArray("visiblePackages").asStringSet()
                val listMode = obj.optString("listMode", MODE_WHITELIST)
                templates[id] = Template(
                    id = id,
                    parentId = obj.optString("parentId").takeIf { it.isNotEmpty() },
                    visiblePackages = visiblePackages,
                    isBlacklist = listMode == MODE_BLACKLIST
                )
            }
        }.onFailure {
            HookLog.w(HookLog.Module.PACKAGE_LIST, "failed to parse package templates: ${it.message}")
        }
    }

    private fun parseAppRules(appRulesJson: String?) {
        appRules.clear()
        if (appRulesJson.isNullOrBlank()) return

        runCatching {
            val array = JSONArray(appRulesJson)
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val packageName = obj.getString("packageName")
                appRules[packageName] = AppRule(
                    mode = obj.optString("mode", MODE_DEFAULT),
                    templateId = obj.optString("templateId").takeIf { it.isNotEmpty() },
                    visiblePackages = obj.optJSONArray("visiblePackages").asStringSet()
                )
            }
        }.onFailure {
            HookLog.w(HookLog.Module.PACKAGE_LIST, "failed to parse package app rules: ${it.message}")
        }
    }

    private fun resolvedTemplatePackages(templateId: String): Pair<Set<String>, Boolean> {
        val visited = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        var current = templates[templateId]
        val isBlacklist = current?.isBlacklist ?: false
        while (current != null && visited.add(current.id)) {
            packages.addAll(current.visiblePackages)
            current = current.parentId?.let { templates[it] }
        }
        return packages to isBlacklist
    }

    private fun shouldKeep(callingPackage: String, targetPackage: String): Boolean {
        if (!enabled) return true
        if (callingPackage == targetPackage) return true
        if (targetPackage == "android" || targetPackage == BuildConfig.APPLICATION_ID) return true

        val rule = appRules[callingPackage]
        val requestedMode = rule?.mode ?: MODE_DEFAULT
        val resolvedMode = if (requestedMode == MODE_DEFAULT) defaultMode else requestedMode
        val resolvedTemplateId = if (requestedMode == MODE_DEFAULT) defaultTemplateId else rule?.templateId

        return when (resolvedMode) {
            MODE_ALL_VISIBLE -> true
            MODE_ALL_HIDDEN -> false
            MODE_TEMPLATE -> keepByTemplate(resolvedTemplateId, targetPackage)
            MODE_CUSTOM -> rule?.visiblePackages?.contains(targetPackage) == true
            else -> true
        }
    }

    private fun keepByTemplate(templateId: String?, targetPackage: String): Boolean {
        if (templateId == null) return true
        val (templatePackages, isBlacklist) = resolvedTemplatePackages(templateId)
        return if (isBlacklist) {
            !templatePackages.contains(targetPackage)
        } else {
            templatePackages.contains(targetPackage)
        }
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
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_DEFAULT_MODE = "default_display_mode"
        private const val KEY_DEFAULT_TEMPLATE_ID = "default_template_id"
        private const val KEY_TEMPLATES = "visibility_templates"
        private const val KEY_APP_RULES = "visibility_app_rules"

        private const val MODE_DEFAULT = "DEFAULT"
        private const val MODE_ALL_VISIBLE = "ALL_VISIBLE"
        private const val MODE_ALL_HIDDEN = "ALL_HIDDEN"
        private const val MODE_TEMPLATE = "TEMPLATE"
        private const val MODE_CUSTOM = "CUSTOM"
        private const val MODE_WHITELIST = "WHITELIST"
        private const val MODE_BLACKLIST = "BLACKLIST"

        private const val PREFS_UNREADABLE_TIMESTAMP = -2L
    }
}
