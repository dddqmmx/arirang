package asia.nana7mi.arirang.selfcheck.ui.activity

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaDrm
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import asia.nana7mi.arirang.selfcheck.BuildConfig
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckSectionView
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskForPhoneDiag
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import asia.nana7mi.arirang.selfcheck.checker.*

class SelfCheckActivity : AppCompatActivity() {

    private lateinit var summaryText: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var scrollView: ScrollView
    private lateinit var liveMonitorButton: MaterialButton
    private lateinit var checkItems: List<CheckItem>

    private data class CheckItem(
        val titleRes: Int,
        val section: CheckSectionView,
        val navChipId: Int,
        val check: suspend () -> CheckResult
    )

    private var isMonitoring = false
    private var monitorJob: Job? = null
    private val latestSensorData = mutableMapOf<Int, FloatArray>()
    private val sensorListeners = mutableListOf<SensorEventListener>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        runSelfCheck()
    }

    private var lastResults: List<Pair<Int, CheckResult>>? = null

    private val createFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            val selectedIndices = pendingExportIndices ?: return@let
            performExport(it, selectedIndices)
            pendingExportIndices = null
        }
    }

    private var pendingExportIndices: List<Int>? = null

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_self_check, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                exportCurrentState()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_self_check)
        setSupportActionBar(findViewById(R.id.toolbar))

        summaryText = findViewById(R.id.selfCheckSummary)
        runButton = findViewById(R.id.runSelfCheckButton)
        scrollView = findViewById(R.id.selfCheckScrollView)

        liveMonitorButton = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener {
                if (isMonitoring) stopMonitoring() else startMonitoring()
            }
        }
        val sensorCardContent = (findViewById<View>(R.id.sensorPrecisionSection) as ViewGroup).getChildAt(0) as ViewGroup
        sensorCardContent.addView(liveMonitorButton)

        checkItems = listOf(
            CheckItem(R.string.self_check_unique_title, CheckSectionView(findViewById(R.id.uniqueSection)), R.id.navUniqueChip) { UniqueIdentifiersChecker().check(this) },
            CheckItem(R.string.self_check_build_title, CheckSectionView(findViewById(R.id.buildSection)), R.id.navBuildChip) { BuildInfoChecker().check(this) },
            CheckItem(R.string.self_check_props_title, CheckSectionView(findViewById(R.id.propsSection)), R.id.navPropsChip) { SystemPropertiesChecker().check(this) },
            CheckItem(R.string.self_check_rawfiles_title, CheckSectionView(findViewById(R.id.rawFilesSection)), R.id.navRawFilesChip) { RawKernelFilesChecker().check(this) },
            CheckItem(R.string.self_check_telephony_title, CheckSectionView(findViewById(R.id.telephonySection)), R.id.navTelephonyChip) { TelephonyChecker().check(this) },
            CheckItem(R.string.self_check_sim_title, CheckSectionView(findViewById(R.id.simSection)), R.id.navSimChip) { SimChecker().check(this) },
            CheckItem(R.string.self_check_drm_title, CheckSectionView(findViewById(R.id.drmSection)), R.id.navDrmChip) { DrmChecker().check(this) },
            CheckItem(R.string.self_check_settings_title, CheckSectionView(findViewById(R.id.settingsSection)), R.id.navSettingsChip) { SettingsChecker().check(this) },
            CheckItem(R.string.self_check_sensors_title, CheckSectionView(findViewById(R.id.sensorsSection)), R.id.navSensorsChip) { SensorsChecker().check(this) },
            CheckItem(R.string.self_check_sensor_precision_title, CheckSectionView(findViewById(R.id.sensorPrecisionSection)), R.id.navSensorPrecisionChip) { SensorPrecisionChecker().check(this) },
            CheckItem(R.string.self_check_apps_title, CheckSectionView(findViewById(R.id.appsSection)), R.id.navAppsChip) { InstalledAppsChecker().check(this) },
            CheckItem(R.string.self_check_location_title, CheckSectionView(findViewById(R.id.locationSection)), R.id.navLocationChip) { LocationChecker().check(this) },
            CheckItem(R.string.self_check_wifi_title, CheckSectionView(findViewById(R.id.wifiSection)), R.id.navWifiChip) { WifiChecker().check(this) },
            CheckItem(R.string.self_check_accounts_title, CheckSectionView(findViewById(R.id.accountsSection)), R.id.navAccountsChip) { AccountsChecker().check(this) },
            CheckItem(R.string.self_check_network_title, CheckSectionView(findViewById(R.id.networkSection)), R.id.navNetworkChip) { NetworkChecker().check(this) },
            CheckItem(R.string.self_check_bluetooth_title, CheckSectionView(findViewById(R.id.bluetoothSection)), R.id.navBluetoothChip) { BluetoothChecker().check(this) }
        )

        checkItems.forEach { item ->
            item.section.bindTitle(getString(item.titleRes))
            findViewById<View>(item.navChipId).setOnClickListener {
                scrollToSection(item.section.root)
            }
        }

        runButton.setOnClickListener { requestMissingPermissionsOrRun() }
        runSelfCheck()
    }

    private fun scrollToSection(section: View) {
        scrollView.post {
            val topOffset = resources.getDimensionPixelSize(R.dimen.self_check_scroll_target_offset)
            scrollView.smoothScrollTo(0, (section.top - topOffset).coerceAtLeast(0))
        }
    }

    private fun requestMissingPermissionsOrRun() {
        val missing = CheckDefinitions.REQUIRED_PERMISSIONS.filter { !hasPermission(it) }

        if (missing.isEmpty()) {
            runSelfCheck()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun runSelfCheck() {
        stopMonitoring()
        setLoadingState()
        lifecycleScope.launch {
            val jobs = checkItems.map { item ->
                item to async(Dispatchers.IO) { item.check() }
            }

            val results = jobs.map { (item, deferred) ->
                item.titleRes to deferred.await()
            }
            lastResults = results

            checkItems.forEachIndexed { index, item ->
                item.section.bindResult(results[index].second)
            }

            val visibleCount = results.count { it.second.state == CheckState.VISIBLE }
            val blockedCount = results.count { it.second.state == CheckState.BLOCKED }
            val leakedCount = results.count { it.second.state == CheckState.LEAKED }
            summaryText.text = if (leakedCount > 0) {
                getString(R.string.self_check_summary_result_leak, visibleCount, blockedCount, leakedCount)
            } else {
                getString(R.string.self_check_summary_result, visibleCount, blockedCount)
            }
            runButton.isEnabled = true
            runButton.setText(R.string.self_check_run_again)
        }
    }

    override fun onPause() {
        super.onPause()
        stopMonitoring()
    }

    private fun exportCurrentState() {
        val results = lastResults ?: return
        val titles = results.map { getString(it.first) }.toTypedArray()
        val checkedItems = BooleanArray(titles.size) { true }

        AlertDialog.Builder(this)
            .setTitle(R.string.export_select_sections)
            .setMultiChoiceItems(titles, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedIndices = checkedItems.indices.filter { checkedItems[it] }
                if (selectedIndices.isNotEmpty()) {
                    pendingExportIndices = selectedIndices
                    createFileLauncher.launch(getString(R.string.export_filename))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performExport(uri: Uri, selectedIndices: List<Int>) {
        val results = lastResults ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                        selectedIndices.forEach { index ->
                            val (titleRes, result) = results[index]
                            writer.write("=== ${getString(titleRes)} ===")
                            writer.newLine()
                            writer.write("${getString(R.string.status_title)}: ${result.status}")
                            writer.newLine()
                            writer.write(result.content)
                            writer.newLine()
                            writer.newLine()
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    Toast.makeText(this@SelfCheckActivity, R.string.export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(CheckDefinitions.PHONE_DIAG_TAG, "Failed to export", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(this@SelfCheckActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoadingState() {
        stopMonitoring()
        runButton.isEnabled = false
        summaryText.setText(R.string.self_check_summary_running)
        val loading = CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_checking), getString(R.string.self_check_waiting))
        checkItems.forEach { it.section.bindResult(loading) }
    }

    private fun analyzeDecimalPrecision(data: FloatArray): Int {
        var maxDecimalPlaces = 0
        for (value in data) {
            val str = value.toString()
            val dotIndex = str.indexOf('.')
            if (dotIndex >= 0) {
                val decimalPart = str.substring(dotIndex + 1)
                val nonZeroLength = decimalPart.trimEnd('0').length
                maxDecimalPlaces = maxOf(maxDecimalPlaces, nonZeroLength)
            }
        }
        return maxDecimalPlaces
    }

    private fun readSensorPrecisionConfig(): Int {
        val fromPrefs = try {
            val prefs = getSharedPreferences("sensor_config_prefs", Context.MODE_PRIVATE)
            val precisionJson = prefs.getString("precision_by_sensor_type", null)
            if (precisionJson == null) 0 else {
                val json = org.json.JSONObject(precisionJson)
                json.keys().asSequence().maxOfOrNull { json.getInt(it as String) } ?: 0
            }
        } catch (_: Exception) { 0 }
        if (fromPrefs > 0) return fromPrefs

        return try {
            val targetPkg = BuildConfig.TARGET_PACKAGE_NAME
            val configDir = BuildConfig.SUBMODULE_CONFIG_DIR
            val configFile = BuildConfig.SUBMODULE_CONFIG_FILE
            val file = File("/data/user_de/0/$targetPkg/files/$configDir/$configFile")
            if (!file.canRead()) return 0
            val json = org.json.JSONObject(file.readText())
            val rules = json.optJSONArray("sensorPrecisionRules") ?: return 0
            var maxLevel = 0
            for (i in 0 until rules.length()) {
                val level = rules.getJSONObject(i).optInt("level", 0)
                if (level > maxLevel) maxLevel = level
            }
            maxLevel
        } catch (_: Exception) { 0 }
    }

    private fun startMonitoring() {
        isMonitoring = true
        latestSensorData.clear()
        sensorListeners.clear()
        val mgr = getSystemService(SensorManager::class.java) ?: run {
            isMonitoring = false
            return
        }

        for ((type, _) in CheckDefinitions.SENSOR_TYPES) {
            val sensor = mgr.getDefaultSensor(type) ?: continue
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    latestSensorData[type] = floatArrayOf(event.values[0], event.values[1], event.values[2])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            sensorListeners.add(listener)
        }

        liveMonitorButton.setText(R.string.self_check_sensor_precision_monitor_stop)
        getSection(R.string.self_check_sensor_precision_title).bindResult(
            CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_sensor_precision_monitoring),
                ""
            )
        )

        monitorJob = lifecycleScope.launch {
            while (isMonitoring) {
                updateMonitorDisplay()
                delay(500)
            }
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        val mgr = getSystemService(SensorManager::class.java)
        sensorListeners.forEach { mgr?.unregisterListener(it) }
        sensorListeners.clear()
        latestSensorData.clear()
        if (::liveMonitorButton.isInitialized) {
            liveMonitorButton.setText(R.string.self_check_sensor_precision_monitor_start)
        }
    }

    private fun updateMonitorDisplay() {
        val configLevel = readSensorPrecisionConfig()
        val content = buildString {
            var worst = 0
            for ((type, name) in CheckDefinitions.SENSOR_TYPES) {
                val data = latestSensorData[type] ?: continue
                val dp = analyzeDecimalPrecision(data)
                if (dp > worst) worst = dp
                append("$name: ${"%.4f".format(data[0])}, ${"%.4f".format(data[1])}, ${"%.4f".format(data[2])}")
                appendLine(" → ${dp}dp")
            }
            if (latestSensorData.isNotEmpty()) {
                append("Worst: ${worst}dp")
                if (configLevel > 0) {
                    append(" | Level: $configLevel")
                    if (worst <= configLevel) append(" ✅") else append(" ⚠ LEAK")
                }
            }
        }
        getSection(R.string.self_check_sensor_precision_title).bindResult(
            CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_sensor_precision_monitoring),
                content.ifEmpty { getString(R.string.self_check_sensor_precision_no_sensor) }
            )
        )
    }

    private fun getSection(titleResId: Int): CheckSectionView {
        return checkItems.first { it.titleRes == titleResId }.section
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
