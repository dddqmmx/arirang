package asia.nana7mi.arirang.ui.component.sensor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs

@Composable
internal fun PrecisionDropdown(labelStr: String, selectedLevel: Int, onLevelSelected: (Int) -> Unit) {
    val options = listOf(
        SensorConfigPrefs.PRECISION_ORIGINAL to stringResource(R.string.sensor_precision_original),
        SensorConfigPrefs.PRECISION_LOW to stringResource(R.string.sensor_precision_low),
        SensorConfigPrefs.PRECISION_MEDIUM to stringResource(R.string.sensor_precision_medium),
        SensorConfigPrefs.PRECISION_HIGH to stringResource(R.string.sensor_precision_high)
    )
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.firstOrNull { it.first == selectedLevel }?.second ?: options.first().second

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = labelStr, style = MaterialTheme.typography.bodyMedium)

        androidx.compose.foundation.layout.Box {
            Text(
                text = selectedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (level, label) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onLevelSelected(level); expanded = false }
                    )
                }
            }
        }
    }
}
