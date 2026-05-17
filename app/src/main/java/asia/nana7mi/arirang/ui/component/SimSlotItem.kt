package asia.nana7mi.arirang.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import asia.nana7mi.arirang.model.SimPreset
import asia.nana7mi.arirang.model.SimPresetCatalog

fun getSimPresets(): List<SimPreset> = SimPresetCatalog.ALL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSlotItem(
    index: Int,
    simInfo: SimInfo,
    onSimInfoChange: (SimInfo) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    .clickable { onSimInfoChange(simInfo.copy(isExpanded = !simInfo.isExpanded)) },
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
                IconButton(onClick = { onSimInfoChange(simInfo.copy(isExpanded = !simInfo.isExpanded)) }) {
                    Icon(
                        imageVector = if (simInfo.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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

            AnimatedVisibility(visible = simInfo.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val simPresets = getSimPresets()
                    val matchedPreset = remember(simInfo.mcc, simInfo.mnc, simPresets) {
                        simPresets.find { it.mcc == simInfo.mcc && it.mnc == simInfo.mnc }
                    }

                    val customText = stringResource(R.string.sim_preset_custom)
                    var selectedCountry by remember(matchedPreset) {
                        mutableStateOf(matchedPreset?.countryName ?: if (simInfo.mcc.isNullOrBlank()) null else customText)
                    }

                    var expandedCountries by remember { mutableStateOf(false) }
                    var expandedCarriers by remember { mutableStateOf(false) }

                    val countries = remember(simPresets) { simPresets.map { it.countryName }.distinct() }
                    val filteredPresets = remember(selectedCountry, simPresets) {
                        simPresets.filter { it.countryName == selectedCountry }
                    }

                    val displayCarrier = remember(matchedPreset, selectedCountry) {
                        if (matchedPreset != null && matchedPreset.countryName == selectedCountry) {
                            matchedPreset.name
                        } else if (selectedCountry == customText) {
                            customText
                        } else {
                            ""
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Country Selector
                        ExposedDropdownMenuBox(
                            expanded = expandedCountries,
                            onExpandedChange = { expandedCountries = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedCountry ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.sim_preset_country)) },
                                placeholder = { Text(stringResource(R.string.sim_preset_country_placeholder)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCountries) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedCountries,
                                onDismissRequest = { expandedCountries = false }
                            ) {
                                countries.forEach { country ->
                                    DropdownMenuItem(
                                        text = { Text(country) },
                                        onClick = {
                                            selectedCountry = country
                                            expandedCountries = false
                                            expandedCarriers = true // Open carrier dropdown automatically
                                        }
                                    )
                                }
                            }
                        }

                        // Carrier Selector
                        ExposedDropdownMenuBox(
                            expanded = expandedCarriers,
                            onExpandedChange = { expandedCarriers = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = displayCarrier,
                                onValueChange = {},
                                readOnly = true,
                                enabled = selectedCountry != null && selectedCountry != customText,
                                label = { Text(stringResource(R.string.sim_preset_carrier)) },
                                placeholder = { 
                                    val text = when {
                                        selectedCountry == null -> stringResource(R.string.sim_preset_carrier_placeholder_init)
                                        selectedCountry == customText -> stringResource(R.string.sim_preset_carrier_placeholder_custom)
                                        else -> stringResource(R.string.sim_preset_carrier_placeholder_select)
                                    }
                                    Text(text)
                                },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCarriers) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedCarriers,
                                onDismissRequest = { expandedCarriers = false }
                            ) {
                                filteredPresets.forEach { preset ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(preset.name, fontWeight = FontWeight.Bold)
                                                Text(
                                                    "${stringResource(R.string.sim_mcc_prefix)}${preset.mcc} ${stringResource(R.string.sim_mnc_prefix)}${preset.mnc}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            onSimInfoChange(simInfo.copy(
                                                mcc = preset.mcc,
                                                mnc = preset.mnc,
                                                countryIso = preset.countryIso,
                                                carrierName = preset.carrierName,
                                                displayName = preset.displayName,
                                                carrierId = preset.carrierId
                                            ))
                                            expandedCarriers = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimField(
                            label = stringResource(R.string.sim_field_mcc),
                            value = simInfo.mcc ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(mcc = it)) },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.sim_mcc_default)
                        )
                        SimField(
                            label = stringResource(R.string.sim_field_mnc),
                            value = simInfo.mnc ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(mnc = it)) },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.sim_mnc_default)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimField(
                            label = stringResource(R.string.sim_field_country_iso),
                            value = simInfo.countryIso ?: "",
                            onValueChange = { onSimInfoChange(simInfo.copy(countryIso = it)) },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.sim_country_default)
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
                        placeholder = stringResource(R.string.sim_carrier_default)
                    )

                    SimField(
                        label = stringResource(R.string.sim_field_display_name),
                        value = simInfo.displayName ?: "",
                        onValueChange = { onSimInfoChange(simInfo.copy(displayName = it)) },
                        placeholder = stringResource(R.string.sim_carrier_default)
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

                    SimField(
                        label = stringResource(R.string.sim_field_imei),
                        value = simInfo.imei ?: "",
                        onValueChange = { onSimInfoChange(simInfo.copy(imei = it)) },
                        keyboardType = KeyboardType.Number
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

@Composable
private fun simSummary(simInfo: SimInfo): String {
    val name = simInfo.carrierName?.takeIf { it.isNotBlank() }
        ?: simInfo.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.sim_summary_fallback)
    val numeric = listOfNotNull(
        simInfo.mcc?.takeIf { it.isNotBlank() },
        simInfo.mnc?.takeIf { it.isNotBlank() }
    ).joinToString("")
    val slot = simInfo.simSlotIndex?.let { stringResource(R.string.slot_title_text, it) } // Reuse existing slot_title_text or use a specific format
    return listOf(name, numeric.takeIf { it.isNotBlank() }, slot)
        .filterNotNull()
        .joinToString(" / ")
}
