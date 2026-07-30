package asia.nana7mi.arirang.ui.screen.packagelist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.DisplayMode
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.Template
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.TemplateListMode
import asia.nana7mi.arirang.ui.activity.packagelist.PackageCustomListActivity
import asia.nana7mi.arirang.ui.component.common.EmptyState
import asia.nana7mi.arirang.ui.component.packagelist.ChoiceListDialog
import asia.nana7mi.arirang.ui.component.packagelist.ConfirmDialog
import asia.nana7mi.arirang.ui.component.packagelist.CreateTemplateDialog
import asia.nana7mi.arirang.ui.component.packagelist.RenameTemplateDialog
import asia.nana7mi.arirang.ui.component.packagelist.SingleChoiceDialog
import asia.nana7mi.arirang.ui.component.packagelist.TemplateCard
import asia.nana7mi.arirang.ui.component.packagelist.TemplateParentDialog
import asia.nana7mi.arirang.ui.component.packagelist.labelRes

/** What a newly created template will be used for once it exists. */
private sealed interface NewTemplateUse {
    data object Standalone : NewTemplateUse
    data class AsParentOf(val childId: String) : NewTemplateUse
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageTemplateManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var templates by remember { mutableStateOf(PackageVisibilityPrefs.loadTemplates(context)) }
    var actionTarget by remember { mutableStateOf<Template?>(null) }
    var renaming by remember { mutableStateOf<Template?>(null) }
    var changingMode by remember { mutableStateOf<Template?>(null) }
    var settingParent by remember { mutableStateOf<Template?>(null) }
    var deleting by remember { mutableStateOf<Template?>(null) }
    var creatingFor by remember { mutableStateOf<NewTemplateUse?>(null) }

    fun refresh() {
        templates = PackageVisibilityPrefs.loadTemplates(context)
    }

    val editor = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    fun openEditor(template: Template) {
        editor.launch(PackageCustomListActivity.forTemplate(context, template.id, template.name))
    }

    fun updateTemplate(updated: Template) {
        val next = PackageVisibilityPrefs.loadTemplates(context)
            .map { if (it.id == updated.id) updated else it }
        PackageVisibilityPrefs.saveTemplates(context, next)
        refresh()
    }

    /**
     * Templates, the rules that referenced the deleted one, and the default
     * selection all move in a single commit. Committing them separately would
     * publish a state where a rule points at a template that no longer exists,
     * which is exactly what ConfigRegistry rejects when the submodule config is
     * rebuilt. See PackageVisibilityPrefs.edit.
     */
    fun deleteTemplate(template: Template) {
        val remainingTemplates = PackageVisibilityPrefs.loadTemplates(context)
            .filterNot { it.id == template.id }
            .map { if (it.parentId == template.id) it.copy(parentId = null) else it }
        val remainingRules = PackageVisibilityPrefs.loadAppRules(context)
            .filterNot { it.templateId == template.id }
        val config = PackageVisibilityPrefs.loadConfig(context)

        PackageVisibilityPrefs.edit(context) {
            templates(remainingTemplates)
            appRules(remainingRules)
            if (config.defaultTemplateId == template.id) {
                defaultSelection(DisplayMode.ALL_VISIBLE, null)
            }
        }
        refresh()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.template_manager)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creatingFor = NewTemplateUse.Standalone }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.template_new))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp)
        ) {
            if (templates.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Default.Layers,
                        title = stringResource(R.string.template_empty),
                        subtitle = stringResource(R.string.template_empty_hint)
                    )
                }
            }

            items(
                count = templates.size,
                key = { index -> templates[index].id }
            ) { index ->
                val template = templates[index]
                TemplateCard(
                    name = template.name,
                    listMode = template.listMode,
                    ancestorNames = PackageVisibilityPrefs.ancestorChain(template, templates)
                        .map { it.name },
                    ownCount = template.visiblePackages.size,
                    totalCount = PackageVisibilityPrefs
                        .resolvedTemplatePackages(template, templates).size,
                    onClick = { actionTarget = template }
                )
            }
        }
    }

    actionTarget?.let { template ->
        val options = listOf(
            stringResource(R.string.template_edit_list),
            stringResource(R.string.template_list_mode),
            stringResource(R.string.template_rename),
            stringResource(R.string.template_set_parent),
            stringResource(R.string.template_delete)
        )
        ChoiceListDialog(
            title = template.name,
            options = options,
            onDismiss = { actionTarget = null },
            onSelect = { index ->
                actionTarget = null
                when (index) {
                    0 -> openEditor(template)
                    1 -> changingMode = template
                    2 -> renaming = template
                    3 -> settingParent = template
                    4 -> deleting = template
                }
            }
        )
    }

    changingMode?.let { template ->
        val modes = TemplateListMode.entries
        SingleChoiceDialog(
            title = stringResource(R.string.template_list_mode),
            options = modes.map { stringResource(it.labelRes()) },
            selectedIndex = modes.indexOf(template.listMode),
            onDismiss = { changingMode = null },
            onSelect = { index ->
                changingMode = null
                updateTemplate(template.copy(listMode = modes[index]))
            }
        )
    }

    renaming?.let { template ->
        RenameTemplateDialog(
            initialName = template.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                updateTemplate(template.copy(name = name))
            }
        )
    }

    settingParent?.let { template ->
        // eligibleParents drops the template itself and anything already
        // inheriting from it, so a loop cannot be selected in the first place.
        TemplateParentDialog(
            currentParentId = template.parentId,
            candidates = PackageVisibilityPrefs.eligibleParents(template, templates),
            packageCountOf = { PackageVisibilityPrefs.resolvedTemplatePackages(it, templates).size },
            onDismiss = { settingParent = null },
            onSelect = { parent ->
                settingParent = null
                updateTemplate(template.copy(parentId = parent?.id))
            },
            onCreateNew = {
                settingParent = null
                creatingFor = NewTemplateUse.AsParentOf(template.id)
            }
        )
    }

    deleting?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.template_delete),
            message = stringResource(R.string.template_delete_confirm, template.name),
            confirmLabel = stringResource(R.string.template_delete),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                deleteTemplate(template)
            }
        )
    }

    creatingFor?.let { use ->
        CreateTemplateDialog(
            onDismiss = { creatingFor = null },
            onConfirm = { name, listMode ->
                creatingFor = null
                val created = PackageVisibilityPrefs.createTemplate(context, name, listMode = listMode)
                refresh()
                when (use) {
                    NewTemplateUse.Standalone -> openEditor(created)
                    is NewTemplateUse.AsParentOf ->
                        PackageVisibilityPrefs.loadTemplates(context)
                            .firstOrNull { it.id == use.childId }
                            ?.let { child -> updateTemplate(child.copy(parentId = created.id)) }
                }
            }
        )
    }
}
