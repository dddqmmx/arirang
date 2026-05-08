package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SimScopeActivity : AppCompatActivity() {
//    private val PREFS_NAME = "sim_config_prefs";
//    private val MODE_KEY = "mode";
//    private val WHITELIST_KEY = "whitelist";
//    private val BLACKLIST_KEY = "blacklist";
//    private val LAST_MODIFIED_KEY = "last_modified";
//
//    private lateinit var filterSpinner: AppCompatSpinner
//    private lateinit var searchEditText: EditText
//    private lateinit var recyclerView: RecyclerView
//    private lateinit var adapter: AppListAdapter
//    private val allApps = mutableListOf<AppInfo>() // 完整数据
//    private val filteredApps = mutableListOf<AppInfo>() // 当前显示的数据（adapter 绑定的）
//
//    private lateinit var prefs: SharedPreferences
//    private var enabled = false
//    private var mode = 0
//    private var whitelist = mutableSetOf<String>()
//    private var blacklist = mutableSetOf<String>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        setContentView(R.layout.activity_sim_scope)
//
//        prefs = getSharedPreferences(PREFS_NAME, MODE_WORLD_READABLE)
//
//        searchEditText = findViewById<EditText>(R.id.searchEditText)
//        filterSpinner = findViewById<AppCompatSpinner>(R.id.filterSpinner)
//
//        recyclerView = findViewById(R.id.appListRecyclerView)
//        recyclerView.layoutManager = LinearLayoutManager(this)
//
//        adapter = AppListAdapter(
//            filteredApps,
//            onPermissionChange = { appInfo, hasPermission ->
//                onAppPermissionChanged(
//                    appInfo,
//                    hasPermission
//                )
//            },
//            getMode = { mode },
//            getWhitelist = { whitelist },
//            getBlacklist = { blacklist }
//        )
//
//        recyclerView.adapter = adapter
//
//        loadClipboardConfig()
//        setupListeners()
//        loadInstalledApps()
    }

//    private fun setupListeners() {
//        searchEditText.addTextChangedListener(object : TextWatcher {
//            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
//            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
//                filterApps(s.toString())
//            }
//            override fun afterTextChanged(s: Editable?) {}
//        })
//        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
//            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
//                mode = position
//                saveClipboardConfig()
//                adapter.refreshModeChange()
//                lifecycleScope.launch(Dispatchers.IO) {
//
//                    val items = allApps.map { pkg ->
//                        AppInfo(
//                            appName = pkg.appName,
//                            packageName = pkg.packageName,
//                            icon = null,
//                            hasPermission = when (mode) {
//                                0 -> whitelist.contains(pkg.packageName)
//                                else -> blacklist.contains(pkg.packageName)
//                            }
//                        )
//                    }.sortedByDescending  { it.hasPermission }
//
//                    withContext(Dispatchers.Main) {
//                        adapter.setList(items)
//                        allApps.clear()
//                        allApps.addAll(items)
//                        filteredApps.clear()
//                        filteredApps.addAll(items)
//                        recyclerView.scrollToPosition(0)
//                    }
//                }
//            }
//            override fun onNothingSelected(parent: AdapterView<*>?) {}
//        }
//    }
//
//    private fun onAppPermissionChanged(app: AppInfo, granted: Boolean) {
//        val key = app.packageName
//        if (mode == 0) {
//            if (granted) whitelist.add(key) else whitelist.remove(key)
//        } else {
//            if (granted) blacklist.add(key) else blacklist.remove(key)
//        }
//        saveClipboardConfig()
//    }
//
//    private fun loadInstalledApps() {
//        val pm = packageManager
//        lifecycleScope.launch(Dispatchers.IO) {
//
//            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
//            val items = packages.map { pkg ->
//                AppInfo(
//                    appName = pm.getApplicationLabel(pkg).toString(),
//                    packageName = pkg.packageName,
//                    icon = null,
//                    hasPermission = when (mode) {
//                        0 -> whitelist.contains(pkg.packageName)
//                        else -> blacklist.contains(pkg.packageName)
//                    }
//                )
//            }.sortedByDescending  { it.hasPermission }
//
//            withContext(Dispatchers.Main) {
//                adapter.setList(items)
//                allApps.clear()
//                allApps.addAll(items)
//                filteredApps.clear()
//                filteredApps.addAll(items)
//                recyclerView.scrollToPosition(0)
//            }
//        }
//    }
//
//    private fun filterApps(query: String) {
//        val newList = if (query.isBlank()) {
//            allApps
//        } else {
//            allApps.filter {
//                it.appName.contains(query, ignoreCase = true) ||
//                        it.packageName.contains(query, ignoreCase = true)
//            }
//        }
//        adapter.setList(newList)
//        recyclerView.scrollToPosition(0)
//    }
//
//    private fun loadClipboardConfig() {
//        mode = prefs.getInt(MODE_KEY, 0)
//        whitelist = prefs.getStringSet(WHITELIST_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
//        blacklist = prefs.getStringSet(BLACKLIST_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
//
//        filterSpinner.setSelection(mode)
//    }
//
//    private fun saveClipboardConfig() {
//        prefs.edit() {
//            apply {
//                putInt(MODE_KEY, mode)
//                putStringSet(WHITELIST_KEY, whitelist)
//                putStringSet(BLACKLIST_KEY, blacklist)
//                putLong(LAST_MODIFIED_KEY, Date().time)
//            }
//        }
//    }
}
