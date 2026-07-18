package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.os.Build
import android.util.Log
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SystemPropertiesChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_props_title
    override val navChipId: Int = R.id.navPropsChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var leaked = false

        val frameworkProbes = listOf(
            Build.BRAND, Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
            Build.PRODUCT, Build.BOARD, Build.HARDWARE, Build.FINGERPRINT
        )

        CheckDefinitions.SYSTEM_PROP_PROBES.forEachIndexed { i, probe ->
            val getpropValue = CheckUtils.runGetprop(probe.second)
            val frameworkValue = frameworkProbes[i]
            val framework = frameworkValue?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
            if (!getpropValue.isNullOrBlank()) {
                val mismatch = framework != null && getpropValue != framework
                if (mismatch) leaked = true
                val marker = if (mismatch) " ${context.getString(R.string.self_check_channel_mismatch)}" else ""
                lines.add("${probe.first}:$marker")
                lines.add("  Build=${framework ?: "-"}")
                lines.add("  getprop ${probe.second}=$getpropValue")
            }
        }

        val partitionLeaks = mutableListOf<String>()
        CheckDefinitions.PARTITION_PROP_KEYS.forEach { key ->
            val value = CheckUtils.runGetprop(key)
            if (!value.isNullOrBlank()) {
                val realBrand = Build.BRAND?.takeUnless { it.isBlank() }
                val realModel = Build.MODEL?.takeUnless { it.isBlank() }
                val divergent = (realBrand != null && key.endsWith("brand") && value != realBrand) ||
                    (realModel != null && key.endsWith("model") && value != realModel) ||
                    (key.endsWith("fingerprint") && Build.FINGERPRINT.isNotBlank() && value != Build.FINGERPRINT)
                if (divergent) {
                    leaked = true
                    partitionLeaks.add("$key=$value ${context.getString(R.string.self_check_channel_mismatch)}")
                } else {
                    partitionLeaks.add("$key=$value")
                }
            }
        }
        if (partitionLeaks.isNotEmpty()) {
            lines.add(context.getString(R.string.self_check_props_partition_label))
            partitionLeaks.forEach { lines.add("  $it") }
        }

        val audioProp = CheckUtils.runGetprop("ro.hardware.audio.primary")
        if (!audioProp.isNullOrBlank()) {
            val isLeak = audioProp != Build.BOARD
            if (isLeak) leaked = true
            val marker = if (isLeak) " ${context.getString(R.string.self_check_channel_leak)}" else ""
            lines.add("ro.hardware.audio.primary=$audioProp$marker")
        }

        CheckDefinitions.SERIAL_PROP_KEYS.forEach { key ->
            val value = CheckUtils.runGetprop(key)
            if (!value.isNullOrBlank()) {
                val isSerial = key.endsWith("serialno")
                if (isSerial) leaked = true
                val marker = if (isSerial) " ${context.getString(R.string.self_check_channel_leak)}" else ""
                lines.add("$key=${if (isSerial) value.maskMiddle() else value}$marker")
            }
        }

        when {
            lines.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                context.getString(R.string.self_check_props_hidden)
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "System Properties CheckResult: \n${it.content}") }
            leaked -> CheckResult(
                CheckState.LEAKED,
                context.getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "System Properties CheckResult: \n${it.content}") }
            else -> CheckResult(
                CheckState.VISIBLE,
                context.getString(R.string.self_check_status_consistent),
                lines.joinToString("\n")
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "System Properties CheckResult: \n${it.content}") }
        }
    }
}
