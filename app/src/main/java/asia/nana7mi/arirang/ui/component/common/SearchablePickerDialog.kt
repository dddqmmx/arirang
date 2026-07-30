package asia.nana7mi.arirang.ui.component.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R

/** One row of a [SearchablePickerDialog]. [id] must be unique within a list. */
internal data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null
)

/**
 * Single-select dialog with a search box, for lists too long to scroll blindly.
 *
 * Built for the time-zone (~600 entries) and locale (~800 entries) pickers, so
 * the list is a height-capped [LazyColumn] rather than a scrolling [Column] —
 * the latter would compose every row up front. Duplicate ids are dropped
 * defensively because [LazyColumn] throws on repeated keys, and a caller
 * building items from platform data cannot always guarantee uniqueness.
 */
@Composable
internal fun SearchablePickerDialog(
    title: String,
    items: List<PickerItem>,
    selectedId: String?,
    searchPlaceholder: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visibleItems = remember(items, query) {
        val normalizedQuery = query.trim().lowercase()
        items.asSequence()
            .distinctBy { it.id }
            .filter { item ->
                normalizedQuery.isEmpty() ||
                    item.title.lowercase().contains(normalizedQuery) ||
                    item.id.lowercase().contains(normalizedQuery) ||
                    item.subtitle?.lowercase()?.contains(normalizedQuery) == true
            }
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = searchPlaceholder
                )
                if (visibleItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.picker_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(
                            count = visibleItems.size,
                            key = { index -> visibleItems[index].id }
                        ) { index ->
                            val item = visibleItems[index]
                            PickerRow(
                                item = item,
                                selected = item.id == selectedId,
                                onClick = { onSelect(item.id) }
                            )
                        }
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

@Composable
private fun PickerRow(
    item: PickerItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = androidx.compose.ui.semantics.Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle != null) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
