package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.media.MediaDrm
import android.util.Log
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DrmChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_drm_title
    override val navChipId: Int = R.id.navDrmChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var leaked = false

        CheckDefinitions.DRM_SCHEMES.forEach { scheme ->
            val supported = runCatching { MediaDrm.isCryptoSchemeSupported(scheme.uuid) }.getOrDefault(false)
            if (!supported) return@forEach
            runCatching {
                MediaDrm(scheme.uuid).use { drm ->
                    val deviceId = runCatching {
                        drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                            .joinToString("") { "%02x".format(it) }
                    }.getOrNull()?.takeUnless { it.isBlank() }
                    val vendor = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VENDOR) }.getOrNull()
                    val version = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VERSION) }.getOrNull()

                    lines.add(scheme.name)
                    vendor?.takeUnless { it.isBlank() }?.let { lines.add("  Vendor: $it") }
                    version?.takeUnless { it.isBlank() }?.let { lines.add("  Version: $it") }
                    if (deviceId != null) {
                        if (!scheme.spoofed) leaked = true
                        val marker = if (!scheme.spoofed) " ${context.getString(R.string.self_check_channel_leak)}" else ""
                        lines.add("  Device unique ID: ${deviceId.maskMiddle()}$marker")
                    }
                }
            }.onFailure {
                Log.e(CheckDefinitions.PHONE_DIAG_TAG, "readDrmInfo ${scheme.name} failed", it)
            }
        }

        when {
            lines.isEmpty() -> CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                context.getString(R.string.self_check_drm_hidden)
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
