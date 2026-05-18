package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import asia.nana7mi.arirang.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.UUID

class SelfCheckActivity : BaseActivity() {

    private companion object {
        private const val PHONE_DIAG_TAG = "Arirang/SelfCheckPhoneDiag"
    }

    private lateinit var summaryText: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var scrollView: ScrollView
    private lateinit var deviceSection: CheckSectionView
    private lateinit var buildSection: CheckSectionView
    private lateinit var telephonySection: CheckSectionView
    private lateinit var simSection: CheckSectionView
    private lateinit var appsSection: CheckSectionView
    private lateinit var wifiSection: CheckSectionView
    private lateinit var accountsSection: CheckSectionView
    private lateinit var drmSection: CheckSectionView
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
        deviceSection = CheckSectionView(findViewById(R.id.deviceSection))
        buildSection = CheckSectionView(findViewById(R.id.buildSection))
        telephonySection = CheckSectionView(findViewById(R.id.telephonySection))
        simSection = CheckSectionView(findViewById(R.id.simSection))
        appsSection = CheckSectionView(findViewById(R.id.appsSection))
        wifiSection = CheckSectionView(findViewById(R.id.wifiSection))
        accountsSection = CheckSectionView(findViewById(R.id.accountsSection))
        drmSection = CheckSectionView(findViewById(R.id.drmSection))
        networkSection = CheckSectionView(findViewById(R.id.networkSection))
        bluetoothSection = CheckSectionView(findViewById(R.id.bluetoothSection))

        deviceSection.bindTitle(getString(R.string.self_check_device_title))
        buildSection.bindTitle(getString(R.string.self_check_build_title))
        telephonySection.bindTitle(getString(R.string.self_check_telephony_title))
        simSection.bindTitle(getString(R.string.self_check_sim_title))
        appsSection.bindTitle(getString(R.string.self_check_apps_title))
        wifiSection.bindTitle(getString(R.string.self_check_wifi_title))
        accountsSection.bindTitle(getString(R.string.self_check_accounts_title))
        drmSection.bindTitle(getString(R.string.self_check_drm_title))
        networkSection.bindTitle(getString(R.string.self_check_network_title))
        bluetoothSection.bindTitle(getString(R.string.self_check_bluetooth_title))

        setupSectionNavigation()
        runButton.setOnClickListener { requestMissingPermissionsOrRun() }
        runSelfCheck()
    }

    private fun setupSectionNavigation() {
        findViewById<View>(R.id.navDeviceChip).setOnClickListener {
            scrollToSection(deviceSection.root)
        }
        findViewById<View>(R.id.navBuildChip).setOnClickListener {
            scrollToSection(buildSection.root)
        }
        findViewById<View>(R.id.navTelephonyChip).setOnClickListener {
            scrollToSection(telephonySection.root)
        }
        findViewById<View>(R.id.navSimChip).setOnClickListener {
            scrollToSection(simSection.root)
        }
        findViewById<View>(R.id.navAppsChip).setOnClickListener {
            scrollToSection(appsSection.root)
        }
        findViewById<View>(R.id.navWifiChip).setOnClickListener {
            scrollToSection(wifiSection.root)
        }
        findViewById<View>(R.id.navAccountsChip).setOnClickListener {
            scrollToSection(accountsSection.root)
        }
        findViewById<View>(R.id.navDrmChip).setOnClickListener {
            scrollToSection(drmSection.root)
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
            val device = async(Dispatchers.IO) { readDeviceIdentifier() }
            val build = async(Dispatchers.IO) { readBuildInfo() }
            val telephony = async(Dispatchers.IO) { readTelephonyInfo() }
            val sim = async(Dispatchers.IO) { readSimInfo() }
            val apps = async(Dispatchers.IO) { readInstalledApps() }
            val wifi = async(Dispatchers.IO) { readWifiInfo() }
            val accounts = async(Dispatchers.IO) { readAccounts() }
            val drm = async(Dispatchers.IO) { readDrmIdentifiers() }
            val network = async(Dispatchers.IO) { readNetworkInterfaces() }
            val bluetooth = async(Dispatchers.IO) { readBluetoothInfo() }

            val results = listOf(
                R.string.self_check_device_title to device.await(),
                R.string.self_check_build_title to build.await(),
                R.string.self_check_telephony_title to telephony.await(),
                R.string.self_check_sim_title to sim.await(),
                R.string.self_check_apps_title to apps.await(),
                R.string.self_check_wifi_title to wifi.await(),
                R.string.self_check_accounts_title to accounts.await(),
                R.string.self_check_drm_title to drm.await(),
                R.string.self_check_network_title to network.await(),
                R.string.self_check_bluetooth_title to bluetooth.await()
            )
            lastResults = results

            deviceSection.bindResult(results[0].second)
            buildSection.bindResult(results[1].second)
            telephonySection.bindResult(results[2].second)
            simSection.bindResult(results[3].second)
            appsSection.bindResult(results[4].second)
            wifiSection.bindResult(results[5].second)
            accountsSection.bindResult(results[6].second)
            drmSection.bindResult(results[7].second)
            networkSection.bindResult(results[8].second)
            bluetoothSection.bindResult(results[9].second)

            val visibleCount = results.count { it.second.state == CheckState.VISIBLE }
            val blockedCount = results.count { it.second.state == CheckState.BLOCKED }
            summaryText.text = getString(R.string.self_check_summary_result, visibleCount, blockedCount)
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
        deviceSection.bindResult(loading)
        buildSection.bindResult(loading)
        telephonySection.bindResult(loading)
        simSection.bindResult(loading)
        appsSection.bindResult(loading)
        wifiSection.bindResult(loading)
        accountsSection.bindResult(loading)
        drmSection.bindResult(loading)
        networkSection.bindResult(loading)
        bluetoothSection.bindResult(loading)
    }

    @SuppressLint("HardwareIds")
    private fun readDeviceIdentifier(): CheckResult {
        val values = listOfNotNull(
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeUnless { it.isBlank() }
                ?.let { "Android ID: ${it.maskMiddle()}" },
            readAdvertisingId()?.let { "GAID: ${it.maskMiddle()}" },
            readGsfId()?.let { "GSF ID: ${it.maskMiddle()}" },
            readAppSetId()?.let { "App Set ID: ${it.maskMiddle()}" },
            runCatching { Build.getSerial() }
                .getOrNull()
                ?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
                ?.let { "Serial: ${it.maskMiddle()}" }
        )

        return visibleListResult(
            values,
            getString(R.string.self_check_status_visible),
            getString(R.string.self_check_device_hidden)
        )
    }

    private fun readAdvertisingId(): String? {
        return runCatching {
            val clientClass = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val info = clientClass
                .getMethod("getAdvertisingIdInfo", Context::class.java)
                .invoke(null, this) ?: return@runCatching null
            info.javaClass.methods
                .firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
                ?.invoke(info) as? String
        }.getOrNull()?.takeUnless { it.isBlank() }
    }

    private fun readGsfId(): String? {
        return runCatching {
            contentResolver.query(
                Uri.parse("content://com.google.android.gsf.gservices"),
                null,
                null,
                arrayOf("android_id"),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.columnCount < 2) {
                    null
                } else {
                    val raw = cursor.getString(1)?.takeUnless { it.isBlank() }
                    raw?.toLongOrNull()?.toString(16) ?: raw
                }
            }
        }.getOrNull()?.takeUnless { it.isBlank() }
    }

    private fun readAppSetId(): String? {
        return runCatching {
            val appSetClass = Class.forName("com.google.android.gms.appset.AppSet")
            val client = appSetClass
                .getMethod("getClient", Context::class.java)
                .invoke(null, this) ?: return@runCatching null
            val task = client.javaClass.methods
                .firstOrNull { it.name == "getAppSetIdInfo" && it.parameterTypes.isEmpty() }
                ?.invoke(client) ?: return@runCatching null
            val tasksClass = Class.forName("com.google.android.gms.tasks.Tasks")
            val info = tasksClass
                .getMethod("await", Class.forName("com.google.android.gms.tasks.Task"), Long::class.javaPrimitiveType, TimeUnit::class.java)
                .invoke(null, task, 1500L, TimeUnit.MILLISECONDS) ?: return@runCatching null
            info.javaClass.methods
                .firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
                ?.invoke(info) as? String
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
            "Fingerprint" to Build.FINGERPRINT,
            "Build time" to Build.TIME.takeIf { it > 0 }?.toString(),
            "Build.SERIAL" to Build.SERIAL,
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

            runCatching { Build.getSerial() }
                .getOrNull()
                ?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
                ?.let { values.add("System serial: ${it.maskMiddle()}") }

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

                val scopedImei = runCatching {
                    telephonyManager.createForSubscriptionId(subscription.subscriptionId).imei
                }
                logPhoneProbe(
                    "TelephonyManager.createForSubscriptionId(${subscription.subscriptionId}).getImei",
                    scopedImei
                )
                scopedImei.getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("IMEI sub ${subscription.subscriptionId}: ${it.maskMiddle()}") }

                @Suppress("DEPRECATION")
                val scopedDeviceId = runCatching {
                    telephonyManager.createForSubscriptionId(subscription.subscriptionId).deviceId
                }
                logPhoneProbe(
                    "TelephonyManager.createForSubscriptionId(${subscription.subscriptionId}).getDeviceId",
                    scopedDeviceId
                )
                scopedDeviceId.getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("Device ID sub ${subscription.subscriptionId}: ${it.maskMiddle()}") }

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

            runCatching { telephonyManager.simSerialNumber }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("SIM serial: ${it.maskMiddle()}") }

            runCatching { telephonyManager.subscriberId }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Subscriber ID: ${it.maskMiddle()}") }

            runCatching { telephonyManager.imei }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("IMEI default: ${it.maskMiddle()}") }

            @Suppress("DEPRECATION")
            runCatching { telephonyManager.deviceId }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Device ID default: ${it.maskMiddle()}") }

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
                runCatching { telephonyManager.getImei(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("IMEI slot $slot: ${it.maskMiddle()}") }

                runCatching { telephonyManager.getMeid(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("MEID slot $slot: ${it.maskMiddle()}") }

                runCatching { telephonyManager.getNetworkCountryIso(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("Network country ISO slot $slot: $it") }

                runCatching { telephonyManager.getTypeAllocationCode(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("Type allocation code slot $slot: ${it.maskMiddle()}") }
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
                    sub.iccId?.takeIf { it.isNotBlank() }?.let { "ICCID: ${it.maskMiddle()}" },
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

    private fun readDrmIdentifiers(): CheckResult {
        return try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            MediaDrm(widevineUuid).use { mediaDrm ->
                val deviceUniqueId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                val id = deviceUniqueId.joinToString("") { "%02x".format(it) }
                CheckResult(
                    if (id.isBlank()) CheckState.BLOCKED else CheckState.VISIBLE,
                    getString(R.string.self_check_status_visible),
                    if (id.isBlank()) getString(R.string.self_check_drm_hidden) else "Widevine ID: ${id.maskMiddle()}"
                )
            }
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun readNetworkInterfaces(): CheckResult {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val values = interfaces.take(10).mapNotNull { item ->
                val hardwareAddress = item.hardwareAddress?.joinToString(":") { "%02x".format(it) }
                val addresses = item.inetAddresses?.toList().orEmpty()
                    .mapNotNull { it.hostAddress }
                    .filterNot { it.isBlank() }
                    .take(4)
                if (hardwareAddress == null && addresses.isEmpty()) {
                    null
                } else {
                    listOfNotNull(
                        item.name,
                        hardwareAddress?.let { "MAC: $it" },
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
                },
                connectionInfo?.bssid?.takeUnless { it.isBlank() || it == "02:00:00:00:00:00" }?.let {
                    getString(R.string.self_check_wifi_bssid, it)
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

            val bondedDevices = adapter.bondedDevices.orEmpty()
            val samples = bondedDevices.take(8).joinToString("\n") { device ->
                val name = device.name ?: getString(R.string.self_check_unknown_name)
                "$name\n${device.address}"
            }

            CheckResult(
                if (bondedDevices.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                resources.getQuantityString(R.plurals.self_check_bluetooth_status, bondedDevices.size, bondedDevices.size),
                if (samples.isBlank()) getString(R.string.self_check_bluetooth_hidden) else samples
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
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
        BLOCKED
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
                if (result.state == CheckState.VISIBLE) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
            )
            status.text = result.status
            content.text = result.content
        }
    }
}
