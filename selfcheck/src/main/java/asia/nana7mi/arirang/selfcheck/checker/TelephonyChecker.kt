package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskForPhoneDiag
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TelephonyChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_telephony_title
    override val navChipId: Int = R.id.navTelephonyChip

    @SuppressLint("HardwareIds", "MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_phone_permission_hint)
            )
        }

        try {
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            val phoneCount = telephonyManager.phoneCount
            val values = mutableListOf<String>()
            Log.d(
                CheckDefinitions.PHONE_DIAG_TAG,
                "readTelephonyInfo start package=${context.packageName} phoneCount=$phoneCount " +
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
                Log.e(CheckDefinitions.PHONE_DIAG_TAG, "SubscriptionManager.activeSubscriptionInfoList failed", it)
            }.getOrDefault(emptyList())

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
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("SIM country ISO: $it") }

            runCatching { telephonyManager.simOperator }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("SIM operator numeric: $it") }

            runCatching { telephonyManager.simOperatorName }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("SIM operator name: $it") }

            runCatching { telephonyManager.simCarrierId }
                .getOrNull()?.let { values.add("SIM carrier ID: $it") }

            runCatching { telephonyManager.simCarrierIdName }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("SIM carrier ID name: $it") }

            runCatching { telephonyManager.carrierIdFromSimMccMnc }
                .getOrNull()?.let { values.add("Carrier ID from SIM MCC/MNC: $it") }

            runCatching { telephonyManager.networkCountryIso }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("Network country ISO: $it") }

            runCatching { telephonyManager.networkOperator }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("Network operator numeric: $it") }

            runCatching { telephonyManager.networkOperatorName }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("Network operator name: $it") }

            runCatching { telephonyManager.serviceState }
                .getOrNull()?.let { values.add("ServiceState: $it") }

            runCatching { telephonyManager.allCellInfo.orEmpty() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "CellInfo: ${it.sanitizeForDisplay()}" }
                ?.let { values.add(it) }

            runCatching { telephonyManager.cellLocation }
                .getOrNull()?.let { values.add("CellLocation: $it") }

            runCatching { telephonyManager.forbiddenPlmns.orEmpty() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Forbidden PLMNs: ")
                ?.let { values.add(it) }

            runCatching { telephonyManager.equivalentHomePlmns.orEmpty() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Equivalent home PLMNs: ")
                ?.let { values.add(it) }

            runCatching { telephonyManager.groupIdLevel1 }
                .getOrNull()?.takeUnless { it.isBlank() }?.let { values.add("Group ID level 1: $it") }

            runCatching { telephonyManager.emergencyNumberList }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.entries
                ?.joinToString("\n") { entry ->
                    val numbers = entry.value.take(6).joinToString { it.toString() }
                    "Emergency numbers[${entry.key}]: $numbers"
                }?.let { values.add(it) }

            repeat(phoneCount.coerceAtMost(4)) { slot ->
                runCatching { telephonyManager.getNetworkCountryIso(slot) }
                    .getOrNull()?.takeUnless { it.isBlank() }
                    ?.let { values.add("Network country ISO slot $slot: $it") }
            }

            val rilLines = CheckDefinitions.RIL_PROP_KEYS.mapNotNull { key ->
                CheckUtils.runGetprop(key)?.let { value ->
                    val sensitive = key.startsWith("ril.")
                    "getprop $key=${if (sensitive) value.maskMiddle() else value}" +
                        if (sensitive) " ${context.getString(R.string.self_check_channel_leak)}" else ""
                }
            }
            if (rilLines.isNotEmpty()) {
                values.add(context.getString(R.string.self_check_telephony_ril_label))
                values.addAll(rilLines)
            }

            val rawLeak = rilLines.any { it.contains(context.getString(R.string.self_check_channel_leak)) }
            val filtered = values.filter { it.isNotBlank() }
            when {
                filtered.isEmpty() -> CheckResult(
                    CheckState.BLOCKED,
                    context.getString(R.string.self_check_status_not_visible),
                    context.getString(R.string.self_check_telephony_hidden)
                )
                rawLeak -> CheckResult(
                    CheckState.LEAKED,
                    context.getString(R.string.self_check_status_leaked),
                    filtered.joinToString("\n")
                )
                else -> CheckResult(
                    CheckState.VISIBLE,
                    context.getString(R.string.self_check_status_visible),
                    filtered.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun logPhoneProbe(label: String, result: Result<String?>) {
        result
            .onSuccess { value ->
                Log.d(CheckDefinitions.PHONE_DIAG_TAG, "$label success value=${value.maskForPhoneDiag()}")
            }
            .onFailure { error ->
                Log.e(CheckDefinitions.PHONE_DIAG_TAG, "$label failed", error)
            }
    }

    private fun CellInfo.sanitizeForDisplay(): String {
        val identity = runCatching {
            javaClass.methods.firstOrNull { it.name == "getCellIdentity" && it.parameterTypes.isEmpty() }
                ?.invoke(this) as? CellIdentity
        }.getOrNull()
        return identity?.toString() ?: toString()
    }
}
