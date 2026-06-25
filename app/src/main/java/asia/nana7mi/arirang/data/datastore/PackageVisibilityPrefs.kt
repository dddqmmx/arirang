package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.util.Date

object PackageVisibilityPrefs {
    private const val PREFS_NAME = "clipboard_visibility_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_VISIBLE_LIST = "visible_list"
    private const val KEY_INVISIBLE_LIST = "invisible_list"
    private const val KEY_LAST_MODIFIED = "last_modified"

    private const val KEY_DEFAULT_MODE = "default_display_mode"
    private const val KEY_DEFAULT_TEMPLATE_ID = "default_template_id"
    private const val KEY_TEMPLATES = "visibility_templates"
    private const val KEY_APP_RULES = "visibility_app_rules"

    private val gson = Gson()

    enum class DisplayMode {
        DEFAULT,
        ALL_VISIBLE,
        ALL_HIDDEN,
        TEMPLATE,
        CUSTOM
    }

    enum class TemplateListMode {
        WHITELIST,
        BLACKLIST
    }

    data class Template(
        val id: String,
        val name: String,
        val parentId: String? = null,
        val visiblePackages: Set<String> = emptySet(),
        val listMode: TemplateListMode = TemplateListMode.WHITELIST
    )

    data class AppRule(
        val packageName: String,
        val mode: DisplayMode = DisplayMode.DEFAULT,
        val templateId: String? = null,
        val visiblePackages: Set<String> = emptySet()
    )

    data class Config(
        val enabled: Boolean,
        val defaultMode: DisplayMode,
        val defaultTemplateId: String?,
        val templates: List<Template>,
        val appRules: List<AppRule>
    )

    fun loadConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            defaultMode = runCatching {
                DisplayMode.valueOf(prefs.getString(KEY_DEFAULT_MODE, DisplayMode.ALL_VISIBLE.name)!!)
            }.getOrDefault(DisplayMode.ALL_VISIBLE),
            defaultTemplateId = prefs.getString(KEY_DEFAULT_TEMPLATE_ID, null),
            templates = loadTemplates(context),
            appRules = loadAppRules(context)
        )
    }

    fun lastModified(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return JSONObject()
            .put(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, false))
            .put(KEY_LAST_MODIFIED, lastModified(context))
            .put(KEY_DEFAULT_MODE, prefs.getString(KEY_DEFAULT_MODE, DisplayMode.ALL_VISIBLE.name))
            .put(KEY_DEFAULT_TEMPLATE_ID, prefs.getString(KEY_DEFAULT_TEMPLATE_ID, null))
            .put(KEY_TEMPLATES, prefs.getString(KEY_TEMPLATES, null))
            .put(KEY_APP_RULES, prefs.getString(KEY_APP_RULES, null))
            .toString()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
        }
    }

    fun setDefaultSelection(context: Context, mode: DisplayMode, templateId: String?) {
        val templates = loadTemplates(context)
        val resolvedMode = if (mode == DisplayMode.TEMPLATE && templateId == null) {
            DisplayMode.ALL_VISIBLE
        } else {
            mode
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_DEFAULT_MODE, resolvedMode.name)
            putString(KEY_DEFAULT_TEMPLATE_ID, templateId)
        }
        syncLegacyDefault(context, resolvedMode, templateId, templates)
    }

    fun loadTemplates(context: Context): List<Template> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEMPLATES, null)
            ?: return emptyList()
        val type = object : TypeToken<List<Template>>() {}.type
        return runCatching { gson.fromJson<List<Template>>(json, type) }.getOrDefault(emptyList())
    }

    fun saveTemplates(context: Context, templates: List<Template>) {
        val cleaned = templates.map { template ->
            if (template.parentId == template.id) template.copy(parentId = null) else template
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_TEMPLATES, gson.toJson(cleaned))
        }
        val config = loadConfig(context)
        syncLegacyDefault(context, config.defaultMode, config.defaultTemplateId, cleaned)
    }

    fun loadAppRules(context: Context): List<AppRule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_RULES, null)
            ?: return emptyList()
        val type = object : TypeToken<List<AppRule>>() {}.type
        return runCatching { gson.fromJson<List<AppRule>>(json, type) }.getOrDefault(emptyList())
    }

    fun saveAppRules(context: Context, rules: List<AppRule>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_APP_RULES, gson.toJson(rules))
            putLong(KEY_LAST_MODIFIED, Date().time)
        }
    }

    fun createTemplate(
        context: Context,
        name: String,
        parentId: String? = null,
        listMode: TemplateListMode = TemplateListMode.WHITELIST
    ): Template {
        val template = Template(
            id = "template_${Date().time}",
            name = name,
            parentId = parentId,
            listMode = listMode
        )
        saveTemplates(context, loadTemplates(context) + template)
        return template
    }

    fun resolvedVisiblePackages(template: Template, templates: List<Template>): Set<String> {
        return resolvedTemplatePackages(template, templates)
    }

    fun resolvedTemplatePackages(template: Template, templates: List<Template>): Set<String> {
        val byId = templates.associateBy { it.id }
        val visited = mutableSetOf<String>()

        fun collect(current: Template): Set<String> {
            if (!visited.add(current.id)) return emptySet()
            val parentPackages = current.parentId
                ?.let { byId[it] }
                ?.let { collect(it) }
                ?: emptySet()
            return parentPackages + current.visiblePackages
        }

        return collect(template)
    }

    fun resolvedTemplateListMode(template: Template): TemplateListMode = template.listMode

    fun resolveRuleVisiblePackages(rule: AppRule?, config: Config): Set<String>? {
        val mode = rule?.mode ?: config.defaultMode
        val templateId = rule?.templateId ?: config.defaultTemplateId
        return when (mode) {
            DisplayMode.DEFAULT -> resolveDefaultVisiblePackages(config)
            DisplayMode.ALL_VISIBLE -> null
            DisplayMode.ALL_HIDDEN -> emptySet()
            DisplayMode.TEMPLATE -> config.templates.firstOrNull { it.id == templateId }?.let { template ->
                when (resolvedTemplateListMode(template)) {
                    TemplateListMode.WHITELIST -> resolvedTemplatePackages(template, config.templates)
                    TemplateListMode.BLACKLIST -> null
                }
            }
            DisplayMode.CUSTOM -> rule?.visiblePackages ?: emptySet()
        }
    }

    fun resolveDefaultVisiblePackages(config: Config): Set<String>? {
        return when (config.defaultMode) {
            DisplayMode.ALL_VISIBLE, DisplayMode.DEFAULT -> null
            DisplayMode.ALL_HIDDEN -> emptySet()
            DisplayMode.TEMPLATE -> config.templates.firstOrNull { it.id == config.defaultTemplateId }?.let { template ->
                when (resolvedTemplateListMode(template)) {
                    TemplateListMode.WHITELIST -> resolvedTemplatePackages(template, config.templates)
                    TemplateListMode.BLACKLIST -> null
                }
            } ?: emptySet()
            DisplayMode.CUSTOM -> emptySet()
        }
    }

    private fun syncLegacyDefault(
        context: Context,
        mode: DisplayMode,
        templateId: String?,
        templates: List<Template>
    ) {
        val template = templates.firstOrNull { it.id == templateId }
        val visiblePackages = when (mode) {
            DisplayMode.ALL_VISIBLE, DisplayMode.DEFAULT -> null
            DisplayMode.ALL_HIDDEN -> emptySet()
            DisplayMode.TEMPLATE -> template?.takeIf { it.listMode == TemplateListMode.WHITELIST }
                ?.let { resolvedTemplatePackages(it, templates) } ?: emptySet()
            DisplayMode.CUSTOM -> emptySet()
        }
        val invisiblePackages = template?.takeIf {
            mode == DisplayMode.TEMPLATE && it.listMode == TemplateListMode.BLACKLIST
        }?.let { resolvedTemplatePackages(it, templates) }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (invisiblePackages != null) {
                putInt(KEY_MODE, 1)
                putStringSet(KEY_INVISIBLE_LIST, invisiblePackages)
                putStringSet(KEY_VISIBLE_LIST, emptySet())
            } else if (visiblePackages == null) {
                putInt(KEY_MODE, 1)
                putStringSet(KEY_INVISIBLE_LIST, emptySet())
                putStringSet(KEY_VISIBLE_LIST, emptySet())
            } else {
                putInt(KEY_MODE, 0)
                putStringSet(KEY_VISIBLE_LIST, visiblePackages)
                putStringSet(KEY_INVISIBLE_LIST, emptySet())
            }
            putLong(KEY_LAST_MODIFIED, Date().time)
        }
    }
}
