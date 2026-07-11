package asia.nana7mi.arirang.ui.component.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.AppPreferences
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.data.config.ConfigBackupManager
import asia.nana7mi.arirang.ui.component.dialog.HookLogDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(
    onNavigateToAdvanced: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val languageNames = stringArrayResource(R.array.language_names)
    val languageCodes = stringArrayResource(R.array.language_codes)
    val logModules = remember {
        HookLogSettings.MODULE_KEYS.map { key ->
            HookLogSettings.Module(key, logModuleLabelRes(key))
        }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Unable to open backup destination")
                        output.use { ConfigBackupManager.export(context, it) }
                    }
                }.onSuccess {
                    Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_failed_message, it.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Unable to open backup")
                        input.use { ConfigBackupManager.import(context, it) }
                    }
                }.onSuccess {
                    Toast.makeText(context, R.string.import_success_restart, Toast.LENGTH_LONG).show()
                    val component = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                        ?.component
                    if (component != null) {
                        context.startActivity(Intent.makeRestartActivityTask(component))
                        exitProcess(0)
                    }
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed_message, it.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val savedLanguage = AppPreferences.getLanguage(context) ?: "system"
    val currentLanguage = languageNames.getOrElse(languageCodes.indexOf(savedLanguage)) { savedLanguage }


    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

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
            SettingsSection(title = stringResource(R.string.backup_restore_title)) {
                SettingCard(
                    title = stringResource(R.string.export_config_title),
                    summary = stringResource(R.string.export_config_summary),
                    icon = Icons.Default.Save,
                    onClick = {
                        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        val fileName = "arirang_config_${dateFormat.format(Date())}.zip"
                        exportConfigLauncher.launch(fileName)
                    }
                )
                SettingCard(
                    title = stringResource(R.string.import_config_title),
                    summary = stringResource(R.string.import_config_summary),
                    icon = Icons.Default.Restore,
                    onClick = {
                        importConfigLauncher.launch(arrayOf("application/zip"))
                    }
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

    if (showLogDialog) {
        HookLogDialog(
            modules = logModules,
            onDismiss = { showLogDialog = false }
        )
    }
}

private fun applyLanguage(code: String) {
    val localeList = if (code == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(code)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

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
