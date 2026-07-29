package asia.nana7mi.arirang.ui.screen.packagelist

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import asia.nana7mi.arirang.ui.activity.packagelist.PackageCustomListActivity.TargetType
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.AppSearchField
import asia.nana7mi.arirang.ui.component.common.AppTypeFilter
import asia.nana7mi.arirang.ui.component.common.AppTypeFilterChips
import asia.nana7mi.arirang.ui.component.common.EmptyState
import asia.nana7mi.arirang.ui.component.common.loadInstalledApps
import asia.nana7mi.arirang.ui.component.common.matching
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.packagelist.SelectablePackageRow
import asia.nana7mi.arirang.ui.component.packagelist.labelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageCustomListScreen(
    targetType: TargetType,
    templateId: String?,
    targetPackageName: String?,
    title: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var savedSelection by remember { mutableStateOf(emptySet<String>()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(AppTypeFilter.ALL) }
    var overflowExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(targetType, templateId, targetPackageName) {
        val installed = loadInstalledApps(context)
        val initial = loadInitialSelection(context, targetType, templateId, targetPackageName)
        apps = installed
        // A null selection means "everything is visible"; treating it as an
        // empty set would silently flip the rule from show-all to show-none.
        selected = initial ?: installed.mapTo(mutableSetOf()) { it.packageName }
        savedSelection = selected
        loaded = true
    }

    val listMode = remember(targetType, templateId) {
        if (targetType == TargetType.TEMPLATE) {
            PackageVisibilityPrefs.loadTemplates(context).firstOrNull { it.id == templateId }?.listMode
        } else {
            null
        }
    }
    val displayTitle = if (listMode == null) title else "$title - ${stringResource(listMode.labelRes())}"

    val visibleApps = apps.matching(typeFilter, query)

    fun save() {
        // Each branch no-ops when its extra is missing but the screen still
        // closes, matching the View implementation: an early return here would
        // leave the user on a screen whose save button appears to do nothing.
        when (targetType) {
            TargetType.TEMPLATE -> templateId?.let { id ->
                val templates = PackageVisibilityPrefs.loadTemplates(context)
                    .map { if (it.id == id) it.copy(visiblePackages = selected) else it }
                PackageVisibilityPrefs.saveTemplates(context, templates)
            }

            TargetType.APP -> targetPackageName?.let { packageName ->
                val rules = PackageVisibilityPrefs.loadAppRules(context)
                    .filterNot { it.packageName == packageName } +
                    AppRule(packageName, DisplayMode.CUSTOM, visiblePackages = selected)
                PackageVisibilityPrefs.saveAppRules(context, rules)
            }
        }
        onSaved()
    }

    // Bulk actions deliberately act on the filtered subset rather than every
    // installed app, matching the View implementation: "select all" after a
    // search means "all of these", not "all six hundred".
    fun applyToVisible(transform: (current: Set<String>, packageName: String) -> Boolean) {
        selected = buildSet {
            addAll(selected)
            visibleApps.forEach { app ->
                if (transform(selected, app.packageName)) add(app.packageName) else remove(app.packageName)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayTitle) },
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
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = {
                                overflowExpanded = false
                                applyToVisible { _, _ -> true }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_all)) },
                            onClick = {
                                overflowExpanded = false
                                applyToVisible { _, _ -> false }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.invert_selection)) },
                            onClick = {
                                overflowExpanded = false
                                applyToVisible { current, packageName -> packageName !in current }
                            }
                        )
                    }
                    SaveConfigIconButton(
                        hasChanges = loaded && selected != savedSelection,
                        onClick = { save() }
                    )
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

            if (loaded && visibleApps.isEmpty()) {
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
                SelectablePackageRow(
                    app = app,
                    selected = app.packageName in selected,
                    onToggle = {
                        selected = if (app.packageName in selected) {
                            selected - app.packageName
                        } else {
                            selected + app.packageName
                        }
                    }
                )
            }
        }
    }
}

private suspend fun loadInitialSelection(
    context: Context,
    targetType: TargetType,
    templateId: String?,
    targetPackageName: String?
): Set<String>? = withContext(Dispatchers.IO) {
    val config = PackageVisibilityPrefs.loadConfig(context)
    when (targetType) {
        TargetType.TEMPLATE ->
            config.templates.firstOrNull { it.id == templateId }?.visiblePackages ?: emptySet()

        TargetType.APP -> {
            val rule = config.appRules.firstOrNull { it.packageName == targetPackageName }
            rule?.visiblePackages ?: PackageVisibilityPrefs.resolveRuleVisiblePackages(rule, config)
        }
    }
}
