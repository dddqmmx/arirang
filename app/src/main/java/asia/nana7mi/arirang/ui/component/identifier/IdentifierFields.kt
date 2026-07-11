package asia.nana7mi.arirang.ui.component.identifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs

@Composable
internal fun IdentifierTextField(
    label: String,
    value: String,
    revision: Long,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onRandom: (() -> Unit)? = null,
    onValueChange: (String) -> Unit
) {
    var localValue by remember(revision, label) { mutableStateOf(value) }
    OutlinedTextField(
        value = localValue,
        onValueChange = {
            localValue = it
            onValueChange(it)
        },
        label = { Text(label) },
        trailingIcon = {
            if (onRandom != null) {
                IconButton(onClick = onRandom) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_randomize))
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun ImeiRow(
    index: Int,
    imei: String,
    tac: String,
    revision: Long,
    canRemove: Boolean,
    onImeiChange: (String) -> Unit,
    onTacChange: (String) -> Unit,
    onRandomize: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = stringResource(R.string.unique_imei_slot_title, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_sim_slot))
                }
            }
            IdentifierTextField(
                label = stringResource(R.string.sim_field_imei),
                value = imei,
                revision = revision,
                keyboardType = KeyboardType.Number,
                onRandom = {
                    onImeiChange(UniqueIdentifierPrefs.randomImeiForSlot(index, tac))
                    onRandomize()
                },
                onValueChange = { onImeiChange(it.filter(Char::isDigit)) }
            )
            IdentifierTextField(
                label = stringResource(R.string.unique_field_type_allocation_code),
                value = tac,
                revision = revision,
                keyboardType = KeyboardType.Number,
                onRandom = {
                    val nextTac = UniqueIdentifierPrefs.randomTac()
                    onTacChange(nextTac)
                    onImeiChange(UniqueIdentifierPrefs.randomImeiForSlot(index, nextTac))
                    onRandomize()
                },
                onValueChange = { onTacChange(it.filter(Char::isDigit).take(8)) }
            )
        }
    }
}

internal data class ImeiRowState(
    val slot: Int,
    val imei: String,
    val tac: String
)
