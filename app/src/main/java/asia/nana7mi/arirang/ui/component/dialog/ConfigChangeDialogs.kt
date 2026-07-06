package asia.nana7mi.arirang.ui.component.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import asia.nana7mi.arirang.R

@Composable
fun SaveConfigIconButton(
    hasChanges: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.Save,
            contentDescription = stringResource(R.string.save),
            tint = if (hasChanges) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
            }
        )
    }
}

@Composable
fun UnsavedChangesDialog(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsaved_changes_title)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.discard_changes))
            }
        }
    )
}

@Composable
fun InfoDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}
