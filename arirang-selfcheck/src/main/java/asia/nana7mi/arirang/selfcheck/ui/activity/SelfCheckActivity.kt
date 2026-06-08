package asia.nana7mi.arirang.selfcheck.ui.activity

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import asia.nana7mi.arirang.selfcheck.R
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

class SelfCheckActivity : AppCompatActivity() {

    private companion object {
        private const val PHONE_DIAG_TAG = "Arirang/SelfCheckPhoneDiag"
    }

    private lateinit var summaryText: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var scrollView: ScrollView
    private lateinit var uniqueSection: CheckSectionView
    private lateinit var buildSection: CheckSectionView
    private lateinit var propsSection: CheckSectionView
    private lateinit var rawFilesSection: CheckSectionView
    private lateinit var telephonySection: CheckSectionView
    private lateinit var simSection: CheckSectionView
    private lateinit var drmSection: CheckSectionView
    private lateinit var settingsSection: CheckSectionView
    private lateinit var sensorsSection: CheckSectionView
    private lateinit var appsSection: CheckSectionView
    private lateinit var locationSection: CheckSectionView
    private lateinit var wifiSection: CheckSectionView
    private lateinit var accountsSection: CheckSectionView
    private lateinit var networkSection: CheckSectionView
    private lateinit var bluetoothSection: CheckSectionView

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
        uniqueSection = CheckSectionView(findViewById(R.id.uniqueSection))
        buildSection = CheckSectionView(findViewById(R.id.buildSection))
        propsSection = CheckSectionView(findViewById(R.id.propsSection))
        rawFilesSection = CheckSectionView(findViewById(R.id.rawFilesSection))
        telephonySection = CheckSectionView(findViewById(R.id.telephonySection))
        simSection = CheckSectionView(findViewById(R.id.simSection))
        drmSection = CheckSectionView(findViewById(R.id.drmSection))
        settingsSection = CheckSectionView(findViewById(R.id.settingsSection))
        sensorsSection = CheckSectionView(findViewById(R.id.sensorsSection))
        appsSection = CheckSectionView(findViewById(R.id.appsSection))
        locationSection = CheckSectionView(findViewById(R.id.locationSection))
        wifiSection = CheckSectionView(findViewById(R.id.wifiSection))
        accountsSection = CheckSectionView(findViewById(R.id.accountsSection))
        networkSection = CheckSectionView(findViewById(R.id.networkSection))
        bluetoothSection = CheckSectionView(findViewById(R.id.bluetoothSection))

        uniqueSection.bindTitle(getString(R.string.self_check_unique_title))
        buildSection.bindTitle(getString(R.string.self_check_build_title))
        propsSection.bindTitle(getString(R.string.self_check_props_title))
        rawFilesSection.bindTitle(getString(R.string.self_check_rawfiles_title))
        telephonySection.bindTitle(getString(R.string.self_check_telephony_title))
        simSection.bindTitle(getString(R.string.self_check_sim_title))
        drmSection.bindTitle(getString(R.string.self_check_drm_title))
        settingsSection.bindTitle(getString(R.string.self_check_settings_title))
        sensorsSection.bindTitle(getString(R.string.self_check_sensors_title))
        appsSection.bindTitle(getString(R.string.self_check_apps_title))
        locationSection.bindTitle(getString(R.string.self_check_location_title))
        wifiSection.bindTitle(getString(R.string.self_check_wifi_title))
        accountsSection.bindTitle(getString(R.string.self_check_accounts_title))
        networkSection.bindTitle(getString(R.string.self_check_network_title))
        bluetoothSection.bindTitle(getString(R.string.self_check_bluetooth_title))

        setupSectionNavigation()
        runButton.setOnClickListener { requestMissingPermissionsOrRun() }
        runSelfCheck()
    }

    private fun setupSectionNavigation() {
        findViewById<View>(R.id.navUniqueChip).setOnClickListener {
            scrollToSection(uniqueSection.root)
        }
        findViewById<View>(R.id.navBuildChip).setOnClickListener {
            scrollToSection(buildSection.root)
        }
        findViewById<View>(R.id.navPropsChip).setOnClickListener {
            scrollToSection(propsSection.root)
        }
        findViewById<View>(R.id.navRawFilesChip).setOnClickListener {
            scrollToSection(rawFilesSection.root)
        }
        findViewById<View>(R.id.navTelephonyChip).setOnClickListener {
            scrollToSection(telephonySection.root)
        }
        findViewById<View>(R.id.navSimChip).setOnClickListener {
            scrollToSection(simSection.root)
        }
        findViewById<View>(R.id.navDrmChip).setOnClickListener {
            scrollToSection(drmSection.root)
        }
        findViewById<View>(R.id.navSettingsChip).setOnClickListener {
            scrollToSection(settingsSection.root)
        }
        findViewById<View>(R.id.navSensorsChip).setOnClickListener {
            scrollToSection(sensorsSection.root)
        }
        findViewById<View>(R.id.navAppsChip).setOnClickListener {
            scrollToSection(appsSection.root)
        }
        findViewById<View>(R.id.navLocationChip).setOnClickListener {
            scrollToSection(locationSection.root)
        }
        findViewById<View>(R.id.navWifiChip).setOnClickListener {
            scrollToSection(wifiSection.root)
        }
        findViewById<View>(R.id.navAccountsChip).setOnClickListener {
            scrollToSection(accountsSection.root)
        }
        findViewById<View>(R.id.navNetworkChip).setOnClickListener {
            scrollToSection(networkSection.root)
        }
        findViewById<View>(R.id.navBluetoothChip).setOnClickListener {
            scrollToSection(bluetoothSection.root)
        }
    }

    private fun scrollToSection(section: View) {
        scrollView.post {
            val topOffset = resources.getDimensionPixelSize(R.dimen.self_check_scroll_target_offset)
            scrollView.smoothScrollTo(0, (section.top - topOffset).coerceAtLeast(0))
        }
    }

    private fun requestMissingPermissionsOrRun() {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            missing.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
            missing.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (!hasPermission(Manifest.permission.GET_ACCOUNTS)) {
            missing.add(Manifest.permission.GET_ACCOUNTS)
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (!hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (missing.isEmpty()) {
            runSelfCheck()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun runSelfCheck() {
        setLoadingState()
        lifecycleScope.launch {
            val unique = async(Dispatchers.IO) { readUniqueIdentifiers() }
            val build = async(Dispatchers.IO) { readBuildInfo() }
            val props = async(Dispatchers.IO) { readSystemProperties() }
            val rawFiles = async(Dispatchers.IO) { readRawKernelFiles() }
            val telephony = async(Dispatchers.IO) { readTelephonyInfo() }
            val sim = async(Dispatchers.IO) { readSimInfo() }
            val drm = async(Dispatchers.IO) { readDrmInfo() }
            val settings = async(Dispatchers.IO) { readSettingsTables() }
            val sensors = async(Dispatchers.IO) { readSensors() }
            val apps = async(Dispatchers.IO) { readInstalledApps() }
            val location = async(Dispatchers.IO) { readLocationInfo() }
            val wifi = async(Dispatchers.IO) { readWifiInfo() }
            val accounts = async(Dispatchers.IO) { readAccounts() }
            val network = async(Dispatchers.IO) { readNetworkInterfaces() }
            val bluetooth = async(Dispatchers.IO) { readBluetoothInfo() }

            val results = listOf(
                R.string.self_check_unique_title to unique.await(),
                R.string.self_check_build_title to build.await(),
                R.string.self_check_props_title to props.await(),
                R.string.self_check_rawfiles_title to rawFiles.await(),
                R.string.self_check_telephony_title to telephony.await(),
                R.string.self_check_sim_title to sim.await(),
                R.string.self_check_drm_title to drm.await(),
                R.string.self_check_settings_title to settings.await(),
                R.string.self_check_sensors_title to sensors.await(),
                R.string.self_check_apps_title to apps.await(),
                R.string.self_check_location_title to location.await(),
                R.string.self_check_wifi_title to wifi.await(),
                R.string.self_check_accounts_title to accounts.await(),
                R.string.self_check_network_title to network.await(),
                R.string.self_check_bluetooth_title to bluetooth.await()
            )
            lastResults = results

            uniqueSection.bindResult(results[0].second)
            buildSection.bindResult(results[1].second)
            propsSection.bindResult(results[2].second)
            rawFilesSection.bindResult(results[3].second)
            telephonySection.bindResult(results[4].second)
            simSection.bindResult(results[5].second)
            drmSection.bindResult(results[6].second)
            settingsSection.bindResult(results[7].second)
            sensorsSection.bindResult(results[8].second)
            appsSection.bindResult(results[9].second)
            locationSection.bindResult(results[10].second)
            wifiSection.bindResult(results[11].second)
            accountsSection.bindResult(results[12].second)
            networkSection.bindResult(results[13].second)
            bluetoothSection.bindResult(results[14].second)

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
                Log.e(PHONE_DIAG_TAG, "Failed to export", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(this@SelfCheckActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoadingState() {
        runButton.isEnabled = false
        summaryText.setText(R.string.self_check_summary_running)
        val loading = CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_checking), getString(R.string.self_check_waiting))
        uniqueSection.bindResult(loading)
        buildSection.bindResult(loading)
        propsSection.bindResult(loading)
        rawFilesSection.bindResult(loading)
        telephonySection.bindResult(loading)
        simSection.bindResult(loading)
        drmSection.bindResult(loading)
        settingsSection.bindResult(loading)
        sensorsSection.bindResult(loading)
        appsSection.bindResult(loading)
        locationSection.bindResult(loading)
        wifiSection.bindResult(loading)
        accountsSection.bindResult(loading)
        networkSection.bindResult(loading)
        bluetoothSection.bindResult(loading)
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readUniqueIdentifiers(): CheckResult {
        val values = mutableListOf<String>()
        val notes = mutableListOf<String>()

        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("Android ID: ${it.maskMiddle()}") }

        readAdvertisingId()?.let { values.add("GAID: ${it.maskMiddle()}") }
        readAppSetId()?.let { values.add("App Set ID: ${it.maskMiddle()}") }
        readWidevineId()?.let { values.add("Widevine DRM ID: ${it.maskMiddle()}") }

        runCatching { Build.getSerial() }
            .getOrNull()
            ?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
            ?.let { values.add("Serial: ${it.maskMiddle()}") }

        Build.SERIAL
            .takeUnless { it.isBlank() || it == Build.UNKNOWN }
            ?.let { values.add("Build.SERIAL: ${it.maskMiddle()}") }

        Build.FINGERPRINT
            .takeUnless { it.isBlank() || it == Build.UNKNOWN }
            ?.let { values.add("Build fingerprint: $it") }

        readPhoneUniqueIdentifiers(values, notes)
        readNetworkUniqueIdentifiers(values)

        val content = (values + notes).filter { it.isNotBlank() }
        return CheckResult(
            if (values.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
            if (values.isEmpty()) getString(R.string.self_check_status_not_visible) else getString(R.string.self_check_status_visible),
            if (content.isEmpty()) getString(R.string.self_check_unique_hidden) else content.joinToString("\n")
        )
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readPhoneUniqueIdentifiers(values: MutableList<String>, notes: MutableList<String>) {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            notes.add(getString(R.string.self_check_phone_permission_hint))
            return
        }

        val telephonyManager = getSystemService(TelephonyManager::class.java)
        val subscriptionManager = getSystemService(SubscriptionManager::class.java)

        runCatching { telephonyManager.imei }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("IMEI default: ${it.maskMiddle()}") }

        runCatching { telephonyManager.meid }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("MEID default: ${it.maskMiddle()}") }

        @Suppress("DEPRECATION")
        runCatching { telephonyManager.deviceId }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("Device ID default: ${it.maskMiddle()}") }

        runCatching { telephonyManager.subscriberId }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("IMSI / Subscriber ID: ${it.maskMiddle()}") }

        runCatching { telephonyManager.simSerialNumber }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("SIM serial: ${it.maskMiddle()}") }

        repeat(telephonyManager.phoneCount.coerceAtMost(4)) { slot ->
            runCatching { telephonyManager.getImei(slot) }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("IMEI slot $slot: ${it.maskMiddle()}") }

            runCatching { telephonyManager.getMeid(slot) }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("MEID slot $slot: ${it.maskMiddle()}") }

            runCatching { telephonyManager.getTypeAllocationCode(slot) }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Type allocation code slot $slot: ${it.maskMiddle()}") }
        }

        runCatching { subscriptionManager.activeSubscriptionInfoList.orEmpty() }
            .getOrDefault(emptyList())
            .take(4)
            .forEach { subscription ->
                subscription.iccId
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("ICCID slot ${subscription.simSlotIndex}: ${it.maskMiddle()}") }
            }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readNetworkUniqueIdentifiers(values: MutableList<String>) {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.connectionInfo
            }.getOrNull()?.let { connectionInfo ->
                connectionInfo.macAddress
                    ?.takeUnless { it.isBlank() || it == "02:00:00:00:00:00" }
                    ?.let { values.add("Wi-Fi MAC: $it") }
                connectionInfo.bssid
                    ?.takeUnless { it.isBlank() || it == "02:00:00:00:00:00" }
                    ?.let { values.add("Wi-Fi BSSID: $it") }
            }
        }

        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .mapNotNull { item ->
                    val mac = item.hardwareAddress?.joinToString(":") { "%02x".format(it) }
                    mac?.takeUnless { it.isBlank() || it == "02:00:00:00:00:00" }
                        ?.let { "${item.name} MAC: $it" }
                }
                .take(6)
        }.getOrDefault(emptyList()).forEach(values::add)

        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            runCatching {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothManager.adapter?.address
            }.getOrNull()
                ?.takeUnless { it.isBlank() || it == "02:00:00:00:00:00" }
                ?.let { values.add("Bluetooth MAC: $it") }
        }
    }

    private fun readAdvertisingId(): String? {
        return runCatching {
            AdvertisingIdClient.getAdvertisingIdInfo(this).id
        }.onFailure {
            Log.e(PHONE_DIAG_TAG, "readAdvertisingId failed", it)
        }.getOrNull()?.takeUnless { it.isBlank() }
    }

    private fun readAppSetId(): String? {
        return runCatching {
            val client = AppSet.getClient(applicationContext)
            Tasks.await(client.appSetIdInfo, 1500L, TimeUnit.MILLISECONDS).id
        }.onFailure {
            Log.e(PHONE_DIAG_TAG, "readAppSetId failed", it)
        }.getOrNull()?.takeUnless { it.isBlank() }
    }

    @SuppressLint("HardwareIds")
    private fun readBuildInfo(): CheckResult {
        val fields = listOfNotNull(
            "Brand" to Build.BRAND,
            "Manufacturer" to Build.MANUFACTURER,
            "Model" to Build.MODEL,
            "Device" to Build.DEVICE,
            "Product" to Build.PRODUCT,
            "Board" to Build.BOARD,
            "Hardware" to Build.HARDWARE,
            "Bootloader" to Build.BOOTLOADER,
            "Display" to Build.DISPLAY,
            "Host" to Build.HOST,
            "ID" to Build.ID,
            "Tags" to Build.TAGS,
            "Type" to Build.TYPE,
            "User" to Build.USER,
            "Build time" to Build.TIME.takeIf { it > 0 }?.toString(),
            "Property gsm.sim.operator.iso-country" to readSystemProperty("gsm.sim.operator.iso-country"),
            "Property gsm.sim.operator.numeric" to readSystemProperty("gsm.sim.operator.numeric"),
            "Property gsm.sim.operator.alpha" to readSystemProperty("gsm.sim.operator.alpha"),
            "Property gsm.operator.iso-country" to readSystemProperty("gsm.operator.iso-country"),
            "Property gsm.operator.numeric" to readSystemProperty("gsm.operator.numeric"),
            "Property gsm.operator.alpha" to readSystemProperty("gsm.operator.alpha")
        ).filter { !it.second.isNullOrBlank() && it.second != Build.UNKNOWN }

        return visibleListResult(
            fields.map { "${it.first}: ${it.second}" },
            getString(R.string.self_check_status_visible),
            getString(R.string.self_check_build_hidden)
        )
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readTelephonyInfo(): CheckResult {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_permission_needed),
                getString(R.string.self_check_phone_permission_hint)
            )
        }

        return try {
            val telephonyManager = getSystemService(TelephonyManager::class.java)
            val subscriptionManager = getSystemService(SubscriptionManager::class.java)
            val phoneCount = telephonyManager.phoneCount
            val values = mutableListOf<String>()
            Log.d(
                PHONE_DIAG_TAG,
                "readTelephonyInfo start package=$packageName phoneCount=$phoneCount " +
                    "telephonyManager=${telephonyManager.javaClass.name} subscriptionManager=${subscriptionManager.javaClass.name}"
            )

            val defaultLine1 = runCatching { telephonyManager.line1Number }
            logPhoneProbe("TelephonyManager.getLine1Number/default", defaultLine1)
            defaultLine1.getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Phone number: ${it.maskMiddle()}") }

            val activeSubscriptions = runCatching {
                subscriptionManager.activeSubscriptionInfoList.orEmpty()
            }.onFailure {
                Log.e(PHONE_DIAG_TAG, "SubscriptionManager.activeSubscriptionInfoList failed", it)
            }.getOrDefault(emptyList())
            Log.d(
                PHONE_DIAG_TAG,
                "activeSubscriptions size=${activeSubscriptions.size} " +
                    activeSubscriptions.joinToString(prefix = "[", postfix = "]") {
                        "subId=${it.subscriptionId},slot=${it.simSlotIndex},number=${it.number.maskForPhoneDiag()}"
                    }
            )

            activeSubscriptions.take(4).forEach { subscription ->
                val scopedLine1 = runCatching {
                    telephonyManager.createForSubscriptionId(subscription.subscriptionId).line1Number
                }
                logPhoneProbe(
                    "TelephonyManager.createForSubscriptionId(${subscription.subscriptionId}).getLine1Number",
                    scopedLine1
                )
                scopedLine1.getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("Phone number sub ${subscription.subscriptionId}: ${it.maskMiddle()}") }

                val subscriptionPhoneNumber = runCatching {
                    subscriptionManager.getPhoneNumber(subscription.subscriptionId)
                }
                logPhoneProbe(
                    "SubscriptionManager.getPhoneNumber(${subscription.subscriptionId})",
                    subscriptionPhoneNumber
                )
                subscriptionPhoneNumber.getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("Subscription phone number ${subscription.subscriptionId}: ${it.maskMiddle()}") }
            }

            runCatching { telephonyManager.simCountryIso }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("SIM country ISO: $it") }

            runCatching { telephonyManager.simOperator }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("SIM operator numeric: $it") }

            runCatching { telephonyManager.simOperatorName }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("SIM operator name: $it") }

            runCatching { telephonyManager.simCarrierId }
                .getOrNull()
                ?.let { values.add("SIM carrier ID: $it") }

            runCatching { telephonyManager.simCarrierIdName }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("SIM carrier ID name: $it") }

            runCatching { telephonyManager.carrierIdFromSimMccMnc }
                .getOrNull()
                ?.let { values.add("Carrier ID from SIM MCC/MNC: $it") }

            runCatching { telephonyManager.networkCountryIso }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Network country ISO: $it") }

            runCatching { telephonyManager.networkOperator }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Network operator numeric: $it") }

            runCatching { telephonyManager.networkOperatorName }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Network operator name: $it") }

            runCatching { telephonyManager.serviceState }
                .getOrNull()
                ?.let { values.add("ServiceState: $it") }

            runCatching { telephonyManager.allCellInfo.orEmpty() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "CellInfo: ${it.sanitizeForDisplay()}" }
                ?.let { values.add(it) }

            runCatching { telephonyManager.cellLocation }
                .getOrNull()
                ?.let { values.add("CellLocation: $it") }

            runCatching { telephonyManager.forbiddenPlmns.orEmpty() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Forbidden PLMNs: ")
                ?.let { values.add(it) }

            runCatching { telephonyManager.equivalentHomePlmns.orEmpty() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Equivalent home PLMNs: ")
                ?.let { values.add(it) }

            runCatching { telephonyManager.groupIdLevel1 }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Group ID level 1: $it") }

            runCatching { telephonyManager.emergencyNumberList }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.entries
                ?.joinToString("\n") { entry ->
                    val numbers = entry.value.take(6).joinToString { it.toString() }
                    "Emergency numbers[${entry.key}]: $numbers"
                }
                ?.let { values.add(it) }

            repeat(phoneCount.coerceAtMost(4)) { slot ->
                runCatching { telephonyManager.getNetworkCountryIso(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("Network country ISO slot $slot: $it") }
            }

            visibleListResult(values, getString(R.string.self_check_status_visible), getString(R.string.self_check_telephony_hidden))
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    @SuppressLint("MissingPermission")
    private fun readSimInfo(): CheckResult {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_permission_needed),
                getString(R.string.self_check_phone_permission_hint)
            )
        }

        return try {
            val subscriptionManager = getSystemService(SubscriptionManager::class.java)
            val subscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()
            Log.d(
                PHONE_DIAG_TAG,
                "readSimInfo subscriptions size=${subscriptions.size} " +
                    subscriptions.joinToString(prefix = "[", postfix = "]") {
                        "subId=${it.subscriptionId},slot=${it.simSlotIndex},getNumber=${it.number.maskForPhoneDiag()}"
                    }
            )
            val values = subscriptions.take(6).map { sub ->
                listOfNotNull(
                    "Slot ${sub.simSlotIndex}",
                    sub.displayName?.toString()?.takeIf { it.isNotBlank() }?.let { "Display: $it" },
                    sub.carrierName?.toString()?.takeIf { it.isNotBlank() }?.let { "Carrier: $it" },
                    sub.number?.takeIf { it.isNotBlank() }?.let { "Number: ${it.maskMiddle()}" },
                    "MCC/MNC: ${sub.mccString}/${sub.mncString}",
                    sub.countryIso?.takeIf { it.isNotBlank() }?.let { "Country: $it" },
                    "Card ID: ${sub.cardId}",
                    sub.groupUuid?.toString()?.takeIf { it.isNotBlank() }?.let { "Group: ${it.maskMiddle()}" },
                    "Carrier ID: ${sub.carrierId}"
                ).joinToString("\n")
            }

            CheckResult(
                if (values.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_sim_status, subscriptions.size, subscriptions.size),
                if (values.isEmpty()) getString(R.string.self_check_sim_hidden) else values.joinToString("\n\n")
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun readInstalledApps(): CheckResult {
        return try {
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val samples = apps
                .filterNot { it.packageName == packageName }
                .sortedWith(compareBy<ApplicationInfo> { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.thenBy { it.packageName })
                .take(8)
                .joinToString("\n") { app ->
                    val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                    "$label\n${app.packageName}"
                }

            CheckResult(
                if (apps.size <= 1) CheckState.BLOCKED else CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_apps_status, apps.size, apps.size),
                if (samples.isBlank()) getString(R.string.self_check_apps_hidden) else samples
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    @SuppressLint("MissingPermission")
    private fun readLocationInfo(): CheckResult {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_permission_needed),
                getString(R.string.self_check_location_permission_hint)
            )
        }

        return try {
            val locationManager = getSystemService(LocationManager::class.java)
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val values = mutableListOf<String>()

            val nativeCache = runCatching {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()
            values.add(formatLocationProbe(getString(R.string.self_check_location_native_cache), nativeCache))

            val nativeRealtime = awaitNativeCurrentLocation(locationManager)
            values.add(formatLocationProbe(getString(R.string.self_check_location_native_realtime), nativeRealtime))

            val fusedCache = runCatching {
                Tasks.await(fusedLocationClient.lastLocation, 1500L, TimeUnit.MILLISECONDS)
            }.getOrNull()
            values.add(formatLocationProbe(getString(R.string.self_check_location_fused_cache), fusedCache))

            val tokenSource = CancellationTokenSource()
            val fusedRealtime = runCatching {
                Tasks.await(
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token),
                    2500L,
                    TimeUnit.MILLISECONDS
                )
            }.getOrNull()
            values.add(formatLocationProbe(getString(R.string.self_check_location_fused_realtime), fusedRealtime))

            val hasLocation = values.any { !it.contains(getString(R.string.self_check_location_no_data)) }
            Log.d(PHONE_DIAG_TAG, "Location check: \n" + values.joinToString("\n---\n"))
            CheckResult(
                if (hasLocation) CheckState.VISIBLE else CheckState.BLOCKED,
                if (hasLocation) getString(R.string.self_check_status_visible) else getString(R.string.self_check_status_not_visible),
                values.joinToString("\n\n")
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    @SuppressLint("MissingPermission")
    private fun awaitNativeCurrentLocation(locationManager: LocationManager): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null
        runCatching {
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                null,
                ContextCompat.getMainExecutor(this)
            ) { location ->
                result = location
                latch.countDown()
            }
            latch.await(2500L, TimeUnit.MILLISECONDS)
        }
        return result
    }

    private fun formatLocationProbe(label: String, location: Location?): String {
        if (location == null) {
            return "$label\n${getString(R.string.self_check_location_no_data)}"
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(location.time))
        return listOf(
            label,
            getString(R.string.self_check_location_coordinates, location.latitude, location.longitude),
            getString(R.string.self_check_location_accuracy_time, location.accuracy, time),
            "Provider: ${location.provider ?: getString(R.string.self_check_unknown_name)}"
        ).joinToString("\n")
    }

    private fun readAccounts(): CheckResult {
        if (!hasPermission(Manifest.permission.GET_ACCOUNTS)) {
            return CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_permission_needed),
                getString(R.string.self_check_accounts_permission_hint)
            )
        }

        return try {
            val accounts = AccountManager.get(this).accounts.orEmpty()
            val samples = accounts.take(8).joinToString("\n") { account ->
                "${account.name}\n${account.type}"
            }
            CheckResult(
                if (accounts.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_accounts_status, accounts.size, accounts.size),
                if (samples.isBlank()) getString(R.string.self_check_accounts_hidden) else samples
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun readWidevineId(): String? {
        return runCatching {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            MediaDrm(widevineUuid).use { mediaDrm ->
                mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                    .joinToString("") { "%02x".format(it) }
                    .takeUnless { it.isBlank() }
            }
        }.getOrNull()
    }

    private fun readNetworkInterfaces(): CheckResult {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val values = interfaces.take(10).mapNotNull { item ->
                val addresses = item.inetAddresses?.toList().orEmpty()
                    .mapNotNull { it.hostAddress }
                    .filterNot { it.isBlank() }
                    .take(4)
                if (addresses.isEmpty()) {
                    null
                } else {
                    listOfNotNull(
                        item.name,
                        addresses.takeIf { it.isNotEmpty() }?.joinToString(prefix = "IP: ")
                    ).joinToString("\n")
                }
            }

            visibleListResult(values, getString(R.string.self_check_status_visible), getString(R.string.self_check_network_hidden))
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    @SuppressLint("MissingPermission")
    private fun readWifiInfo(): CheckResult {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_permission_needed),
                getString(R.string.self_check_wifi_permission_hint)
            )
        }

        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val currentNetwork = listOfNotNull(
                connectionInfo?.ssid?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }?.let {
                    getString(R.string.self_check_wifi_current, it)
                }
            )

            val scans = wifiManager.scanResults.orEmpty()
            val scanSamples = scans.take(8).joinToString("\n") { result ->
                val ssid = result.SSID.ifBlank { getString(R.string.self_check_unknown_name) }
                "$ssid\n${result.BSSID}"
            }
            val content = (currentNetwork + scanSamples).joinToString("\n")

            CheckResult(
                if (content.isBlank()) CheckState.BLOCKED else CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_wifi_status, scans.size, scans.size),
                if (content.isBlank()) getString(R.string.self_check_wifi_hidden) else content
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBluetoothInfo(): CheckResult {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_permission_needed),
                getString(R.string.self_check_bluetooth_permission_hint)
            )
        }

        return try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null) {
                return CheckResult(
                    CheckState.BLOCKED,
                    getString(R.string.self_check_status_not_supported),
                    getString(R.string.self_check_bluetooth_not_supported)
                )
            }

            val deviceName = adapter.name ?: getString(R.string.self_check_unknown_name)
            val bondedDevices = adapter.bondedDevices.orEmpty()
            val samples = bondedDevices.take(8).joinToString("\n") { device ->
                val name = device.name ?: getString(R.string.self_check_unknown_name)
                "$name\n${device.address}"
            }

            val content = listOfNotNull(
                getString(R.string.self_check_bluetooth_name, deviceName),
                samples.takeIf { it.isNotBlank() }
            ).joinToString("\n\n")

            CheckResult(
                CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_bluetooth_status, bondedDevices.size, bondedDevices.size),
                content.ifBlank { getString(R.string.self_check_bluetooth_hidden) }
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    /**
     * 多通道交叉读取系统属性：把 Java 框架值（Build 静态字段，在普通应用进程里已被 Zygisk
     * 在 zygote 阶段改写）与 getprop 子进程、分区级 ro.product.* 属性这些"旁路通道"读到的值
     * 逐项对比。任何不一致都说明 spoof 没盖住该通道，真实信息从旁路泄漏。
     */
    private fun readSystemProperties(): CheckResult {
        val lines = mutableListOf<String>()
        var leaked = false

        data class PropProbe(val label: String, val frameworkValue: String?, val propKey: String)

        val probes = listOf(
            PropProbe("Brand", Build.BRAND, "ro.product.brand"),
            PropProbe("Manufacturer", Build.MANUFACTURER, "ro.product.manufacturer"),
            PropProbe("Model", Build.MODEL, "ro.product.model"),
            PropProbe("Device", Build.DEVICE, "ro.product.device"),
            PropProbe("Product", Build.PRODUCT, "ro.product.name"),
            PropProbe("Board", Build.BOARD, "ro.product.board"),
            PropProbe("Hardware", Build.HARDWARE, "ro.hardware"),
            PropProbe("Fingerprint", Build.FINGERPRINT, "ro.build.fingerprint")
        )

        probes.forEach { probe ->
            val getpropValue = runGetprop(probe.propKey)
            val framework = probe.frameworkValue?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
            if (!getpropValue.isNullOrBlank()) {
                val mismatch = framework != null && getpropValue != framework
                if (mismatch) leaked = true
                val marker = if (mismatch) " ${getString(R.string.self_check_channel_mismatch)}" else ""
                lines.add("${probe.label}:$marker")
                lines.add("  Build=${framework ?: "-"}")
                lines.add("  getprop ${probe.propKey}=$getpropValue")
            }
        }

        // 分区级属性：原生 spoofer 只改写裸 ro.product.brand 等，不覆盖各分区副本。
        val partitionKeys = listOf(
            "ro.product.vendor.model",
            "ro.product.vendor.brand",
            "ro.product.vendor.manufacturer",
            "ro.product.system.model",
            "ro.product.odm.model",
            "ro.product.bootimage.brand",
            "ro.bootimage.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint",
            "ro.system.build.fingerprint"
        )
        val partitionLeaks = mutableListOf<String>()
        partitionKeys.forEach { key ->
            val value = runGetprop(key)
            if (!value.isNullOrBlank()) {
                val realBrand = Build.BRAND?.takeUnless { it.isBlank() }
                val realModel = Build.MODEL?.takeUnless { it.isBlank() }
                val divergent = (realBrand != null && key.endsWith("brand") && value != realBrand) ||
                    (realModel != null && key.endsWith("model") && value != realModel) ||
                    (key.endsWith("fingerprint") && Build.FINGERPRINT.isNotBlank() && value != Build.FINGERPRINT)
                if (divergent) {
                    leaked = true
                    partitionLeaks.add("$key=$value ${getString(R.string.self_check_channel_mismatch)}")
                } else {
                    partitionLeaks.add("$key=$value")
                }
            }
        }
        if (partitionLeaks.isNotEmpty()) {
            lines.add(getString(R.string.self_check_props_partition_label))
            partitionLeaks.forEach { lines.add("  $it") }
        }

        // 序列号 / boot 标识：原生 spoofer 不替换 ro.serialno / ro.boot.serialno，仅设置 Build.SERIAL。
        val serialKeys = listOf("ro.serialno", "ro.boot.serialno", "ro.boot.bootreason", "ro.boot.vbmeta.digest")
        serialKeys.forEach { key ->
            val value = runGetprop(key)
            if (!value.isNullOrBlank()) {
                val isSerial = key.endsWith("serialno")
                if (isSerial) leaked = true
                val marker = if (isSerial) " ${getString(R.string.self_check_channel_leak)}" else ""
                lines.add("$key=${if (isSerial) value.maskMiddle() else value}$marker")
            }
        }

        return when {
            lines.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_status_not_visible),
                getString(R.string.self_check_props_hidden)
            )
            leaked -> CheckResult(
                CheckState.LEAKED,
                getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            )
            else -> CheckResult(
                CheckState.VISIBLE,
                getString(R.string.self_check_status_consistent),
                lines.joinToString("\n")
            )
        }
    }

    /**
     * 直接读取 /sys、/proc 下的内核文件，绕过所有 Java/Binder 层 hook：
     * 网卡 MAC（/sys/class/net/<iface>/address）、CPU 序列号、boot_id 等。
     * 这些通道暴露真实硬件标识即视为泄漏。
     */
    private fun readRawKernelFiles(): CheckResult {
        val lines = mutableListOf<String>()
        var leaked = false

        val netDir = File("/sys/class/net")
        val ifaces = runCatching { netDir.listFiles()?.map { it.name }?.sorted() }.getOrNull().orEmpty()
        ifaces.forEach { iface ->
            val mac = readRawFile("/sys/class/net/$iface/address")
                ?.trim()
                ?.takeUnless { it.isBlank() || it == "00:00:00:00:00:00" }
            if (mac != null) {
                val real = mac != "02:00:00:00:00:00" && !iface.startsWith("lo") && !iface.startsWith("dummy")
                if (real) leaked = true
                val marker = if (real) " ${getString(R.string.self_check_channel_leak)}" else ""
                lines.add("/sys/class/net/$iface/address = $mac$marker")
            }
        }

        readRawFile("/proc/sys/kernel/random/boot_id")?.trim()?.takeUnless { it.isBlank() }?.let {
            lines.add("/proc/sys/kernel/random/boot_id = $it")
        }

        runCatching {
            File("/proc/cpuinfo").bufferedReader().useLines { seq ->
                seq.filter { line ->
                    val lower = line.lowercase(Locale.ROOT)
                    lower.startsWith("serial") || lower.startsWith("hardware")
                }.take(4).toList()
            }
        }.getOrNull().orEmpty().forEach { line ->
            val value = line.substringAfter(':', "").trim()
            if (value.isNotBlank()) {
                val isSerial = line.lowercase(Locale.ROOT).startsWith("serial")
                if (isSerial) leaked = true
                val marker = if (isSerial) " ${getString(R.string.self_check_channel_leak)}" else ""
                lines.add("/proc/cpuinfo ${line.trim()}$marker")
            }
        }

        readRawFile("/sys/devices/soc0/serial_number")?.trim()?.takeUnless { it.isBlank() }?.let {
            leaked = true
            lines.add("/sys/devices/soc0/serial_number = ${it.maskMiddle()} ${getString(R.string.self_check_channel_leak)}")
        }

        return when {
            lines.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_status_not_visible),
                getString(R.string.self_check_rawfiles_hidden)
            )
            leaked -> CheckResult(
                CheckState.LEAKED,
                getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            )
            else -> CheckResult(
                CheckState.VISIBLE,
                getString(R.string.self_check_status_visible),
                lines.joinToString("\n")
            )
        }
    }

    /**
     * 对多个 MediaDrm UUID 探测 deviceUniqueId / 厂商信息。原生 DRM hook 只覆盖 Widevine
     * 的 deviceUniqueId，其它 scheme（ClearKey/PlayReady）或其它属性可能暴露真实设备标识。
     */
    private fun readDrmInfo(): CheckResult {
        data class Scheme(val name: String, val uuid: UUID, val spoofed: Boolean)

        val schemes = listOf(
            // Widevine edef8ba9-79d6-4ace-a3c8-27dcd51d21ed —— 原生 DRM hook 已覆盖
            Scheme("Widevine", UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L), spoofed = true),
            // PlayReady 9a04f079-9840-4286-ab92-e65be0885f95
            Scheme("PlayReady", UUID(0x9a04f07998404286uL.toLong(), 0xab92e65be0885f95uL.toLong()), spoofed = false),
            // ClearKey e2719d58-a985-b3c9-781a-b030af78d30e
            Scheme("ClearKey", UUID(0xe2719d58a985b3c9uL.toLong(), 0x781ab030af78d30euL.toLong()), spoofed = false)
        )

        val lines = mutableListOf<String>()
        var leaked = false

        schemes.forEach { scheme ->
            val supported = runCatching { MediaDrm.isCryptoSchemeSupported(scheme.uuid) }.getOrDefault(false)
            if (!supported) return@forEach
            runCatching {
                MediaDrm(scheme.uuid).use { drm ->
                    val deviceId = runCatching {
                        drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                            .joinToString("") { "%02x".format(it) }
                    }.getOrNull()?.takeUnless { it.isBlank() }
                    val vendor = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VENDOR) }.getOrNull()
                    val version = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VERSION) }.getOrNull()

                    lines.add(scheme.name)
                    vendor?.takeUnless { it.isBlank() }?.let { lines.add("  Vendor: $it") }
                    version?.takeUnless { it.isBlank() }?.let { lines.add("  Version: $it") }
                    if (deviceId != null) {
                        // 非 Widevine scheme 仍能返回唯一设备 ID = 旁路泄漏。
                        if (!scheme.spoofed) leaked = true
                        val marker = if (!scheme.spoofed) " ${getString(R.string.self_check_channel_leak)}" else ""
                        lines.add("  Device unique ID: ${deviceId.maskMiddle()}$marker")
                    }
                }
            }.onFailure {
                Log.e(PHONE_DIAG_TAG, "readDrmInfo ${scheme.name} failed", it)
            }
        }

        return when {
            lines.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_status_not_visible),
                getString(R.string.self_check_drm_hidden)
            )
            leaked -> CheckResult(
                CheckState.LEAKED,
                getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            )
            else -> CheckResult(
                CheckState.VISIBLE,
                getString(R.string.self_check_status_visible),
                lines.joinToString("\n")
            )
        }
    }

    /**
     * 读取 Settings.Global / Secure 表里常被用作设备指纹的键。这些经由 SettingsProvider，
     * FuckSettingsProvider 只挑选部分键 spoof，其余直读暴露。
     */
    @SuppressLint("HardwareIds")
    private fun readSettingsTables(): CheckResult {
        val lines = mutableListOf<String>()

        fun globalKey(key: String) = runCatching {
            Settings.Global.getString(contentResolver, key)
        }.getOrNull()?.takeUnless { it.isBlank() }

        fun secureKey(key: String) = runCatching {
            Settings.Secure.getString(contentResolver, key)
        }.getOrNull()?.takeUnless { it.isBlank() }

        globalKey("device_name")?.let { lines.add("Global device_name: $it") }
        globalKey("bluetooth_name")?.let { lines.add("Global bluetooth_name: $it") }
        globalKey("bluetooth_address")?.let { lines.add("Global bluetooth_address: $it") }
        globalKey("wifi_country_code")?.let { lines.add("Global wifi_country_code: $it") }
        globalKey("boot_count")?.let { lines.add("Global boot_count: $it") }
        globalKey("adb_enabled")?.let { lines.add("Global adb_enabled: $it") }
        globalKey("development_settings_enabled")?.let { lines.add("Global development_settings_enabled: $it") }
        globalKey("install_non_market_apps")?.let { lines.add("Global install_non_market_apps: $it") }

        secureKey("bluetooth_name")?.let { lines.add("Secure bluetooth_name: $it") }
        secureKey("bluetooth_address")?.let { lines.add("Secure bluetooth_address: $it") }
        secureKey("android_id")?.let { lines.add("Secure android_id: ${it.maskMiddle()}") }

        return visibleListResult(
            lines,
            getString(R.string.self_check_status_visible),
            getString(R.string.self_check_settings_hidden)
        )
    }

    /**
     * 列出传感器清单。传感器型号/厂商组合可作为稳定设备指纹，且通常不在任何 spoof 范围内。
     */
    private fun readSensors(): CheckResult {
        return try {
            val sensorManager = getSystemService(SensorManager::class.java)
            val sensors = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            val values = sensors.take(20).map { sensor ->
                "${sensor.name} | ${sensor.vendor}"
            }
            CheckResult(
                if (values.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_sensors_status, sensors.size, sensors.size),
                if (values.isEmpty()) getString(R.string.self_check_sensors_hidden) else values.joinToString("\n")
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun runGetprop(key: String): String? {
        return runCatching {
            val process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() }
            process.waitFor(1500, TimeUnit.MILLISECONDS)
            output?.trim()?.takeUnless { it.isBlank() }
        }.getOrNull()
    }

    private fun readRawFile(path: String): String? {
        return runCatching {
            val file = File(path)
            if (!file.canRead()) null else file.readText()
        }.getOrNull()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun readSystemProperty(key: String): String? {
        return runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            systemPropertiesClass
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String
        }.getOrNull()
    }

    private fun CellInfo.sanitizeForDisplay(): String {
        val identity = runCatching {
            javaClass.methods.firstOrNull { it.name == "getCellIdentity" && it.parameterTypes.isEmpty() }
                ?.invoke(this) as? CellIdentity
        }.getOrNull()
        return identity?.toString() ?: toString()
    }

    private fun visibleListResult(values: List<String>, visibleStatus: String, emptyText: String): CheckResult {
        val filtered = values.filter { it.isNotBlank() }
        return CheckResult(
            if (filtered.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
            if (filtered.isEmpty()) getString(R.string.self_check_status_not_visible) else visibleStatus,
            if (filtered.isEmpty()) emptyText else filtered.joinToString("\n")
        )
    }

    private fun Exception.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    private fun logPhoneProbe(label: String, result: Result<String?>) {
        result
            .onSuccess { value ->
                Log.d(PHONE_DIAG_TAG, "$label success value=${value.maskForPhoneDiag()}")
            }
            .onFailure { error ->
                Log.e(PHONE_DIAG_TAG, "$label failed", error)
            }
    }

    private fun String?.maskForPhoneDiag(): String {
        if (this == null) return "null"
        if (isBlank()) return "blank(len=$length)"
        return if (length <= 4) "len=$length,value=$this" else "len=$length,tail=${takeLast(4)}"
    }

    private fun String.maskMiddle(): String {
        return if (length <= 8) this else "${take(4)}****${takeLast(4)}"
    }

    private data class CheckResult(
        val state: CheckState,
        val status: String,
        val content: String
    )

    private enum class CheckState {
        VISIBLE,
        BLOCKED,
        LEAKED
    }

    private class CheckSectionView(val root: View) {
        private val icon: ImageView = root.findViewById(R.id.sectionStatusIcon)
        private val title: TextView = root.findViewById(R.id.sectionTitle)
        private val status: TextView = root.findViewById(R.id.sectionStatus)
        private val content: TextView = root.findViewById(R.id.sectionContent)

        fun bindTitle(value: String) {
            title.text = value
        }

        fun bindResult(result: CheckResult) {
            icon.setImageResource(
                when (result.state) {
                    CheckState.VISIBLE -> R.drawable.ic_status_enabled
                    CheckState.BLOCKED -> R.drawable.ic_status_disabled
                    CheckState.LEAKED -> R.drawable.ic_status_leak
                }
            )
            status.text = result.status
            content.text = result.content
        }
    }
}
