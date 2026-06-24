package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InstalledAppsChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_apps_title
    override val navChipId: Int = R.id.navAppsChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val packageManager = context.packageManager
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val samples = apps
                .filterNot { it.packageName == context.packageName }
                .sortedWith(compareBy<ApplicationInfo> { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.thenBy { it.packageName })
                .take(8)
                .joinToString("\n") { app ->
                    val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                    "$label\n${app.packageName}"
                }

            CheckResult(
                if (apps.size <= 1) CheckState.BLOCKED else CheckState.VISIBLE,
                context.resources.getQuantityString(R.plurals.self_check_apps_status, apps.size, apps.size),
                if (samples.isBlank()) context.getString(R.string.self_check_apps_hidden) else samples
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
