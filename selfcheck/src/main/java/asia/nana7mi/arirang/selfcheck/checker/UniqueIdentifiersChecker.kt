package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaDrm
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.TimeUnit

class UniqueIdentifiersChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_unique_title
    override val navChipId: Int = R.id.navUniqueChip

    @SuppressLint("HardwareIds", "MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val values = mutableListOf<String>()
        val notes = mutableListOf<String>()

        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeUnless { it.isBlank() }
            ?.let { values.add("Android ID: ${it.maskMiddle()}") }

        readAdvertisingId(context)?.let { values.add("GAID: ${it.maskMiddle()}") }
        readAppSetId(context)?.let { values.add("App Set ID: ${it.maskMiddle()}") }
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

        readPhoneUniqueIdentifiers(context, values, notes)
        readNetworkUniqueIdentifiers(context, values)

        val content = (values + notes).filter { it.isNotBlank() }
        CheckResult(
            if (values.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
            if (values.isEmpty()) context.getString(R.string.self_check_status_not_visible) else context.getString(R.string.self_check_status_visible),
            if (content.isEmpty()) context.getString(R.string.self_check_unique_hidden) else content.joinToString("\n")
        )
    }

    private fun readAdvertisingId(context: Context): String? {
        return runCatching {
            AdvertisingIdClient.getAdvertisingIdInfo(context).id
        }.onFailure {
            Log.e(CheckDefinitions.PHONE_DIAG_TAG, "readAdvertisingId failed", it)
        }.getOrNull()?.takeUnless { it.isBlank() }
    }

    private fun readAppSetId(context: Context): String? {
        return runCatching {
            val client = AppSet.getClient(context.applicationContext)
            Tasks.await(client.appSetIdInfo, 1500L, TimeUnit.MILLISECONDS).id
        }.onFailure {
            Log.e(CheckDefinitions.PHONE_DIAG_TAG, "readAppSetId failed", it)
        }.getOrNull()?.takeUnless { it.isBlank() }
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

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readPhoneUniqueIdentifiers(context: Context, values: MutableList<String>, notes: MutableList<String>) {
        if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
            notes.add(context.getString(R.string.self_check_phone_permission_hint))
            return
        }

        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

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
    private fun readNetworkUniqueIdentifiers(context: Context, values: MutableList<String>) {
        if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.connectionInfo
            }.getOrNull()?.let { addWifiIdentifiers(values, "WifiManager.connectionInfo", it) }

            runCatching {
                val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                val network = connectivityManager?.activeNetwork ?: return@runCatching null
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@runCatching null
                capabilities.transportInfo as? WifiInfo
            }.getOrNull()?.let { addWifiIdentifiers(values, "ConnectivityManager.transportInfo", it) }
        }

        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .mapNotNull { item ->
                    val mac = item.hardwareAddress?.joinToString(":") { "%02x".format(it) }
                    mac?.takeUnless { it.isBlank() || it == REDACTED_MAC }
                        ?.let { "${item.name} MAC: $it" }
                }
                .take(6)
        }.getOrDefault(emptyList()).forEach(values::add)

        if (hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            runCatching {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothManager.adapter?.address
            }.getOrNull()
                ?.takeUnless { it.isBlank() || it == REDACTED_MAC }
                ?.let { values.add("Bluetooth MAC: $it") }
        }
    }

    @SuppressLint("HardwareIds")
    private fun addWifiIdentifiers(values: MutableList<String>, source: String, wifiInfo: WifiInfo) {
        @Suppress("DEPRECATION")
        wifiInfo.macAddress
            ?.takeUnless { it.isBlank() || it.equals(REDACTED_MAC, ignoreCase = true) }
            ?.let { values.add("Wi-Fi MAC ($source): $it") }
        wifiInfo.bssid
            ?.takeUnless { it.isBlank() || it.equals(REDACTED_MAC, ignoreCase = true) }
            ?.let { values.add("Wi-Fi BSSID ($source): $it") }
    }

    private companion object {
        private const val REDACTED_MAC = "02:00:00:00:00:00"
    }
}
