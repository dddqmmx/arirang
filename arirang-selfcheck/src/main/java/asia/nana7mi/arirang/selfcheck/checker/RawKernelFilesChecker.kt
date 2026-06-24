package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class RawKernelFilesChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_rawfiles_title
    override val navChipId: Int = R.id.navRawFilesChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var leaked = false

        val netDir = File("/sys/class/net")
        val ifaces = runCatching { netDir.listFiles()?.map { it.name }?.sorted() }.getOrNull().orEmpty()
        ifaces.forEach { iface ->
            val mac = CheckUtils.readRawFile("/sys/class/net/$iface/address")
                ?.trim()
                ?.takeUnless { it.isBlank() || it == "00:00:00:00:00:00" }
            if (mac != null) {
                val real = mac != "02:00:00:00:00:00" && !iface.startsWith("lo") && !iface.startsWith("dummy")
                if (real) leaked = true
                val marker = if (real) " ${context.getString(R.string.self_check_channel_leak)}" else ""
                lines.add("/sys/class/net/$iface/address = $mac$marker")
            }
        }

        CheckUtils.readRawFile("/proc/sys/kernel/random/boot_id")?.trim()?.takeUnless { it.isBlank() }?.let {
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
                val marker = if (isSerial) " ${context.getString(R.string.self_check_channel_leak)}" else ""
                lines.add("/proc/cpuinfo ${line.trim()}$marker")
            }
        }

        CheckUtils.readRawFile("/sys/devices/soc0/serial_number")?.trim()?.takeUnless { it.isBlank() }?.let {
            leaked = true
            lines.add("/sys/devices/soc0/serial_number = ${it.maskMiddle()} ${context.getString(R.string.self_check_channel_leak)}")
        }

        when {
            lines.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                context.getString(R.string.self_check_rawfiles_hidden)
            )
            leaked -> CheckResult(
                CheckState.LEAKED,
                context.getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            )
            else -> CheckResult(
                CheckState.VISIBLE,
                context.getString(R.string.self_check_status_visible),
                lines.joinToString("\n")
            )
        }
    }
}
