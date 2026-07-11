package asia.nana7mi.arirang.ui.component.device

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DeviceTextField(
    label: String,
    value: String,
    revision: Long,
    singleLine: Boolean = true,
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
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier.fillMaxWidth()
    )
    if (!singleLine) Spacer(modifier = Modifier.height(2.dp))
}
