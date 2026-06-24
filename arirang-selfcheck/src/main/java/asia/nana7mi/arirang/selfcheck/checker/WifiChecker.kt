package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WifiChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_wifi_title
    override val navChipId: Int = R.id.navWifiChip

    @SuppressLint("MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_wifi_permission_hint)
            )
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val currentNetwork = listOfNotNull(
                connectionInfo?.ssid?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }?.let {
                    context.getString(R.string.self_check_wifi_current, it)
                }
            )

            val scans = wifiManager.scanResults.orEmpty()
            val scanSamples = scans.take(8).joinToString("\n") { result ->
                val ssid = result.SSID.ifBlank { context.getString(R.string.self_check_unknown_name) }
                "$ssid\n${result.BSSID}"
            }
            val content = (currentNetwork + scanSamples).joinToString("\n")

            CheckResult(
                if (content.isBlank()) CheckState.BLOCKED else CheckState.VISIBLE,
                context.resources.getQuantityString(R.plurals.self_check_wifi_status, scans.size, scans.size),
                if (content.isBlank()) context.getString(R.string.self_check_wifi_hidden) else content
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
