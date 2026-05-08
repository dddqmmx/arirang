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
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import asia.nana7mi.arirang.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.UUID

class SelfCheckActivity : BaseActivity() {

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
                device.await(),
                build.await(),
                telephony.await(),
                sim.await(),
                apps.await(),
                wifi.await(),
                accounts.await(),
                drm.await(),
                network.await(),
                bluetooth.await()
            )
            deviceSection.bindResult(results[0])
            buildSection.bindResult(results[1])
            telephonySection.bindResult(results[2])
            simSection.bindResult(results[3])
            appsSection.bindResult(results[4])
            wifiSection.bindResult(results[5])
            accountsSection.bindResult(results[6])
            drmSection.bindResult(results[7])
            networkSection.bindResult(results[8])
            bluetoothSection.bindResult(results[9])

            val visibleCount = results.count { it.state == CheckState.VISIBLE }
            val blockedCount = results.count { it.state == CheckState.BLOCKED }
            summaryText.text = getString(R.string.self_check_summary_result, visibleCount, blockedCount)
            runButton.isEnabled = true
            runButton.setText(R.string.self_check_run_again)
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

    private fun readDeviceIdentifier(): CheckResult {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (androidId.isNullOrBlank()) {
            CheckResult(
                CheckState.BLOCKED,
                getString(R.string.self_check_status_not_visible),
                getString(R.string.self_check_device_hidden)
            )
        } else {
            CheckResult(
                CheckState.VISIBLE,
                getString(R.string.self_check_status_visible),
                getString(R.string.self_check_device_visible, androidId.maskMiddle())
            )
        }
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
            "Build.SERIAL" to Build.SERIAL
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
            val phoneCount = telephonyManager.phoneCount
            val values = mutableListOf<String>()

            runCatching { Build.getSerial() }
                .getOrNull()
                ?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
                ?.let { values.add("System serial: ${it.maskMiddle()}") }

            runCatching { telephonyManager.line1Number }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Phone number: ${it.maskMiddle()}") }

            runCatching { telephonyManager.simSerialNumber }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("SIM serial: ${it.maskMiddle()}") }

            runCatching { telephonyManager.subscriberId }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?.let { values.add("Subscriber ID: ${it.maskMiddle()}") }

            repeat(phoneCount.coerceAtMost(4)) { slot ->
                runCatching { telephonyManager.getImei(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("IMEI slot $slot: ${it.maskMiddle()}") }

                runCatching { telephonyManager.getMeid(slot) }
                    .getOrNull()
                    ?.takeUnless { it.isBlank() }
                    ?.let { values.add("MEID slot $slot: ${it.maskMiddle()}") }
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
