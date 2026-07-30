package asia.nana7mi.arirang.ui.component.packagelist

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.Template
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs.TemplateListMode

/**
 * The dialogs the package-visibility screens need.
 *
 * The View implementation built these with `MaterialAlertDialogBuilder` and, for
 * the create-template case, a hand-assembled `LinearLayout` + `RadioGroup` —
 * which existed verbatim twice, once in `PackageListConfigActivity` and once in
 * `PackageTemplateManagerActivity`. [CreateTemplateDialog] is the single copy.
 */

/** Replaces `MaterialAlertDialogBuilder.setItems` — a titled list, dismissed on pick. */
@Composable
internal fun ChoiceListDialog(
    title: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 14.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/** Replaces `MaterialAlertDialogBuilder.setSingleChoiceItems` — radio list, dismissed on pick. */
@Composable
internal fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == selectedIndex,
                                onClick = { onSelect(index) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * Name plus whitelist/blacklist, with the mode's meaning spelled out.
 *
 * Confirm stays disabled until the trimmed name is non-empty. The View version
 * silently substituted "New Template" for a blank name, which produced several
 * identically named templates that were then impossible to tell apart in the
 * pickers that list them.
 */
@Composable
internal fun CreateTemplateDialog(
    onConfirm: (name: String, listMode: TemplateListMode) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var listMode by remember { mutableStateOf(TemplateListMode.WHITELIST) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Layers, contentDescription = null) },
        title = { Text(stringResource(R.string.template_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TemplateNameField(
                    value = name,
                    onValueChange = { name = it },
                    showRequiredHint = trimmedName.isEmpty()
                )
                TemplateModeSelector(selected = listMode, onSelect = { listMode = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmedName.isNotEmpty(),
                onClick = { onConfirm(trimmedName, listMode) }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
internal fun RenameTemplateDialog(
    initialName: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
        title = { Text(stringResource(R.string.template_rename)) },
        text = {
            TemplateNameField(
                value = name,
                onValueChange = { name = it },
                showRequiredHint = trimmedName.isEmpty()
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmedName.isNotEmpty(),
                onClick = { onConfirm(trimmedName) }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * Inheritance picker.
 *
 * Leads with what inheritance actually does, because neither half of it is
 * guessable: the parent's packages are merged in, and the *child's* list mode
 * decides how the merged set is read — the parent's own whitelist/blacklist
 * setting is discarded (`PackageListHookConfig.resolveTemplate`).
 *
 * [candidates] is expected to already exclude anything that would form a loop;
 * see `PackageVisibilityPrefs.eligibleParents`.
 */
@Composable
internal fun TemplateParentDialog(
    currentParentId: String?,
    candidates: List<Template>,
    packageCountOf: (Template) -> Int,
    onSelect: (Template?) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AccountTree, contentDescription = null) },
        title = { Text(stringResource(R.string.template_parent)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.template_inheritance_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ParentOptionRow(
                    title = stringResource(R.string.template_none),
                    subtitle = null,
                    selected = currentParentId == null,
                    onClick = { onSelect(null) }
                )
                candidates.forEach { candidate ->
                    ParentOptionRow(
                        title = candidate.name,
                        subtitle = stringResource(R.string.visible_count, packageCountOf(candidate)),
                        selected = candidate.id == currentParentId,
                        onClick = { onSelect(candidate) }
                    )
                }
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.template_no_eligible_parents),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCreateNew)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.template_new),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * Shared name input.
 *
 * `trimStart` runs on every keystroke so a leading space can never be entered;
 * the trailing one is removed when the caller trims on confirm, which is why it
 * is not stripped here — doing so would make it impossible to type a space
 * between two words.
 */
@Composable
private fun TemplateNameField(
    value: String,
    onValueChange: (String) -> Unit,
    showRequiredHint: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.trimStart()) },
        label = { Text(stringResource(R.string.template_name)) },
        singleLine = true,
        supportingText = if (showRequiredHint) {
            { Text(stringResource(R.string.template_name_required)) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ParentOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@StringRes
internal fun TemplateListMode.labelRes(): Int = when (this) {
    TemplateListMode.WHITELIST -> R.string.template_mode_whitelist
    TemplateListMode.BLACKLIST -> R.string.template_mode_blacklist
}

@StringRes
internal fun TemplateListMode.hintRes(): Int = when (this) {
    TemplateListMode.WHITELIST -> R.string.template_mode_whitelist_hint
    TemplateListMode.BLACKLIST -> R.string.template_mode_blacklist_hint
}
