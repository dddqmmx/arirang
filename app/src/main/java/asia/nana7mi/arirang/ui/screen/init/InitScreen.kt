package asia.nana7mi.arirang.ui.screen.init

import asia.nana7mi.arirang.ui.component.init.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.AppPreferences
import asia.nana7mi.arirang.ui.activity.MainActivity

// 数据模型：用于存储选项的显示名称 and 实际值

@Composable
internal fun InitScreen() {
    val context = LocalContext.current
    MaterialTheme(colorScheme = getAppColorScheme(context)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SetupFlow()
        }
    }
}

@Composable
private fun getAppColorScheme(context: Context): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
}

@Composable
private fun SetupFlow() {
    var step by rememberSaveable { mutableIntStateOf(1) }
    var selectedLanguageCode by rememberSaveable { mutableStateOf(getCurrentAppLanguageCode()) }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> -width } + fadeOut())
            } else {
                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> width } + fadeOut())
            }
        },
        label = "ScreenTransition"
    ) { targetStep ->
        when (targetStep) {
            1 -> LanguageScreen(
                onNext = { lang ->
                    selectedLanguageCode = lang
                    step = 2
                }
            )
            2 -> WarningScreen(
                languageCode = selectedLanguageCode,
                onBack = { step = 1 }
            )
        }
    }
}

// =======================
// 第一屏：语言选择
// =======================
@Composable
private fun LanguageScreen(onNext: (String) -> Unit) {
    val languageLabels = stringArrayResource(id = R.array.language_names)
    val languageCodes = stringArrayResource(id = R.array.language_codes)

    val languages = remember(languageLabels, languageCodes) {
        languageLabels.zip(languageCodes) { label, code -> OptionItem(label, code) }
    }

    var currentLanguage by remember { mutableStateOf(languages.find { it.value == getCurrentAppLanguageCode() } ?: languages.first()) }

    var showLangDialog by remember { mutableStateOf(false) }

    if (showLangDialog) {
        CommonSelectionDialog(
            title = stringResource(id = R.string.init_select_language),
            options = languages,
            selectedOption = currentLanguage,
            onDismiss = { showLangDialog = false },
            onOptionSelected = { selected ->
                currentLanguage = selected
                showLangDialog = false
                applyLanguage(selected.value)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(id = R.string.init_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(id = R.string.init_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            SelectionCard(
                icon = Icons.Default.Language,
                label = stringResource(id = R.string.init_label_language),
                value = currentLanguage.label,
                onClick = { showLangDialog = true }
            )
        }

        Button(
            onClick = { onNext(currentLanguage.value) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(stringResource(id = R.string.init_button_next), fontSize = 18.sp)
        }
    }
}


// =======================
// 通用选择卡片 (支持点击)
// =======================

// =======================
// 通用选择弹窗 (核心逻辑)
// =======================

// =======================
// 工具函数：语言切换逻辑
// =======================

private fun applyLanguage(code: String) {
    val localeList = if (code == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(code)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

private fun getCurrentAppLanguageCode(): String {
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    if (currentLocales.isEmpty) return "system"
    return currentLocales.toLanguageTags()
}

// =======================
// 第二屏
// =======================
@Composable
private fun WarningScreen(languageCode: String, onBack: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val scrollState = rememberScrollState()

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.padding(24.dp)) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        AppPreferences.setSetupCompleted(activity, true)
                        AppPreferences.setLanguage(activity, languageCode)
                        val intent = Intent(activity, MainActivity::class.java)
                        activity.startActivity(intent)
                        activity.finish()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(stringResource(id = R.string.init_button_enter), fontSize = 18.sp)
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(id = R.string.init_security_check_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(32.dp))

            // 核心依赖卡片
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.init_core_dependency_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(id = R.string.init_core_dependency_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 信任根基卡片
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(id = R.string.init_precondition_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(id = R.string.init_precondition_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(id = R.string.init_risk_cases_title), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    RiskItem(stringResource(id = R.string.init_risk_item_backdoor))
                    RiskItem(stringResource(id = R.string.init_risk_item_rom))
                    RiskItem(stringResource(id = R.string.init_risk_item_kernel))
                    RiskItem(stringResource(id = R.string.init_risk_item_root))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
