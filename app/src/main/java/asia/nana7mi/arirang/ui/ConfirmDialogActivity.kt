package asia.nana7mi.arirang.ui

import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asia.nana7mi.arirang.R
import kotlinx.coroutines.delay
import kotlin.math.ceil

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
        val timeoutMs = intent.getLongExtra("timeout_ms", 2500L)
        val receiver = getResultReceiver()

        setContent {
            DynamicArirangTheme {
                ConfirmDialogScreen(
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

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_OUTSIDE) {
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

@Composable
fun DynamicArirangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

@Composable
fun ConfirmDialogScreen(
    pkgName: String,
    timeoutMs: Long,
    onResult: (Int) -> Unit
) {
    var isStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isStarted = true
        delay(timeoutMs)
        onResult(ConfirmDialogActivity.RESULT_DENY_ONCE)
    }

    BackHandler {
        onResult(ConfirmDialogActivity.RESULT_DENY_ONCE)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (isStarted) 0f else 1f,
        animationSpec = tween(durationMillis = timeoutMs.toInt(), easing = LinearEasing),
        label = "progress"
    )

    val secondsLeft = ceil((animatedProgress * timeoutMs) / 1000.0).toInt().coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onResult(ConfirmDialogActivity.RESULT_DENY_ONCE) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 320.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: Integrated Icon/Progress + Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(42.dp)) {
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxSize(),
                            color = if (animatedProgress > 0.3f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            strokeWidth = 3.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(id = R.string.clipboard_access_warning),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // App Name + Desc Block
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pkgName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(id = R.string.clipboard_access_desc).replace(pkgName, "").replace("[Unknown]", "").trim().removeSuffix(":").removeSuffix("："),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }

                // Primary Action Stack
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onResult(ConfirmDialogActivity.RESULT_ALLOW_ONCE) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.allow_once),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    FilledTonalButton(
                        onClick = { onResult(ConfirmDialogActivity.RESULT_DENY_ONCE) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(
                            text = "${stringResource(id = R.string.this_deny_close)} (${secondsLeft}s)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Bottom Secondary Actions: Clean centered grouping
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onResult(ConfirmDialogActivity.RESULT_ALLOW_ALWAYS) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.always_allow),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    TextButton(
                        onClick = { onResult(ConfirmDialogActivity.RESULT_DENY_ALWAYS) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.always_deny),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}
