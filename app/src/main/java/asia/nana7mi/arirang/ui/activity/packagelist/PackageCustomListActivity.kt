package asia.nana7mi.arirang.ui.activity.packagelist

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.AppRule
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.DisplayMode
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.TemplateListMode
import asia.nana7mi.arirang.model.PackageCustomListItem
import asia.nana7mi.arirang.ui.activity.BaseActivity
import asia.nana7mi.arirang.ui.adapter.PackageCustomListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackageCustomListActivity : BaseActivity() {

    private lateinit var searchEditText: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PackageCustomListAdapter

    private val allPackages = mutableListOf<PackageItem>()
    private val visiblePackages = mutableListOf<PackageItem>()
    private val selectedPackages = mutableSetOf<String>()

    private val targetType: TargetType by lazy {
        TargetType.valueOf(intent.getStringExtra(EXTRA_TARGET_TYPE) ?: TargetType.TEMPLATE.name)
    }
    private val templateId: String? by lazy { intent.getStringExtra(EXTRA_TEMPLATE_ID) }
    private val targetPackageName: String? by lazy { intent.getStringExtra(EXTRA_PACKAGE_NAME) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_package_custom_list)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val baseTitle = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.template_edit_list)
        val modeSuffix = templateModeTitleSuffix()
        supportActionBar?.title = if (modeSuffix == null) baseTitle else "$baseTitle - $modeSuffix"

        searchEditText = findViewById(R.id.searchEditText)
        recyclerView = findViewById(R.id.packageRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PackageCustomListAdapter(mutableListOf()) { item ->
            if (!selectedPackages.add(item.packageName)) {
                selectedPackages.remove(item.packageName)
            }
            applyFilter()
        }
        recyclerView.adapter = adapter

        setupListeners()
        lifecycleScope.launch { loadData() }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_package_custom_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                saveSelection()
                true
            }
            R.id.action_select_all -> {
                setVisibleSelection(true)
                true
            }
            R.id.action_clear_all -> {
                setVisibleSelection(false)
                true
            }
            R.id.action_invert_selection -> {
                invertVisibleSelection()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupListeners() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private suspend fun loadData() {
        val initialSelection = withContext(Dispatchers.IO) {
            loadInitialSelection()
        }
        val packages = withContext(Dispatchers.IO) {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .map {
                    PackageItem(
                        packageManager.getApplicationLabel(it).toString(),
                        it.packageName,
                        runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull()
                    )
                }
                .sortedBy { it.appName.lowercase() }
        }
        allPackages.clear()
        allPackages.addAll(packages)
        selectedPackages.clear()
        selectedPackages.addAll(initialSelection ?: packages.map { it.packageName })
        applyFilter()
    }

    private fun loadInitialSelection(): Set<String>? {
        val config = PackageVisibilityPrefs.loadConfig(this)
        return when (targetType) {
            TargetType.TEMPLATE -> {
                config.templates.firstOrNull { it.id == templateId }?.visiblePackages ?: emptySet()
            }
            TargetType.APP -> {
                val rule = config.appRules.firstOrNull { it.packageName == targetPackageName }
                rule?.visiblePackages
                    ?: PackageVisibilityPrefs.resolveRuleVisiblePackages(rule, config)
            }
        }
    }

    private fun applyFilter() {
        val query = searchEditText.text.toString().lowercase()
        val filtered = if (query.isBlank()) {
            allPackages
        } else {
            allPackages.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }

        visiblePackages.clear()
        visiblePackages.addAll(filtered)
        adapter.setList(filtered.map {
            PackageCustomListItem(
                appName = it.appName,
                packageName = it.packageName,
                icon = it.icon,
                isSelected = selectedPackages.contains(it.packageName)
            )
        })
    }

    private fun saveSelection() {
        when (targetType) {
            TargetType.TEMPLATE -> saveTemplateSelection()
            TargetType.APP -> saveAppSelection()
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun saveTemplateSelection() {
        val id = templateId ?: return
        val templates = PackageVisibilityPrefs.loadTemplates(this).map {
            if (it.id == id) it.copy(visiblePackages = selectedPackages.toSet()) else it
        }
        PackageVisibilityPrefs.saveTemplates(this, templates)
    }

    private fun saveAppSelection() {
        val pkg = targetPackageName ?: return
        val rules = PackageVisibilityPrefs.loadAppRules(this)
            .filterNot { it.packageName == pkg } +
            AppRule(pkg, DisplayMode.CUSTOM, visiblePackages = selectedPackages.toSet())
        PackageVisibilityPrefs.saveAppRules(this, rules)
    }

    private fun setVisibleSelection(selected: Boolean) {
        visiblePackages.forEach { item ->
            if (selected) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }
        }
        applyFilter()
    }

    private fun invertVisibleSelection() {
        visiblePackages.forEach { item ->
            if (!selectedPackages.add(item.packageName)) {
                selectedPackages.remove(item.packageName)
            }
        }
        applyFilter()
    }

    private fun templateModeTitleSuffix(): String? {
        if (targetType != TargetType.TEMPLATE) return null
        val template = PackageVisibilityPrefs.loadTemplates(this).firstOrNull { it.id == templateId } ?: return null
        return getString(
            if (template.listMode == TemplateListMode.WHITELIST) {
                R.string.template_mode_whitelist
            } else {
                R.string.template_mode_blacklist
            }
        )
    }

    private data class PackageItem(
        val appName: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable? = null
    )

    enum class TargetType {
        TEMPLATE,
        APP
    }

    companion object {
        private const val EXTRA_TARGET_TYPE = "target_type"
        private const val EXTRA_TEMPLATE_ID = "template_id"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_TITLE = "title"

        fun forTemplate(context: Context, templateId: String, title: String): Intent {
            return Intent(context, PackageCustomListActivity::class.java)
                .putExtra(EXTRA_TARGET_TYPE, TargetType.TEMPLATE.name)
                .putExtra(EXTRA_TEMPLATE_ID, templateId)
                .putExtra(EXTRA_TITLE, title)
        }

        fun forApp(context: Context, packageName: String, title: String): Intent {
            return Intent(context, PackageCustomListActivity::class.java)
                .putExtra(EXTRA_TARGET_TYPE, TargetType.APP.name)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_TITLE, title)
        }
    }
}
