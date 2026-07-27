package asia.nana7mi.arirang.ui.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import asia.nana7mi.arirang.ui.screen.clipboard.ConfirmDialogScreen
import asia.nana7mi.arirang.ui.theme.ArirangTheme

/**
 * The clipboard consent prompt.
 *
 * This activity is `singleInstance`, so concurrent requests are delivered to
 * the already-running instance via [onNewIntent] rather than by creating a new
 * one. That was previously not overridden, so `getIntent()` kept returning the
 * *first* request forever: every later request's ResultReceiver was discarded,
 * its caller's binder thread blocked for the full 10 s timeout before defaulting
 * to DENY, and ClipboardController's pending map filled with stale entries that
 * then blanket-denied all clipboard reads for another 15 s with no prompt shown.
 *
 * The current request is therefore held in state rather than read from the
 * intent at each use.
 */
class ConfirmDialogActivity : ComponentActivity() {
    companion object {
        const val RESULT_DENY_ONCE = 0
        const val RESULT_ALLOW_ONCE = 1
        const val RESULT_ALLOW_ALWAYS = 2
        const val RESULT_DENY_ALWAYS = 3

        private const val DEFAULT_TIMEOUT_MS = 10_000L
    }

    private data class PromptRequest(
        val pkgName: String = "Unknown",
        val appName: String = "Unknown",
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val receiver: ResultReceiver? = null
    )

    private var request by mutableStateOf(PromptRequest())

    @Volatile
    private var resultSent = false
    private var pendingResultCode: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )

        request = readRequest(intent)

        setContent {
            ArirangTheme {
                val current = request
                // Keyed so a request arriving via onNewIntent restarts the
                // countdown instead of inheriting the previous one's remaining
                // time.
                key(current.pkgName, current.receiver) {
                    ConfirmDialogScreen(
                        appName = current.appName,
                        pkgName = current.pkgName,
                        timeoutMs = current.timeoutMs,
                        onResult = { resultCode -> sendResult(resultCode) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // The prompt currently on screen belongs to an earlier request whose
        // caller is still blocked waiting. Answer it rather than dropping it;
        // deny, since the user did not actually make a choice.
        deliverTo(request.receiver, RESULT_DENY_ONCE)

        resultSent = false
        pendingResultCode = null
        request = readRequest(intent)
    }

    private fun readRequest(intent: Intent?): PromptRequest {
        val pkgName = intent?.getStringExtra("pkg_name") ?: "Unknown"
        return PromptRequest(
            pkgName = pkgName,
            appName = resolveAppName(pkgName),
            timeoutMs = intent?.getLongExtra("timeout_ms", DEFAULT_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS,
            receiver = intent?.resultReceiver()
        )
    }

    private fun Intent.resultReceiver(): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra("receiver", ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra("receiver")
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
            sendResult(RESULT_DENY_ONCE)
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Records the user's choice; it is delivered once the window is dismissed. */
    private fun sendResult(resultCode: Int) {
        if (resultSent) return
        pendingResultCode = resultCode
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            pendingResultCode?.let { sendActualResult(it) }
        }
    }

    private fun sendActualResult(resultCode: Int) {
        if (resultSent) return
        resultSent = true
        deliverTo(request.receiver, resultCode)
    }

    private fun deliverTo(receiver: ResultReceiver?, resultCode: Int) {
        runCatching { receiver?.send(resultCode, null) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!resultSent) {
            sendActualResult(pendingResultCode ?: RESULT_DENY_ONCE)
        }
    }
}
