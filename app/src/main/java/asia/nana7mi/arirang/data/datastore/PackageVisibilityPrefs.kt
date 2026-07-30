package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.PackageListAppRuleSchema
import asia.nana7mi.arirang.data.datastore.schema.PackageListConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.PackageListTemplateSchema
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

object PackageVisibilityPrefs {
    const val PREFS_NAME = "clipboard_visibility_prefs"

    private const val KEY_ENABLED = "enabled"
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
        val config = loadConfig(context)
        return PackageListConfigSchema(
            enabled = config.enabled,
            defaultMode = config.defaultMode.name,
            defaultTemplateId = config.defaultTemplateId,
            templates = config.templates.map { t ->
                PackageListTemplateSchema(
                    id = t.id,
                    name = t.name,
                    parentId = t.parentId,
                    visiblePackages = t.visiblePackages.toList(),
                    listMode = t.listMode.name
                )
            },
            appRules = config.appRules.map { r ->
                PackageListAppRuleSchema(
                    packageName = r.packageName,
                    mode = r.mode.name,
                    templateId = r.templateId,
                    visiblePackages = r.visiblePackages.toList()
                )
            },
            lastModified = lastModified(context)
        ).toJson()
    }

    fun importSchema(context: Context, schema: PackageListConfigSchema) {
        val mode = schema.defaultMode.toDisplayMode(DisplayMode.ALL_VISIBLE)
        val templateIds = mutableSetOf<String>()
        val templates = schema.templates.asSequence()
            .take(MAX_TEMPLATES)
            .mapNotNull { template ->
                val id = template.id.trim().take(MAX_IDENTIFIER_LENGTH)
                if (id.isBlank() || !templateIds.add(id)) return@mapNotNull null
                Template(
                    id = id,
                    name = template.name.trim().take(MAX_NAME_LENGTH),
                    parentId = template.parentId?.trim()?.take(MAX_IDENTIFIER_LENGTH)
                        ?.takeIf { it.isNotBlank() && it != id },
                    visiblePackages = template.visiblePackages.sanitizedPackages(),
                    listMode = runCatching { TemplateListMode.valueOf(template.listMode) }
                        .getOrDefault(TemplateListMode.WHITELIST)
                )
            }
            .toList()
            .map { template -> template.copy(parentId = template.parentId?.takeIf(templateIds::contains)) }

        val rulesByPackage = LinkedHashMap<String, AppRule>()
        schema.appRules.asSequence().take(MAX_APP_RULES).forEach { rule ->
            val packageName = rule.packageName.trim()
            if (!PACKAGE_NAME.matches(packageName)) return@forEach
            val ruleMode = rule.mode.toDisplayMode(DisplayMode.DEFAULT)
            rulesByPackage[packageName] = AppRule(
                packageName = packageName,
                mode = ruleMode,
                templateId = rule.templateId?.trim()?.take(MAX_IDENTIFIER_LENGTH)
                    ?.takeIf { it in templateIds },
                visiblePackages = rule.visiblePackages.sanitizedPackages()
            )
        }

        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                defaultMode = mode,
                defaultTemplateId = schema.defaultTemplateId?.trim()?.take(MAX_IDENTIFIER_LENGTH)
                    ?.takeIf { it in templateIds },
                templates = templates,
                appRules = rulesByPackage.values.toList()
            )
        )
    }

    private fun saveConfig(context: Context, config: Config) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_DEFAULT_MODE, config.defaultMode.name)
            putString(KEY_DEFAULT_TEMPLATE_ID, config.defaultTemplateId)
            putString(KEY_TEMPLATES, gson.toJson(config.templates))
            putString(KEY_APP_RULES, gson.toJson(config.appRules))
            putLong(KEY_LAST_MODIFIED, Date().time)
        }
        SubmoduleConfigFiles.write(context)
    }

    private fun String.toDisplayMode(fallback: DisplayMode): DisplayMode =
        runCatching { DisplayMode.valueOf(this) }.getOrDefault(fallback)

    private fun List<String>.sanitizedPackages(): Set<String> = asSequence()
        .map(String::trim)
        .filter(PACKAGE_NAME::matches)
        .distinct()
        .take(MAX_PACKAGES_PER_LIST)
        .toCollection(linkedSetOf())

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_ENABLED, enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
        }
        SubmoduleConfigFiles.write(context)
    }

    /**
     * Collects related changes so they are committed together.
     *
     * Templates, app rules and the default selection reference each other, and
     * ConfigRegistry validates those cross-references when the submodule
     * config is rebuilt. Committing them one at a time publishes intermediate
     * states that do not satisfy those constraints — deleting a template used
     * by a rule, for instance, briefly leaves the rule pointing at a template
     * that no longer exists.
     */
    class Edit internal constructor() {
        internal var templates: List<Template>? = null
        internal var appRules: List<AppRule>? = null
        internal var defaultSelection: Pair<DisplayMode, String?>? = null

        fun templates(value: List<Template>) { templates = value }

        fun appRules(value: List<AppRule>) { appRules = value }

        fun defaultSelection(mode: DisplayMode, templateId: String?) {
            defaultSelection = mode to templateId
        }
    }

    /** Applies [block]'s changes in one commit followed by one submodule write. */
    fun edit(context: Context, block: Edit.() -> Unit) {
        val pending = Edit().apply(block)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            pending.templates?.let { putString(KEY_TEMPLATES, gson.toJson(normalizedTemplates(it))) }
            pending.appRules?.let { putString(KEY_APP_RULES, gson.toJson(it)) }
            pending.defaultSelection?.let { (mode, templateId) ->
                // A TEMPLATE selection with no template is not a state the
                // validator accepts, so fall back to showing everything.
                val resolvedMode = if (mode == DisplayMode.TEMPLATE && templateId == null) {
                    DisplayMode.ALL_VISIBLE
                } else {
                    mode
                }
                putString(KEY_DEFAULT_MODE, resolvedMode.name)
                putString(KEY_DEFAULT_TEMPLATE_ID, templateId)
            }
            putLong(KEY_LAST_MODIFIED, Date().time)
        }
        SubmoduleConfigFiles.write(context)
    }

    /**
     * Enforces the invariants a persisted template list has to satisfy,
     * regardless of which caller produced it: no self-parent, and no leading or
     * trailing whitespace in the name. Names are user-entered and end up in
     * pickers, card titles and the exported config, where a stray space is
     * invisible but makes two templates look identical.
     */
    private fun normalizedTemplates(templates: List<Template>): List<Template> = templates.map {
        val name = it.name.trim()
        when {
            it.parentId == it.id -> it.copy(name = name, parentId = null)
            name != it.name -> it.copy(name = name)
            else -> it
        }
    }

    fun setDefaultSelection(context: Context, mode: DisplayMode, templateId: String?) {
        edit(context) { defaultSelection(mode, templateId) }
    }

    fun loadTemplates(context: Context): List<Template> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEMPLATES, null)
            ?: return emptyList()
        val type = object : TypeToken<List<Template>>() {}.type
        return runCatching { gson.fromJson<List<Template>>(json, type) }.getOrDefault(emptyList())
    }

    fun saveTemplates(context: Context, templates: List<Template>) {
        edit(context) { templates(templates) }
    }

    fun loadAppRules(context: Context): List<AppRule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_RULES, null)
            ?: return emptyList()
        val type = object : TypeToken<List<AppRule>>() {}.type
        return runCatching { gson.fromJson<List<AppRule>>(json, type) }.getOrDefault(emptyList())
    }

    fun saveAppRules(context: Context, rules: List<AppRule>) {
        edit(context) { appRules(rules) }
    }

    fun createTemplate(
        context: Context,
        name: String,
        parentId: String? = null,
        listMode: TemplateListMode = TemplateListMode.WHITELIST
    ): Template {
        val template = Template(
            id = "template_${Date().time}",
            name = name.trim(),
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

    /**
     * The parent chain above [template], nearest ancestor first.
     *
     * Stops if the chain loops back on itself, so a malformed config renders as
     * a truncated chain rather than hanging the UI.
     */
    fun ancestorChain(template: Template, templates: List<Template>): List<Template> {
        val byId = templates.associateBy { it.id }
        val chain = mutableListOf<Template>()
        val visited = mutableSetOf(template.id)
        var current = template.parentId?.let(byId::get)
        while (current != null && visited.add(current.id)) {
            chain += current
            current = current.parentId?.let(byId::get)
        }
        return chain
    }

    /**
     * The templates [child] can inherit from without forming a cycle — that is,
     * every template that is not [child] itself and does not already have
     * [child] somewhere in its own parent chain.
     *
     * Worth filtering at the point of choice: [ConfigRegistry] only rejects a
     * template that is its own parent, so a two-template cycle passes validation
     * and then silently truncates in [resolvedTemplatePackages], which drops
     * packages the user believes are inherited.
     */
    fun eligibleParents(child: Template, templates: List<Template>): List<Template> {
        val byId = templates.associateBy { it.id }

        fun inheritsFromChild(candidate: Template): Boolean {
            val visited = mutableSetOf<String>()
            var current: Template? = candidate
            while (current != null && visited.add(current.id)) {
                if (current.id == child.id) return true
                current = current.parentId?.let(byId::get)
            }
            return false
        }

        return templates.filterNot(::inheritsFromChild)
    }

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

    private const val MAX_TEMPLATES = 256
    private const val MAX_APP_RULES = 4_096
    private const val MAX_PACKAGES_PER_LIST = 4_096
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_NAME_LENGTH = 256
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
}
