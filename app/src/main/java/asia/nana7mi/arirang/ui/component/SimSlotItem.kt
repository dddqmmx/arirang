package asia.nana7mi.arirang.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.SimInfo

@Composable
fun SimSlotItem(
    index: Int,
    simInfo: SimInfo,
    onSimInfoChange: (SimInfo) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(index == 0) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SimCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.slot_title_text, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = simSummary(simInfo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.remove_sim_slot),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimField(
                            label = stringResource(R.string.sim_field_mcc),
                            value = simInfo.mcc ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(mcc = it)) },
                            modifier = Modifier.weight(1f),
                            placeholder = "467"
                        )
                        SimField(
                            label = stringResource(R.string.sim_field_mnc),
                            value = simInfo.mnc ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(mnc = it)) },
                            modifier = Modifier.weight(1f),
                            placeholder = "05"
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimField(
                            label = stringResource(R.string.sim_field_country_iso),
                            value = simInfo.countryIso ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(countryIso = it)) },
                            modifier = Modifier.weight(1f),
                            placeholder = "kp"
                        )
                        SimField(
                            label = stringResource(R.string.sim_field_carrier_id),
                            value = simInfo.carrierId?.toString() ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(carrierId = it.toIntOrNull())) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                            placeholder = "-1"
                        )
                    }

                    SimField(
                        label = stringResource(R.string.sim_field_carrier_name),
                        value = simInfo.carrierName ?: "",
                        onValueChange = { onSimInfoChange(simInfo.copy(carrierName = it)) },
                        placeholder = "Koryolink"
                    )

                    SimField(
                        label = stringResource(R.string.sim_field_display_name),
                        value = simInfo.displayName ?: "",
                        onValueChange = { onSimInfoChange(simInfo.copy(displayName = it)) },
                        placeholder = "Koryolink"
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimField(
                            label = stringResource(R.string.sim_field_subscription_id),
                            value = simInfo.id?.toString() ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(id = it.toIntOrNull())) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number
                        )
                        SimField(
                            label = stringResource(R.string.sim_field_slot_index),
                            value = simInfo.simSlotIndex?.toString() ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(simSlotIndex = it.toIntOrNull())) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number
                        )
                    }

                    SimField(
                        label = stringResource(R.string.sim_field_iccid),
                        value = simInfo.iccId ?: "",
                        onValueChange = { onSimInfoChange(simInfo.copy(iccId = it)) }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimField(
                            label = stringResource(R.string.sim_field_card_id),
                            value = simInfo.cardId?.toString() ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(cardId = it.toIntOrNull())) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number
                        )
                        SimField(
                            label = stringResource(R.string.sim_field_number),
                            value = simInfo.number ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(number = it)) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Phone
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SimField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(8.dp)
    )
}

private fun simSummary(simInfo: SimInfo): String {
    val name = simInfo.carrierName?.takeIf { it.isNotBlank() }
        ?: simInfo.displayName?.takeIf { it.isNotBlank() }
        ?: "SIM"
    val numeric = listOfNotNull(
        simInfo.mcc?.takeIf { it.isNotBlank() },
        simInfo.mnc?.takeIf { it.isNotBlank() }
    ).joinToString("")
    val slot = simInfo.simSlotIndex?.let { "slot $it" }
    return listOf(name, numeric.takeIf { it.isNotBlank() }, slot)
        .filterNotNull()
        .joinToString(" / ")
}
