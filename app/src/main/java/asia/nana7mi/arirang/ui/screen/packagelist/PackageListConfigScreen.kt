package asia.nana7mi.arirang.ui.screen.packagelist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.AppRule
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.DisplayMode
import asia.nana7mi.arirang.ui.activity.packagelist.PackageCustomListActivity
import asia.nana7mi.arirang.ui.activity.packagelist.PackageTemplateManagerActivity
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.AppSearchField
import asia.nana7mi.arirang.ui.component.common.AppTypeFilter
import asia.nana7mi.arirang.ui.component.common.AppTypeFilterChips
import asia.nana7mi.arirang.ui.component.common.EmptyState
import asia.nana7mi.arirang.ui.component.common.ToggleSettingRow
import asia.nana7mi.arirang.ui.component.common.loadInstalledApps
import asia.nana7mi.arirang.ui.component.common.matching
import asia.nana7mi.arirang.ui.component.packagelist.AppRuleRow
import asia.nana7mi.arirang.ui.component.packagelist.ChoiceListDialog
import asia.nana7mi.arirang.ui.component.packagelist.CreateTemplateDialog
import asia.nana7mi.arirang.ui.component.packagelist.LabelledDropdown

/**
 * Why a newly created template is being created — the create dialog is reachable
 * both from the default-list dropdown and from an individual app's rule picker,
 * and the two do different things with the result.
 */
private sealed interface TemplateDestination {
    data object AsDefault : TemplateDestination
    data class ForApp(val packageName: String) : TemplateDestination
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageListConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var config by remember { mutableStateOf(PackageVisibilityPrefs.loadConfig(context)) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    // ALL, not USER: the View screen listed system apps too, and narrowing the
    // default would hide rules the user had already configured.
    var typeFilter by remember { mutableStateOf(AppTypeFilter.ALL) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var ruleTarget by remember { mutableStateOf<AppEntry?>(null) }
    var creatingTemplateFor by remember { mutableStateOf<TemplateDestination?>(null) }

    // Unconditional: PackageCustomListActivity sets RESULT_OK but the template
    // manager never sets a result, and both can change what this screen shows.
    val childScreen = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { config = PackageVisibilityPrefs.loadConfig(context) }

    LaunchedEffect(Unit) { apps = loadInstalledApps(context) }

    fun saveAppRule(rule: AppRule) {
        val updated = config.appRules.filterNot { it.packageName == rule.packageName } + rule
        PackageVisibilityPrefs.saveAppRules(context, updated)
        config = PackageVisibilityPrefs.loadConfig(context)
    }

    fun openTemplateEditor(templateId: String, templateName: String) {
        childScreen.launch(PackageCustomListActivity.forTemplate(context, templateId, templateName))
    }

    val rulesByPackage = config.appRules.associateBy { it.packageName }

    // A DEFAULT-mode rule is indistinguishable from having no rule at all, so
    // neither counts as configured for sorting or for the highlighted status.
    fun isConfigured(packageName: String): Boolean =
        rulesByPackage[packageName]?.mode?.takeIf { it != DisplayMode.DEFAULT } != null

    val visibleApps = apps
        .matching(typeFilter, query)
        .sortedWith(
            compareByDescending<AppEntry> { isConfigured(it.packageName) }
                .thenBy { it.label.lowercase() }
        )

    val allVisibleLabel = stringResource(R.string.display_all_visible)
    val allHiddenLabel = stringResource(R.string.display_all_hidden)
    val newTemplateLabel = stringResource(R.string.template_new)
    val customLabel = stringResource(R.string.display_custom)

    // Order matters: index 0/1 are the two fixed modes, then one entry per
    // template, then "new template" last.
    val defaultOptions = buildList {
        add(allVisibleLabel)
        add(allHiddenLabel)
        addAll(config.templates.map { it.name })
        add(newTemplateLabel)
    }
    val defaultSelectedLabel = when (config.defaultMode) {
        DisplayMode.ALL_HIDDEN -> allHiddenLabel
        DisplayMode.TEMPLATE ->
            config.templates.firstOrNull { it.id == config.defaultTemplateId }?.name ?: allVisibleLabel
        else -> allVisibleLabel
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.package_list_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.template_manager)) },
                            onClick = {
                                overflowExpanded = false
                                childScreen.launch(
                                    Intent(context, PackageTemplateManagerActivity::class.java)
                                )
                            }
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item(key = "settings") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ToggleSettingRow(
                            title = stringResource(R.string.package_list_enable),
                            summary = stringResource(R.string.package_list_enable_summary),
                            checked = config.enabled,
                            onCheckedChange = { enabled ->
                                PackageVisibilityPrefs.setEnabled(context, enabled)
                                config = PackageVisibilityPrefs.loadConfig(context)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        LabelledDropdown(
                            label = stringResource(R.string.default_display_list),
                            selectedLabel = defaultSelectedLabel,
                            options = defaultOptions,
                            onSelect = { index ->
                                when (index) {
                                    0 -> PackageVisibilityPrefs.setDefaultSelection(
                                        context, DisplayMode.ALL_VISIBLE, null
                                    )

                                    1 -> PackageVisibilityPrefs.setDefaultSelection(
                                        context, DisplayMode.ALL_HIDDEN, null
                                    )

                                    defaultOptions.lastIndex ->
                                        creatingTemplateFor = TemplateDestination.AsDefault

                                    else -> config.templates.getOrNull(index - 2)?.let { template ->
                                        PackageVisibilityPrefs.setDefaultSelection(
                                            context, DisplayMode.TEMPLATE, template.id
                                        )
                                    }
                                }
                                config = PackageVisibilityPrefs.loadConfig(context)
                            }
                        )
                    }
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
                val rule = rulesByPackage[app.packageName]
                AppRuleRow(
                    app = app,
                    statusText = ruleStatusText(config, rule),
                    isConfigured = isConfigured(app.packageName),
                    onClick = { ruleTarget = app }
                )
            }
        }
    }

    ruleTarget?.let { app ->
        val options = buildList {
            add(newTemplateLabel)
            add(allVisibleLabel)
            add(allHiddenLabel)
            add(customLabel)
            addAll(config.templates.map { it.name })
        }
        val title = stringResource(R.string.app_visibility_title, app.label)
        ChoiceListDialog(
            title = title,
            options = options,
            onDismiss = { ruleTarget = null },
            onSelect = { index ->
                ruleTarget = null
                when (index) {
                    0 -> creatingTemplateFor = TemplateDestination.ForApp(app.packageName)
                    1 -> saveAppRule(AppRule(app.packageName, DisplayMode.ALL_VISIBLE))
                    2 -> saveAppRule(AppRule(app.packageName, DisplayMode.ALL_HIDDEN))
                    3 -> childScreen.launch(
                        PackageCustomListActivity.forApp(context, app.packageName, title)
                    )

                    else -> config.templates.getOrNull(index - 4)?.let { template ->
                        saveAppRule(AppRule(app.packageName, DisplayMode.TEMPLATE, template.id))
                    }
                }
            }
        )
    }

    creatingTemplateFor?.let { destination ->
        CreateTemplateDialog(
            onDismiss = { creatingTemplateFor = null },
            onConfirm = { name, listMode ->
                creatingTemplateFor = null
                val template = PackageVisibilityPrefs.createTemplate(context, name, listMode = listMode)
                when (destination) {
                    TemplateDestination.AsDefault -> PackageVisibilityPrefs.setDefaultSelection(
                        context, DisplayMode.TEMPLATE, template.id
                    )

                    is TemplateDestination.ForApp -> saveAppRule(
                        AppRule(destination.packageName, DisplayMode.TEMPLATE, template.id)
                    )
                }
                config = PackageVisibilityPrefs.loadConfig(context)
                openTemplateEditor(template.id, template.name)
            }
        )
    }
}

@Composable
private fun ruleStatusText(
    config: PackageVisibilityPrefs.Config,
    rule: AppRule?
): String {
    if (rule == null || rule.mode == DisplayMode.DEFAULT) return defaultStatusText(config)
    return when (rule.mode) {
        DisplayMode.ALL_VISIBLE -> stringResource(R.string.display_all_visible)
        DisplayMode.ALL_HIDDEN -> stringResource(R.string.display_all_hidden)
        DisplayMode.TEMPLATE -> stringResource(
            R.string.rule_status_template,
            config.templates.firstOrNull { it.id == rule.templateId }?.name
                ?: stringResource(R.string.template_none)
        )

        DisplayMode.CUSTOM -> stringResource(R.string.rule_status_custom, rule.visiblePackages.size)
        DisplayMode.DEFAULT -> defaultStatusText(config)
    }
}

@Composable
private fun defaultStatusText(config: PackageVisibilityPrefs.Config): String =
    when (config.defaultMode) {
        DisplayMode.ALL_VISIBLE, DisplayMode.DEFAULT -> stringResource(R.string.display_all_visible)
        DisplayMode.ALL_HIDDEN -> stringResource(R.string.display_all_hidden)
        DisplayMode.TEMPLATE -> stringResource(
            R.string.rule_status_template,
            config.templates.firstOrNull { it.id == config.defaultTemplateId }?.name
                ?: stringResource(R.string.template_none)
        )

        DisplayMode.CUSTOM -> stringResource(R.string.display_all_hidden)
    }
