package asia.nana7mi.arirang.ui.component.bluetooth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import java.security.SecureRandom

@Composable
internal fun DeviceEditor(
    title: String,
    removeContentDescription: String,
    device: BluetoothConfigPrefs.Device,
    expanded: Boolean,
    canRemove: Boolean,
    onExpandedChange: () -> Unit,
    onDeviceChange: (BluetoothConfigPrefs.Device) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOf(device.name, device.address)
                        .filter { it.isNotBlank() }
                        .joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onExpandedChange) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = removeContentDescription,
                    tint = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BluetoothTextField(
                    label = stringResource(R.string.bluetooth_field_name),
                    value = device.name,
                    onValueChange = { onDeviceChange(device.copy(name = it)) }
                )
                BluetoothTextField(
                    label = stringResource(R.string.bluetooth_field_address),
                    value = device.address,
                    onValueChange = { onDeviceChange(device.copy(address = it)) },
                    onRandom = { onDeviceChange(device.copy(address = randomMacAddress())) }
                )
            }
        }
    }
}

@Composable
internal fun BluetoothTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onRandom: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = {
            if (onRandom != null) {
                IconButton(onClick = onRandom) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_randomize))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private val macRandom = SecureRandom()

private fun randomMacAddress(): String = buildString {
    repeat(6) { i ->
        if (i > 0) append(':')
        append("%02X".format(macRandom.nextInt(256)))
    }
}
