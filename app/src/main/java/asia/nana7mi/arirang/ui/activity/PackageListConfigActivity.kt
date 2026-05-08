package asia.nana7mi.arirang.ui.activity

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.AppCompatSpinner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.AppRule
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.DisplayMode
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.Template
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.TemplateListMode
import asia.nana7mi.arirang.model.PackageVisibilityAppInfo
import asia.nana7mi.arirang.ui.activity.packagelist.PackageCustomListActivity
import asia.nana7mi.arirang.ui.activity.packagelist.PackageTemplateManagerActivity
import asia.nana7mi.arirang.ui.adapter.PackageVisibilityAppAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackageListConfigActivity : BaseActivity() {

    private lateinit var featureStatusIcon: ImageView
    private lateinit var defaultDisplaySpinner: AppCompatSpinner
    private lateinit var searchEditText: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PackageVisibilityAppAdapter

    private lateinit var config: PackageVisibilityPrefs.Config
    private var suppressDefaultSelection = false
    private val allApps = mutableListOf<PackageVisibilityAppInfo>()
    private val defaultSpinnerItems = mutableListOf<DefaultSelectionItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_package_list_config)
        setSupportActionBar(findViewById(R.id.toolbar))

        initViews()
        setupListeners()
        refreshConfig()
        lifecycleScope.launch { loadInstalledApps() }
    }

    override fun onResume() {
        super.onResume()
        if (::config.isInitialized) {
            refreshConfig()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_package_visibility_config, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_template_manager) {
            showTemplateManager()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun initViews() {
        featureStatusIcon = findViewById(R.id.featureStatusIcon)
        defaultDisplaySpinner = findViewById(R.id.defaultDisplaySpinner)
        searchEditText = findViewById(R.id.searchEditText)
        recyclerView = findViewById(R.id.appListRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PackageVisibilityAppAdapter(mutableListOf()) { showAppRuleDialog(it) }
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        defaultDisplaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressDefaultSelection) return
                val item = defaultSpinnerItems.getOrNull(position) ?: return
                when (item.action) {
                    DefaultAction.ALL_VISIBLE -> updateDefaultSelection(DisplayMode.ALL_VISIBLE, null)
                    DefaultAction.ALL_HIDDEN -> updateDefaultSelection(DisplayMode.ALL_HIDDEN, null)
                    DefaultAction.TEMPLATE -> updateDefaultSelection(DisplayMode.TEMPLATE, item.templateId)
                    DefaultAction.NEW_TEMPLATE -> {
                        restoreDefaultSpinnerSelection()
                        showCreateTemplateDialog { template ->
                            updateDefaultSelection(DisplayMode.TEMPLATE, template.id)
                            openTemplateEditor(template)
                        }
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        featureStatusIcon.setOnClickListener {
            PackageVisibilityPrefs.setEnabled(this, !config.enabled)
            refreshConfig()
        }
    }

    private fun refreshConfig() {
        config = PackageVisibilityPrefs.loadConfig(this)
        updateFeatureStatusUI()
        rebuildDefaultSpinner()
        rebuildAppStatuses()
    }

    private fun updateFeatureStatusUI() {
        featureStatusIcon.setImageResource(
            if (config.enabled) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
        )
    }

    private fun rebuildDefaultSpinner() {
        defaultSpinnerItems.clear()
        defaultSpinnerItems.add(DefaultSelectionItem(getString(R.string.display_all_visible), DefaultAction.ALL_VISIBLE))
        defaultSpinnerItems.add(DefaultSelectionItem(getString(R.string.display_all_hidden), DefaultAction.ALL_HIDDEN))
        defaultSpinnerItems.addAll(config.templates.map {
            DefaultSelectionItem(it.name, DefaultAction.TEMPLATE, it.id)
        })
        defaultSpinnerItems.add(DefaultSelectionItem(getString(R.string.template_new), DefaultAction.NEW_TEMPLATE))

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            defaultSpinnerItems.map { it.title }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        suppressDefaultSelection = true
        defaultDisplaySpinner.adapter = spinnerAdapter
        restoreDefaultSpinnerSelection()
        suppressDefaultSelection = false
    }

    private fun restoreDefaultSpinnerSelection() {
        val position = when (config.defaultMode) {
            DisplayMode.ALL_HIDDEN -> 1
            DisplayMode.TEMPLATE -> defaultSpinnerItems.indexOfFirst {
                it.action == DefaultAction.TEMPLATE && it.templateId == config.defaultTemplateId
            }.takeIf { it >= 0 } ?: 0
            else -> 0
        }
        defaultDisplaySpinner.setSelection(position, false)
    }

    private fun updateDefaultSelection(mode: DisplayMode, templateId: String?) {
        PackageVisibilityPrefs.setDefaultSelection(this, mode, templateId)
        refreshConfig()
    }

    private suspend fun loadInstalledApps() {
        val items = withContext(Dispatchers.IO) {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA).map { pkg ->
                val isSystem = (pkg.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                PackageVisibilityAppInfo(
                    appName = packageManager.getApplicationLabel(pkg).toString(),
                    packageName = pkg.packageName,
                    icon = null,
                    isSystemApp = isSystem,
                    statusText = "",
                    isConfigured = false
                )
            }.sortedBy { it.appName.lowercase() }
        }
        allApps.clear()
        allApps.addAll(items)
        rebuildAppStatuses()
    }

    private fun rebuildAppStatuses() {
        if (allApps.isEmpty()) return
        val rules = config.appRules.associateBy { it.packageName }
        val updated = allApps.map { app ->
            val rule = rules[app.packageName]
            app.copy(
                statusText = statusTextForRule(rule),
                isConfigured = rule != null && rule.mode != DisplayMode.DEFAULT
            )
        }.sortedWith(compareByDescending<PackageVisibilityAppInfo> { it.isConfigured }.thenBy { it.appName.lowercase() })
        allApps.clear()
        allApps.addAll(updated)
        applyFilters()
    }

    private fun applyFilters() {
        val query = searchEditText.text.toString().lowercase()
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        adapter.setList(filtered)
    }

    private fun statusTextForRule(rule: AppRule?): String {
        if (rule == null || rule.mode == DisplayMode.DEFAULT) return defaultStatusText()
        return when (rule.mode) {
            DisplayMode.ALL_VISIBLE -> getString(R.string.display_all_visible)
            DisplayMode.ALL_HIDDEN -> getString(R.string.display_all_hidden)
            DisplayMode.TEMPLATE -> {
                val templateName = config.templates.firstOrNull { it.id == rule.templateId }?.name
                    ?: getString(R.string.template_none)
                getString(R.string.rule_status_template, templateName)
            }
            DisplayMode.CUSTOM -> getString(R.string.rule_status_custom, rule.visiblePackages.size)
            DisplayMode.DEFAULT -> defaultStatusText()
        }
    }

    private fun defaultStatusText(): String {
        return when (config.defaultMode) {
            DisplayMode.ALL_VISIBLE, DisplayMode.DEFAULT -> getString(R.string.display_all_visible)
            DisplayMode.ALL_HIDDEN -> getString(R.string.display_all_hidden)
            DisplayMode.TEMPLATE -> {
                val templateName = config.templates.firstOrNull { it.id == config.defaultTemplateId }?.name
                    ?: getString(R.string.template_none)
                getString(R.string.rule_status_template, templateName)
            }
            DisplayMode.CUSTOM -> getString(R.string.display_all_hidden)
        }
    }

    private fun showAppRuleDialog(app: PackageVisibilityAppInfo) {
        val options = buildList {
            add(AppRuleOption(getString(R.string.template_new), AppRuleAction.NEW_TEMPLATE))
            add(AppRuleOption(getString(R.string.display_all_visible), AppRuleAction.ALL_VISIBLE))
            add(AppRuleOption(getString(R.string.display_all_hidden), AppRuleAction.ALL_HIDDEN))
            add(AppRuleOption(getString(R.string.display_custom), AppRuleAction.CUSTOM))
            config.templates.forEach {
                add(AppRuleOption(it.name, AppRuleAction.TEMPLATE, it.id))
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_visibility_title, app.appName))
            .setItems(options.map { it.title }.toTypedArray()) { _, which ->
                handleAppRuleSelection(app, options[which])
            }
            .show()
    }

    private fun handleAppRuleSelection(
        app: PackageVisibilityAppInfo,
        option: AppRuleOption
    ) {
        when (option.action) {
            AppRuleAction.NEW_TEMPLATE -> showCreateTemplateDialog { template ->
                saveAppRule(AppRule(app.packageName, DisplayMode.TEMPLATE, template.id))
                openTemplateEditor(template)
            }
            AppRuleAction.ALL_VISIBLE -> saveAppRule(AppRule(app.packageName, DisplayMode.ALL_VISIBLE))
            AppRuleAction.ALL_HIDDEN -> saveAppRule(AppRule(app.packageName, DisplayMode.ALL_HIDDEN))
            AppRuleAction.TEMPLATE -> saveAppRule(AppRule(app.packageName, DisplayMode.TEMPLATE, option.templateId))
            AppRuleAction.CUSTOM -> {
                startActivity(
                    PackageCustomListActivity.forApp(
                        this,
                        app.packageName,
                        getString(R.string.app_visibility_title, app.appName)
                    )
                )
            }
        }
    }

    private fun saveAppRule(rule: AppRule) {
        val updated = config.appRules
            .filterNot { it.packageName == rule.packageName }
            .let { rules -> rules + rule }
        PackageVisibilityPrefs.saveAppRules(this, updated)
        refreshConfig()
    }

    private fun showTemplateManager() {
        startActivity(Intent(this, PackageTemplateManagerActivity::class.java))
    }

    private fun openTemplateEditor(template: Template) {
        startActivity(PackageCustomListActivity.forTemplate(this, template.id, template.name))
    }

    private fun showCreateTemplateDialog(onCreated: (Template) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
        }
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.template_name)
            addView(input)
        }
        val whitelistId = android.view.View.generateViewId()
        val blacklistId = android.view.View.generateViewId()
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(MaterialRadioButton(this@PackageListConfigActivity).apply {
                id = whitelistId
                text = getString(R.string.template_mode_whitelist)
            })
            addView(MaterialRadioButton(this@PackageListConfigActivity).apply {
                id = blacklistId
                text = getString(R.string.template_mode_blacklist)
            })
            check(whitelistId)
        }
        container.addView(inputLayout)
        container.addView(modeGroup)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifBlank {
                    getString(R.string.template_new)
                }
                val mode = if (modeGroup.checkedRadioButtonId == blacklistId) {
                    TemplateListMode.BLACKLIST
                } else {
                    TemplateListMode.WHITELIST
                }
                val template = PackageVisibilityPrefs.createTemplate(this, name, listMode = mode)
                refreshConfig()
                onCreated(template)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class DefaultSelectionItem(
        val title: String,
        val action: DefaultAction,
        val templateId: String? = null
    )

    private enum class DefaultAction {
        ALL_VISIBLE,
        ALL_HIDDEN,
        TEMPLATE,
        NEW_TEMPLATE,
    }

    private data class AppRuleOption(
        val title: String,
        val action: AppRuleAction,
        val templateId: String? = null
    )

    private enum class AppRuleAction {
        NEW_TEMPLATE,
        ALL_VISIBLE,
        ALL_HIDDEN,
        CUSTOM,
        TEMPLATE
    }

}
