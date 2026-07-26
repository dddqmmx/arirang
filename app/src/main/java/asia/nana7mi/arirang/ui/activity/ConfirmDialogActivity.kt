package asia.nana7mi.arirang.ui.activity

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import asia.nana7mi.arirang.ui.screen.clipboard.ConfirmDialogScreen
import asia.nana7mi.arirang.ui.component.clipboard.DynamicArirangTheme

class ConfirmDialogActivity : ComponentActivity() {
    companion object {
        const val RESULT_DENY_ONCE = 0
        const val RESULT_ALLOW_ONCE = 1
        const val RESULT_ALLOW_ALWAYS = 2
        const val RESULT_DENY_ALWAYS = 3
    }

    @Volatile
    private var resultSent = false
    private var pendingResultCode: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )

        val pkgName = intent.getStringExtra("pkg_name") ?: "Unknown"
        val appName = resolveAppName(pkgName)
        val timeoutMs = intent.getLongExtra("timeout_ms", 10000L)
        val receiver = getResultReceiver()

        setContent {
            DynamicArirangTheme {
                ConfirmDialogScreen(
                    appName = appName,
                    pkgName = pkgName,
                    timeoutMs = timeoutMs,
                    onResult = { resultCode ->
                        sendResult(receiver, resultCode)
                    }
                )
            }
        }
    }

    private fun getResultReceiver(): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("receiver", ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("receiver")
        }
    }

    private fun resolveAppName(pkgName: String): String {
        if (pkgName.isBlank() || pkgName == "Unknown") {
            return pkgName
        }

        return runCatching {
            val applicationInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                    pkgName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkgName, 0)
            }
            packageManager.getApplicationLabel(applicationInfo).toString().ifBlank { pkgName }
        }.getOrDefault(pkgName)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_OUTSIDE) {
            sendResult(getResultReceiver(), RESULT_DENY_ONCE)
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun sendResult(receiver: ResultReceiver?, resultCode: Int) {
        if (resultSent) return
        pendingResultCode = resultCode
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            pendingResultCode?.let {
                sendActualResult(it)
            }
        }
    }

    private fun sendActualResult(resultCode: Int) {
        if (resultSent) return
        resultSent = true
        getResultReceiver()?.send(resultCode, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!resultSent) {
            sendActualResult(pendingResultCode ?: RESULT_DENY_ONCE)
        }
    }
}
