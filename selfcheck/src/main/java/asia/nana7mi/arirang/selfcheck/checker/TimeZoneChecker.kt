package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.util.Log
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verifies that every in-process default-time-zone channel agrees.
 *
 * The per-app time zone spoof (submodule/doc/timezone_per_app_research.md)
 * works by giving a spoofed package a private copy-on-write view of the
 * `persist.sys.timezone` property, so `TimeZone.getDefault()`,
 * `ZoneId.systemDefault()`, the ICU default and direct SystemProperties reads
 * must all report the same zone inside this process. When the package is NOT
 * spoofed they must all report the real zone. Either way, a mismatch between
 * channels is the observable leak this check surfaces.
 */
class TimeZoneChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_timezone_title
    override val navChipId: Int = R.id.navTimeZoneChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var leaked = false

        val javaDefault = runCatching { java.util.TimeZone.getDefault().id }.getOrNull()
        lines.add(context.getString(R.string.self_check_timezone_java, javaDefault ?: "-"))

        val zoneIdDefault = runCatching { java.time.ZoneId.systemDefault().id }.getOrNull()
        lines.add(context.getString(R.string.self_check_timezone_zoneid, zoneIdDefault ?: "-"))

        val icuDefault = runCatching { android.icu.util.TimeZone.getDefault().id }.getOrNull()
        lines.add(context.getString(R.string.self_check_timezone_icu, icuDefault ?: "-"))

        val propValue = CheckUtils.readSystemProperty("persist.sys.timezone")
        lines.add(context.getString(R.string.self_check_timezone_prop, propValue ?: "-"))

        val formatDefault = runCatching {
            SimpleDateFormat.getDateTimeInstance().timeZone.id
        }.getOrNull()
        lines.add(context.getString(R.string.self_check_timezone_formatter, formatDefault ?: "-"))

        // §12.2/H: after TimeZone.setDefault(null) the next getDefault() must
        // still return the same zone (the property view is pinned in a spoofed
        // process; the real property in an unspoofed one). Any divergence is a
        // cache-vs-property leak.
        val reRead = runCatching {
            java.util.TimeZone.setDefault(null)
            java.util.TimeZone.getDefault().id
        }.getOrNull()
        lines.add(context.getString(R.string.self_check_timezone_reread, reRead ?: "-"))

        val channels = listOfNotNull(
            javaDefault, zoneIdDefault, icuDefault, propValue, formatDefault, reRead
        )
        if (channels.isNotEmpty() && channels.distinct().size > 1) {
            leaked = true
        }
        val mismatchMarker = context.getString(R.string.self_check_channel_mismatch)
        val expected = channels.firstOrNull() ?: ""
        listOf(
            "java" to javaDefault,
            "zoneId" to zoneIdDefault,
            "icu" to icuDefault,
            "prop" to propValue,
            "formatter" to formatDefault,
            "reread" to reRead
        ).forEach { (label, value) ->
            if (value != null && expected.isNotEmpty() && value != expected) {
                lines.add(context.getString(R.string.self_check_timezone_mismatch, label, value, mismatchMarker))
            }
        }

        when {
            channels.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                context.getString(R.string.self_check_timezone_hidden)
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "Time Zone CheckResult: \n${it.content}") }
            leaked -> CheckResult(
                CheckState.LEAKED,
                context.getString(R.string.self_check_status_leaked),
                lines.joinToString("\n")
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "Time Zone CheckResult: \n${it.content}") }
            else -> CheckResult(
                CheckState.VISIBLE,
                context.getString(R.string.self_check_status_consistent),
                lines.joinToString("\n")
            ).also { Log.i(CheckDefinitions.PHONE_DIAG_TAG, "Time Zone CheckResult: \n${it.content}") }
        }
    }
}