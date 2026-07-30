package asia.nana7mi.arirang.ui.screen.systemsetting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.AppSearchField
import asia.nana7mi.arirang.ui.component.common.AppTypeFilter
import asia.nana7mi.arirang.ui.component.common.AppTypeFilterChips
import asia.nana7mi.arirang.ui.component.common.ConfigScreenScaffold
import asia.nana7mi.arirang.ui.component.common.ConfigSectionCard
import asia.nana7mi.arirang.ui.component.common.EmptyState
import asia.nana7mi.arirang.ui.component.common.SearchablePickerDialog
import asia.nana7mi.arirang.ui.component.common.ToggleSettingRow
import asia.nana7mi.arirang.ui.component.common.loadInstalledApps
import asia.nana7mi.arirang.ui.component.common.matching
import asia.nana7mi.arirang.ui.component.systemsetting.AppOverrideDialog
import asia.nana7mi.arirang.ui.component.systemsetting.AppOverrideRow
import asia.nana7mi.arirang.ui.component.systemsetting.ValueRow
import asia.nana7mi.arirang.ui.component.systemsetting.languagePickerItems
import asia.nana7mi.arirang.ui.component.systemsetting.timeZonePickerItems

private enum class GlobalPicker { TIME_ZONE, LANGUAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemSettingConfigScreen(
    initialConfig: SystemSettingPrefs.Config,
    onBack: () -> Unit,
    onSave: (SystemSettingPrefs.Config) -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(initialConfig) }
    var savedConfig by remember { mutableStateOf(initialConfig) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(AppTypeFilter.USER) }
    var globalPicker by remember { mutableStateOf<GlobalPicker?>(null) }
    var editingApp by remember { mutableStateOf<AppEntry?>(null) }

    LaunchedEffect(Unit) { apps = loadInstalledApps(context) }

    val unchangedLabel = stringResource(R.string.system_setting_unchanged)
    val followDefaultLabel = stringResource(R.string.system_setting_follow_default)

    // Several hundred time zones and more locales; built once, not per frame.
    val globalTimeZoneItems = remember(unchangedLabel) { timeZonePickerItems(unchangedLabel) }
    val globalLanguageItems = remember(unchangedLabel) { languagePickerItems(unchangedLabel) }
    val overrideTimeZoneItems = remember(followDefaultLabel) { timeZonePickerItems(followDefaultLabel) }
    val overrideLanguageItems = remember(followDefaultLabel) { languagePickerItems(followDefaultLabel) }

    val visibleApps = apps
        .matching(typeFilter, query)
        .sortedWith(
            compareByDescending<AppEntry> { it.packageName in config.perPackage }
                .thenBy { it.label.lowercase() }
        )

    fun saveCurrent(): Boolean {
        onSave(config)
        savedConfig = config
        return true
    }

    ConfigScreenScaffold(
        title = stringResource(R.string.feature_system_setting),
        hasChanges = config != savedConfig,
        onSave = { saveCurrent() },
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item(key = "enable") {
                ConfigSectionCard(title = stringResource(R.string.system_setting_section_enable)) {
                    ToggleSettingRow(
                        title = stringResource(R.string.system_setting_enable),
                        summary = stringResource(R.string.system_setting_enable_summary),
                        checked = config.enabled,
                        onCheckedChange = { config = config.copy(enabled = it) }
                    )
                }
            }

            item(key = "defaults") {
                ConfigSectionCard(title = stringResource(R.string.system_setting_section_default)) {
                    Text(
                        text = stringResource(R.string.system_setting_section_default_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ValueRow(
                        label = stringResource(R.string.system_setting_time_zone),
                        value = config.timeZoneId.ifEmpty { unchangedLabel },
                        onClick = { globalPicker = GlobalPicker.TIME_ZONE }
                    )
                    ValueRow(
                        label = stringResource(R.string.system_setting_language),
                        value = config.languageTag.ifEmpty { unchangedLabel },
                        onClick = { globalPicker = GlobalPicker.LANGUAGE }
                    )
                }
            }

            item(key = "search") {
                AppSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.hint_package_or_app_name)
                )
            }

            item(key = "filter") {
                AppTypeFilterChips(selected = typeFilter, onSelect = { typeFilter = it })
            }

            if (apps.isNotEmpty() && visibleApps.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Default.Apps,
                        title = stringResource(R.string.app_list_empty)
                    )
                }
            }

            items(
                count = visibleApps.size,
                key = { index -> visibleApps[index].packageName }
            ) { index ->
                val app = visibleApps[index]
                AppOverrideRow(
                    app = app,
                    summary = overrideSummary(config, app.packageName),
                    isOverridden = app.packageName in config.perPackage,
                    onClick = { editingApp = app }
                )
            }
        }
    }

    when (globalPicker) {
        GlobalPicker.TIME_ZONE -> SearchablePickerDialog(
            title = stringResource(R.string.system_setting_time_zone),
            items = globalTimeZoneItems,
            selectedId = config.timeZoneId,
            searchPlaceholder = stringResource(R.string.system_setting_search_time_zone),
            onDismiss = { globalPicker = null },
            onSelect = {
                config = config.copy(timeZoneId = it)
                globalPicker = null
            }
        )

        GlobalPicker.LANGUAGE -> SearchablePickerDialog(
            title = stringResource(R.string.system_setting_language),
            items = globalLanguageItems,
            selectedId = config.languageTag,
            searchPlaceholder = stringResource(R.string.system_setting_search_language),
            onDismiss = { globalPicker = null },
            onSelect = {
                config = config.copy(languageTag = it)
                globalPicker = null
            }
        )

        null -> Unit
    }

    editingApp?.let { app ->
        val existing = config.perPackage[app.packageName]
        AppOverrideDialog(
            app = app,
            override = existing,
            timeZoneItems = overrideTimeZoneItems,
            languageItems = overrideLanguageItems,
            onDismiss = { editingApp = null },
            onSave = { override ->
                editingApp = null
                // An override that changes nothing is stored anyway when it is
                // disabled — "this app is exempt" is itself a decision — but an
                // enabled, all-default override is dropped so it does not sit in
                // the config looking meaningful.
                val isNoOp = override.enabled &&
                    override.timeZoneId.isEmpty() &&
                    override.languageTag.isEmpty()
                config = if (isNoOp) {
                    config.copy(perPackage = config.perPackage - app.packageName)
                } else if (config.perPackage.size < SystemSettingPrefs.MAX_PACKAGE_OVERRIDES ||
                    app.packageName in config.perPackage
                ) {
                    config.copy(perPackage = config.perPackage + (app.packageName to override))
                } else {
                    config
                }
            },
            onClear = existing?.let {
                {
                    editingApp = null
                    config = config.copy(perPackage = config.perPackage - app.packageName)
                }
            }
        )
    }
}

/** What this app ends up seeing, phrased for a list row. */
@Composable
private fun overrideSummary(config: SystemSettingPrefs.Config, packageName: String): String {
    if (!config.enabled) return stringResource(R.string.system_setting_disabled)
    val override = config.perPackage[packageName]
    if (override != null && !override.enabled) {
        return stringResource(R.string.system_setting_override_off)
    }
    val resolved = SystemSettingPrefs.resolveFor(config, packageName)
    val unchanged = stringResource(R.string.system_setting_unchanged)
    // Both empty would otherwise read "Leave unchanged · Leave unchanged".
    val summary = if (resolved.timeZoneId.isEmpty() && resolved.languageTag.isEmpty()) {
        unchanged
    } else {
        stringResource(
            R.string.system_setting_row_summary,
            resolved.timeZoneId.ifEmpty { unchanged },
            resolved.languageTag.ifEmpty { unchanged }
        )
    }
    return if (override == null) {
        stringResource(R.string.system_setting_row_default, summary)
    } else {
        summary
    }
}
