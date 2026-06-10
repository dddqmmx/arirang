package asia.nana7mi.arirang.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.AppPreferences
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.ui.activity.AdvancedSettingsActivity
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArirangTheme {
                    SettingsScreen(
                        onNavigateToAdvanced = {
                            startActivity(Intent(requireContext(), AdvancedSettingsActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onNavigateToAdvanced: () -> Unit
) {
    val context = LocalContext.current
    val languageNames = stringArrayResource(R.array.language_names)
    val languageCodes = stringArrayResource(R.array.language_codes)
    val regionNames = stringArrayResource(R.array.region_names)
    val regionCodes = stringArrayResource(R.array.region_codes)
    val logModules = remember {
        HookLogSettings.MODULE_KEYS.map { key ->
            HookLogSettings.Module(key, logModuleLabelRes(key))
        }
    }

    val savedLanguage = AppPreferences.getLanguage(context) ?: "system"
    val currentLanguage = languageNames.getOrElse(languageCodes.indexOf(savedLanguage)) { savedLanguage }


    var currentRegionCode by remember {
        mutableStateOf(AppPreferences.getRegion(context) ?: DEFAULT_REGION)
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    val currentRegion = regionNames.getOrElse(regionCodes.indexOf(currentRegionCode)) { currentRegionCode }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            SettingsSection(title = stringResource(R.string.global_settings_title)) {
                SettingCard(
                    title = stringResource(R.string.advanced_settings_title),
                    summary = stringResource(R.string.advanced_settings_summary),
                    icon = Icons.Default.Settings,
                    onClick = onNavigateToAdvanced
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.ui_settings_title)) {
                SettingCard(
                    title = stringResource(R.string.title_activity_language_settings),
                    summary = currentLanguage,
                    icon = Icons.Default.Language,
                    onClick = { showLanguageDialog = true }
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.init_select_region)) {
                SettingCard(
                    title = stringResource(R.string.user_region_title),
                    summary = currentRegion,
                    icon = Icons.Default.Public,
                    onClick = { showRegionDialog = true }
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.log_settings_title)) {
                SettingCard(
                    title = stringResource(R.string.log_settings_title),
                    summary = stringResource(R.string.log_settings_summary),
                    icon = Icons.Default.Terminal,
                    onClick = { showLogDialog = true }
                )
            }
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            languageNames = languageNames,
            languageCodes = languageCodes,
            selectedCode = savedLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelect = { code ->
                AppPreferences.setLanguage(context, code)
                applyLanguage(code)
                showLanguageDialog = false
            }
        )
    }

    if (showRegionDialog) {
        RegionDialog(
            regionNames = regionNames,
            regionCodes = regionCodes,
            selectedCode = currentRegionCode,
            onDismiss = { showRegionDialog = false },
            onSelect = { code ->
                AppPreferences.setRegion(context, code)
                currentRegionCode = code
                showRegionDialog = false
                Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showLogDialog) {
        HookLogDialog(
            modules = logModules,
            onDismiss = { showLogDialog = false }
        )
    }
}

@Composable
private fun LanguageDialog(
    languageNames: Array<String>,
    languageCodes: Array<String>,
    selectedCode: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.title_language)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                languageNames.zip(languageCodes).forEach { (name, code) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = code == selectedCode,
                            onClick = { onSelect(code) }
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun HookLogDialog(
    modules: List<HookLogSettings.Module>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.log_settings_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                modules.forEach { module ->
                    var enabled by remember(module.key) {
                        mutableStateOf(HookLogSettings.isEnabled(context, module.key))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !enabled
                                if (HookLogSettings.setEnabled(context, module.key, next)) {
                                    enabled = next
                                } else {
                                    Toast.makeText(context, R.string.log_settings_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(module.labelRes),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = module.key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                if (HookLogSettings.setEnabled(context, module.key, checked)) {
                                    enabled = checked
                                } else {
                                    Toast.makeText(context, R.string.log_settings_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RegionDialog(
    regionNames: Array<String>,
    regionCodes: Array<String>,
    selectedCode: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.init_select_region)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                regionNames.zip(regionCodes).forEach { (name, code) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = code == selectedCode,
                            onClick = { onSelect(code) }
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}

private fun applyLanguage(code: String) {
    val localeList = if (code == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(code)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

private const val DEFAULT_REGION = "JP"

private fun logModuleLabelRes(key: String): Int {
    return when (key) {
        "core" -> R.string.log_module_core
        "clipboard" -> R.string.log_module_clipboard
        "gms" -> R.string.log_module_gms
        "location" -> R.string.log_module_location
        "package_list" -> R.string.log_module_package_list
        "settings" -> R.string.log_module_settings
        "sim" -> R.string.log_module_sim
        "wifi" -> R.string.log_module_wifi
        "bluetooth" -> R.string.log_module_bluetooth
        "unique_id" -> R.string.log_module_unique_id
        "notify" -> R.string.log_module_notify
        else -> R.string.log_settings_title
    }
}
