package asia.nana7mi.arirang.ui.screen.clipboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlin.math.ceil
import asia.nana7mi.arirang.ui.activity.ConfirmDialogActivity
import asia.nana7mi.arirang.ui.component.clipboard.ConfirmDialogContent

@Composable
internal fun ConfirmDialogScreen(
    appName: String,
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

    ConfirmDialogContent(
        appName = appName,
        packageName = pkgName,
        progress = animatedProgress,
        secondsLeft = secondsLeft,
        onDismiss = { onResult(ConfirmDialogActivity.RESULT_DENY_ONCE) },
        onAllowOnce = { onResult(ConfirmDialogActivity.RESULT_ALLOW_ONCE) },
        onAllowAlways = { onResult(ConfirmDialogActivity.RESULT_ALLOW_ALWAYS) },
        onDenyAlways = { onResult(ConfirmDialogActivity.RESULT_DENY_ALWAYS) }
    )
}
