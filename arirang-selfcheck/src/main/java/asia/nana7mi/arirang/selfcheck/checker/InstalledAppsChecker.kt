package asia.nana7mi.arirang.selfcheck.checker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class InstalledAppsChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_apps_title
    override val navChipId: Int = R.id.navAppsChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val selfPkg = context.packageName

            val results = linkedMapOf<String, EnumResult>()

            // 1. getInstalledApplications - baseline
            results["getInstalledApplications"] = runCatching {
                val pkgs = pm.getInstalledApplications(0)
                    .map { it.packageName }
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 2. getInstalledPackages
            results["getInstalledPackages"] = runCatching {
                val pkgs = pm.getInstalledPackages(0)
                    .map { it.packageName }
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 3. queryIntentActivities - LAUNCHER
            results["queryIntent(MAIN/LAUNCHER)"] = runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val pkgs = pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 4. queryIntentActivities - URL handlers (http)
            results["queryIntent(VIEW/http)"] = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
                val pkgs = pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 5. queryIntentActivities - URL handlers (https)
            results["queryIntent(VIEW/https)"] = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                val pkgs = pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 6. queryIntentActivities - SEND (share targets)
            results["queryIntent(SEND/text)"] = runCatching {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                val pkgs = pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 7. queryContentProviders
            results["queryContentProviders"] = runCatching {
                val pkgs = pm.queryContentProviders(null, 0, 0)
                    .map { it.packageName }
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 8. getPackagesForUid - UID scan (THE ATTACK SURFACE)
            results["getPackagesForUid ⭐"] = runCatching {
                val found = mutableSetOf<String>()
                // System UIDs: 0-2000
                for (uid in 0..2000) {
                    pm.getPackagesForUid(uid)?.forEach { pkg ->
                        if (pkg != selfPkg) found.add(pkg)
                    }
                }
                // App UIDs: 10000-50000
                for (uid in 10000..50000) {
                    pm.getPackagesForUid(uid)?.forEach { pkg ->
                        if (pkg != selfPkg) found.add(pkg)
                    }
                }
                EnumResult(found.sorted())
            }.getOrDefault(EnumResult.failed())

            // 9. getNameForUid - UID to package name mapping
            results["getNameForUid"] = runCatching {
                val found = mutableSetOf<String>()
                for (uid in 0..2000) {
                    pm.getNameForUid(uid)?.let { name ->
                        if (name.isNotEmpty() && name != selfPkg && name !in found) found.add(name)
                    }
                }
                for (uid in 10000..50000) {
                    pm.getNameForUid(uid)?.let { name ->
                        if (name.isNotEmpty() && name != selfPkg && name !in found) found.add(name)
                    }
                }
                EnumResult(found.sorted())
            }.getOrDefault(EnumResult.failed())

            // 10. getPreferredActivities
            results["getPreferredActivities"] = runCatching {
                val outFilters = mutableListOf<IntentFilter>()
                val outActivities = mutableListOf<ComponentName>()
                pm.getPreferredActivities(outFilters, outActivities, null)
                val pkgs = outActivities.map { it.packageName }
                    .distinct()
                    .filter { it != selfPkg }
                    .sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // 11. pm list packages via shell
            results["pm list packages (shell)"] = runCatching {
                val process = ProcessBuilder("/system/bin/sh", "-c", "pm list packages 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val lines = reader.readLines()
                process.waitFor(3, TimeUnit.SECONDS)
                val pkgs = lines.mapNotNull { line ->
                    line.removePrefix("package:").trim().takeIf { it.isNotEmpty() && it != selfPkg }
                }.sorted()
                EnumResult(pkgs)
            }.getOrDefault(EnumResult.failed())

            // ---- ANALYSIS ----
            val baselinePkgs = results["getInstalledApplications"]?.pkgs.orEmpty()
            val baselineCount = baselinePkgs.size

            val allPkgs = results.values.flatMap { it.pkgs }.distinct().sorted()
            val allCount = allPkgs.size

            val maxCount = results.values.maxOfOrNull { it.pkgs.size } ?: 0
            val bestMethods = results.filter { it.value.pkgs.size == maxCount }.keys

            // Detect if getPackagesForUid finds anything extra vs baseline
            val uidExtra = (results["getPackagesForUid ⭐"]?.pkgs.orEmpty())
                .filter { it !in baselinePkgs }

            // Detect cross-method discrepancies:
            // If any method returns packages that the baseline doesn't have, that's a leak
            val leakMethods = mutableListOf<String>()
            for ((name, result) in results) {
                val extras = result.pkgs.filter { it !in baselinePkgs }
                if (extras.isNotEmpty() && name != "getInstalledApplications") {
                    leakMethods.add("$name: +${extras.size} extra")
                }
            }
            // Also check if baseline is missing compared to any other method
            val baselineDeficit = baselinePkgs.any { pkg ->
                results.values.any { pkg !in it.pkgs }
            }
            val hasDiscrepancy = leakMethods.isNotEmpty()

            // ---- BUILD CONTENT ----
            val content = buildString {
                appendLine("=== Methods ===")
                for ((name, result) in results) {
                    val status = if (result.failed) "FAILED" else "${result.pkgs.size}"
                    appendLine("$name -> $status")
                }
                if (uidExtra.isNotEmpty()) {
                    appendLine()
                    appendLine("--- getPackagesForUid ⭐ Attack Surface ---")
                    appendLine("Found ${uidExtra.size} packages NOT returned by getInstalledApplications:")
                    uidExtra.take(30).forEach { appendLine(it) }
                    if (uidExtra.size > 30) appendLine("... and ${uidExtra.size - 30} more")
                }
                if (hasDiscrepancy) {
                    appendLine()
                    appendLine("--- Cross-Method Discrepancies ---")
                    leakMethods.forEach { appendLine(it) }
                }
                appendLine()
                appendLine("--- All Methods ---")
                appendLine("Baseline (getInstalledApplications): $baselineCount packages")
                appendLine("Best methods: ${bestMethods.joinToString(", ")} ($maxCount)")
                appendLine("Union (all methods, deduped): $allCount packages")
                if (baselinePkgs.isNotEmpty()) {
                    appendLine()
                    appendLine("--- Samples from Baseline ---")
                    baselinePkgs.take(8).forEach { appendLine(it) }
                    if (baselinePkgs.size > 8) appendLine("... and ${baselinePkgs.size - 8} more")
                }
            }

            val state = when {
                results.values.all { it.pkgs.isEmpty() } -> CheckState.BLOCKED
                hasDiscrepancy || uidExtra.isNotEmpty() -> CheckState.LEAKED
                else -> CheckState.VISIBLE
            }

            val status = buildString {
                append(maxCount)
                append(" packages | ")
                append(results.size)
                append(" methods")
                if (hasDiscrepancy || uidExtra.isNotEmpty()) {
                    append(" | ⚠ LEAK")
                }
            }

            CheckResult(state, status, content)
        } catch (e: Exception) {
            CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                e.readableMessage()
            )
        }
    }

    private data class EnumResult(
        val pkgs: List<String>,
        val failed: Boolean = false
    ) {
        companion object {
            fun failed() = EnumResult(emptyList(), failed = true)
        }
    }
}
