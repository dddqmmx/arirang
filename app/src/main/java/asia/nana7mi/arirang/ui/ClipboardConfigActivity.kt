package asia.nana7mi.arirang.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.AppInfo
import asia.nana7mi.arirang.ui.adapter.AppListAdapter
import asia.nana7mi.arirang.data.datastore.ClipboardPromptPrefs
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatSpinner
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardConfigActivity : BaseActivity() {

    private lateinit var featureStatusIcon: ImageView
    private lateinit var filterSpinner: AppCompatSpinner
    private lateinit var filterAppTypeSpinner: AppCompatSpinner
    private lateinit var searchEditText: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter

    private val allApps = mutableListOf<AppInfo>()
    
    private var isFeatureEnabled = true 
    private var defaultPolicy = ClipboardPromptPrefs.Policy.ASK

    private var appFilter = ClipboardPromptPrefs.AppFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_clipboard_setting)

        setSupportActionBar(findViewById(R.id.toolbar))

        initViews()
        setupListeners()
        lifecycleScope.launch {
            loadConfig()
            loadInstalledApps()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_clipboard_config, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_reset) {
            showResetConfirmDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showResetConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_all)
            .setMessage(R.string.reset_all_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ClipboardPromptPrefs.resetAll(this@ClipboardConfigActivity)
                    }
                    loadConfig()
                    loadInstalledApps()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun initViews() {
        featureStatusIcon = findViewById(R.id.featureStatusIcon)
        searchEditText = findViewById(R.id.searchEditText)
        filterSpinner = findViewById(R.id.filterSpinner)
        filterAppTypeSpinner = findViewById(R.id.filterAppTypeSpinner)

        recyclerView = findViewById(R.id.appListRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = AppListAdapter(mutableListOf()) { app, newState ->
            updateAppPermission(app.packageName, newState)
        }
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

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (parent?.id == R.id.filterSpinner) {
                    val newPolicy = when(position) {
                        0 -> ClipboardPromptPrefs.Policy.ALLOW
                        1 -> ClipboardPromptPrefs.Policy.DENY
                        else -> ClipboardPromptPrefs.Policy.ASK
                    }
                    if (newPolicy != defaultPolicy) {
                        defaultPolicy = newPolicy
                        lifecycleScope.launch(Dispatchers.IO) {
                            ClipboardPromptPrefs.setDefaultPolicy(this@ClipboardConfigActivity, defaultPolicy)
                        }
                        lifecycleScope.launch {
                            loadInstalledApps()
                        }
                    }
                } else {
                    applyFilters()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        filterSpinner.onItemSelectedListener = spinnerListener
        filterAppTypeSpinner.onItemSelectedListener = spinnerListener

        featureStatusIcon.setOnClickListener {
            isFeatureEnabled = !isFeatureEnabled
            updateFeatureStatusUI()
            lifecycleScope.launch(Dispatchers.IO) {
                ClipboardPromptPrefs.setFeatureEnabled(this@ClipboardConfigActivity, isFeatureEnabled)
            }
        }
    }

    private fun updateFeatureStatusUI() {
        featureStatusIcon.setImageResource(
            if (isFeatureEnabled) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
        )
    }

    private suspend fun loadInstalledApps() {
        val pm = packageManager
        val context = this
        val items = withContext(Dispatchers.IO) {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appPolicies = ClipboardPromptPrefs.getAppPolicies(context)

            packages.map { pkg ->
                val isSystem = (pkg.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val appPolicy = appPolicies[pkg.packageName]
                val state = appPolicy ?: defaultPolicy
                
                AppInfo(
                    appName = pm.getApplicationLabel(pkg).toString(),
                    packageName = pkg.packageName,
                    icon = null,
                    permissionState = state,
                    isSystemApp = isSystem
                )
            }.sortedBy { it.appName.lowercase() }
        }

        withContext(Dispatchers.Main) {
            allApps.clear()
            allApps.addAll(items)
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = searchEditText.text.toString().lowercase()
        val appTypeFilter = filterAppTypeSpinner.selectedItemPosition

        val newAppFilter = when (appTypeFilter) {
            0 -> ClipboardPromptPrefs.AppFilter.ALL
            1 -> ClipboardPromptPrefs.AppFilter.USER
            else -> ClipboardPromptPrefs.AppFilter.SYSTEM
        }
        if (newAppFilter != this.appFilter){
            this.appFilter = newAppFilter
            updateAppFilter(newAppFilter)
        }

        val filtered = allApps.filter { app ->
            val matchesQuery = app.appName.lowercase().contains(query) || 
                               app.packageName.lowercase().contains(query)
            val matchesType = when (appTypeFilter) {
                1 -> !app.isSystemApp
                2 -> app.isSystemApp
                else -> true
            }
            matchesQuery && matchesType
        }

        adapter.setList(filtered)
    }

    private fun updateAppFilter(newAppFilter: ClipboardPromptPrefs.AppFilter) {
        val context = this
        lifecycleScope.launch(Dispatchers.IO) {
            ClipboardPromptPrefs.setAppFilter(context, newAppFilter)
        }
    }

    private suspend fun loadConfig() {
        val context = this
        val config = withContext(Dispatchers.IO) {
            val dp = ClipboardPromptPrefs.getDefaultPolicy(context)
            val af = ClipboardPromptPrefs.getAppFilter(context)
            val fe = ClipboardPromptPrefs.isFeatureEnabled(context)
            Triple(dp, af, fe)
        }
        
        defaultPolicy = config.first
        appFilter = config.second
        isFeatureEnabled = config.third

        withContext(Dispatchers.Main) {
            val spinnerPos = when(defaultPolicy) {
                ClipboardPromptPrefs.Policy.ALLOW -> 0
                ClipboardPromptPrefs.Policy.DENY -> 1
                ClipboardPromptPrefs.Policy.ASK -> 2
            }
            filterSpinner.setSelection(spinnerPos)
            val appTypeSpinnerPos = when(appFilter) {
                ClipboardPromptPrefs.AppFilter.ALL -> 0
                ClipboardPromptPrefs.AppFilter.USER -> 1
                ClipboardPromptPrefs.AppFilter.SYSTEM -> 2
            }
            filterAppTypeSpinner.setSelection(appTypeSpinnerPos)
            updateFeatureStatusUI()
        }
    }

    private fun updateAppPermission(packageName: String, newState: ClipboardPromptPrefs.Policy) {
        val context = this
        lifecycleScope.launch(Dispatchers.IO) {
            ClipboardPromptPrefs.setAppPolicy(context, packageName,newState)
        }
    }
}