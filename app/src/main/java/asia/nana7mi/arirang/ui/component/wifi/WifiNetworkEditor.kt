package asia.nana7mi.arirang.ui.component.wifi

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
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import java.security.SecureRandom

@Composable
internal fun ScanNetworkEditor(
    index: Int,
    network: WifiConfigPrefs.ScanNetwork,
    expanded: Boolean,
    canRemove: Boolean,
    onExpandedChange: () -> Unit,
    onNetworkChange: (WifiConfigPrefs.ScanNetwork) -> Unit,
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
                    text = stringResource(R.string.wifi_scan_result_title, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOf(network.ssid, network.bssid)
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
                    contentDescription = stringResource(R.string.wifi_remove_scan_result),
                    tint = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WifiTextField(
                    label = stringResource(R.string.wifi_field_scan_ssid),
                    value = network.ssid,
                    onValueChange = { onNetworkChange(network.copy(ssid = it)) }
                )
                WifiTextField(
                    label = stringResource(R.string.wifi_field_scan_bssid),
                    value = network.bssid,
                    onValueChange = { onNetworkChange(network.copy(bssid = it)) },
                    onRandom = { onNetworkChange(network.copy(bssid = randomBssid())) }
                )
            }
        }
    }
}

@Composable
internal fun WifiTextField(
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

private val bssidRandom = SecureRandom()

internal fun randomBssid(): String = buildString {
    repeat(6) { i ->
        if (i > 0) append(':')
        append("%02X".format(bssidRandom.nextInt(256)))
    }
}
