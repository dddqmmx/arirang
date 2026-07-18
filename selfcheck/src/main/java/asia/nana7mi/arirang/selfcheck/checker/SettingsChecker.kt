package asia.nana7mi.arirang.selfcheck.checker

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.visibleListResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_settings_title
    override val navChipId: Int = R.id.navSettingsChip

    @SuppressLint("HardwareIds")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()

        CheckDefinitions.SETTINGS_GLOBAL_KEYS.forEach { key ->
            runCatching { Settings.Global.getString(context.contentResolver, key) }
                .getOrNull()?.takeUnless { it.isBlank() }
                ?.let { lines.add("Global $key: $it") }
        }

        CheckDefinitions.SETTINGS_SECURE_KEYS.forEach { key ->
            runCatching { Settings.Secure.getString(context.contentResolver, key) }
                .getOrNull()?.takeUnless { it.isBlank() }
                ?.let { value ->
                    val display = if (key == "android_id") value.maskMiddle() else value
                    lines.add("Secure $key: $display")
                }
        }

        visibleListResult(
            lines,
            context.getString(R.string.self_check_status_visible),
            context.getString(R.string.self_check_settings_hidden),
            context
        )
    }
}
