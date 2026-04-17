package asia.nana7mi.arirang.ui

import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPasteSearch
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R

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
        val receiver = getResultReceiver()

        setContent {
            // 使用支持动态取色的主题
            DynamicArirangTheme {
                ConfirmDialogScreen(
                    pkgName = pkgName,
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

/**
 * 动态取色主题 (Material You)
 */
@Composable
fun DynamicArirangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Android 12 (API 31) 及以上使用系统壁纸动态取色，否则使用默认的 M3 颜色
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
    onResult: (Int) -> Unit
) {
    BackHandler {
        onResult(ConfirmDialogActivity.RESULT_DENY_ONCE)
    }

    // 外层容器，不再有黑色 background，完全透明，仅负责拦截点击事件
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
        // 对话框卡片主体
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .clickable( // 拦截内部点击，防止误触发关闭
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(28.dp),
            // 使用主题色中的 Surface 变体，并带一点高度投影，视觉更立体
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部动态色的 Icon
                Icon(
                    imageVector = Icons.Default.ContentPasteSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.clipboard_access_warning),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(id = R.string.clipboard_access_desc).replace("[Unknown]", pkgName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 改为 2x2 网格排版，更加美观紧凑
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 第一排：允许操作
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onResult(ConfirmDialogActivity.RESULT_ALLOW_ALWAYS) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(id = R.string.always_allow))
                        }

                        FilledTonalButton(
                            onClick = { onResult(ConfirmDialogActivity.RESULT_ALLOW_ONCE) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(id = R.string.allow_once))
                        }
                    }

                    // 第二排：拒绝操作
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onResult(ConfirmDialogActivity.RESULT_DENY_ALWAYS) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(id = R.string.always_deny))
                        }

                        OutlinedButton(
                            onClick = { onResult(ConfirmDialogActivity.RESULT_DENY_ONCE) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(id = R.string.this_deny_close))
                        }
                    }
                }
            }
        }
    }
}