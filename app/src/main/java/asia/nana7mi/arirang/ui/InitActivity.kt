package asia.nana7mi.arirang.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

// 数据模型：用于存储选项的显示名称 and 实际值
data class OptionItem(val label: String, val value: String)

class InitActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = getAppColorScheme(this)
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupFlow()
                }
            }
        }
    }
}

@Composable
fun getAppColorScheme(context: Context): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
}

@Composable
fun SetupFlow() {
    var step by rememberSaveable { mutableIntStateOf(1) }
    var selectedLanguageCode by rememberSaveable { mutableStateOf(getCurrentAppLanguageCode()) }
    var selectedRegionCode by rememberSaveable { mutableStateOf("CN") }

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
            1 -> LanguageRegionScreen(
                onNext = { lang, region ->
                    selectedLanguageCode = lang
                    selectedRegionCode = region
                    step = 2
                }
            )
            2 -> WarningScreen(
                languageCode = selectedLanguageCode,
                regionCode = selectedRegionCode,
                onBack = { step = 1 }
            )
        }
    }
}

// =======================
// 第一屏：语言与地区 (逻辑增强版)
// =======================
@Composable
fun LanguageRegionScreen(onNext: (String, String) -> Unit) {
    val context = LocalContext.current
    // --- 读取 XML 资源 ---
    val languageLabels = stringArrayResource(id = R.array.language_names)
    val languageCodes = stringArrayResource(id = R.array.language_codes)
    val regionLabels = stringArrayResource(id = R.array.region_names)
    val regionCodes = stringArrayResource(id = R.array.region_codes)

    // 组合成对象列表
    val languages = remember(languageLabels, languageCodes) {
        languageLabels.zip(languageCodes) { label, code -> OptionItem(label, code) }
    }
    val regions = remember(regionLabels, regionCodes) {
        regionLabels.zip(regionCodes) { label, code -> OptionItem(label, code) }
    }

    // --- 状态管理 ---
    var currentLanguage by remember { mutableStateOf(languages.find { it.value == getCurrentAppLanguageCode() } ?: languages.first()) }
    var currentRegionCode by rememberSaveable { mutableStateOf(regions.first().value) }
    val currentRegion = regions.find { it.value == currentRegionCode } ?: regions.first()
    
    val isExtreme = currentRegion.value == "KP"

    var showLangDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }

    // --- 弹窗组件调用 ---
    if (showLangDialog) {
        CommonSelectionDialog(
            title = stringResource(id = R.string.init_select_language),
            options = languages,
            selectedOption = currentLanguage,
            onDismiss = { showLangDialog = false },
            onOptionSelected = { selected ->
                currentLanguage = selected
                showLangDialog = false
                // 执行真正的语言切换
                applyLanguage( selected.value)
            }
        )
    }

    if (showRegionDialog) {
        CommonSelectionDialog(
            title = stringResource(id = R.string.init_select_region),
            options = regions,
            selectedOption = currentRegion,
            onDismiss = { showRegionDialog = false },
            onOptionSelected = { selected ->
                currentRegionCode = selected.value
                showRegionDialog = false
            }
        )
    }

    // --- UI 布局 ---
    Box(modifier = Modifier.fillMaxSize()) {

        // 🔴 呼吸红色背景
        if (isExtreme) {
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.04f,
                targetValue = 0.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAnim"
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Color.Red.copy(alpha = pulse)
                    )
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
                icon = Icons.Default.Public,
                label = stringResource(id = R.string.init_label_language),
                value = currentLanguage.label, // 显示动态选中的值
                onClick = { showLangDialog = true }
            )
            SelectionCard(
                icon = Icons.Default.Public,
                label = stringResource(id = R.string.init_label_region),
                value = currentRegion.label, // 显示动态选中的值
                onClick = { showRegionDialog = true }
            )
            AnimatedVisibility(
                visible = isExtreme,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = stringResource(id = R.string.init_extreme_warning),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Button(
            onClick = { onNext(currentLanguage.value, currentRegion.value) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(stringResource(id = R.string.init_button_next), fontSize = 18.sp)
        }
    }
}}


// =======================
// 通用选择卡片 (支持点击)
// =======================
@Composable
fun SelectionCard(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null
            )
        }
    }
}

// =======================
// 通用选择弹窗 (核心逻辑)
// =======================
@Composable
fun CommonSelectionDialog(
    title: String,
    options: List<OptionItem>,
    selectedOption: OptionItem?,
    onDismiss: () -> Unit,
    onOptionSelected: (OptionItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
            ) {
                items(options) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(item) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (item == selectedOption),
                            onClick = { onOptionSelected(item) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.btn_cancel))
            }
        }
    )
}

// =======================
// 工具函数：语言切换逻辑
// =======================

fun applyLanguage(code: String) {
    val localeList = if (code == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(code)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

fun getCurrentAppLanguageCode(): String {
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    if (currentLocales.isEmpty) return "system"
    return currentLocales.toLanguageTags()
}

// =======================
// 第二屏
// =======================
@Composable
fun WarningScreen(languageCode: String, regionCode: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.padding(24.dp)) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        AppPreferences.setSetupCompleted(context, true)
                        AppPreferences.setRegion(context, regionCode)
                        AppPreferences.setLanguage(context, languageCode)
                        val intent = Intent(context, MainActivity::class.java)
                        context.startActivity(intent)
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

@Composable
fun RiskItem(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
