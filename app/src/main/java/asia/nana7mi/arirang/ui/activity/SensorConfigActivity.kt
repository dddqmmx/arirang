package asia.nana7mi.arirang.ui.activity

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs.SensorEntry
import asia.nana7mi.arirang.ui.component.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class SensorConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        var initialConfig = SensorConfigPrefs.loadConfig(this)

        // Seed sensor entries from device if the list is empty (first launch)
        if (initialConfig.sensorEntries.isEmpty()) {
            val sm = getSystemService(SensorManager::class.java)
            val deviceEntries = sm?.getSensorList(Sensor.TYPE_ALL).orEmpty()
                .map { SensorEntry(name = it.name, vendor = it.vendor, type = it.type) }
                .distinctBy { it.type }
                .sortedBy { it.type }
            initialConfig = initialConfig.copy(sensorEntries = deviceEntries)
        }

        setContent {
            ArirangTheme {
                SensorConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        SensorConfigPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success_reboot_required), Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SensorConfigScreen(
        initialConfig: SensorConfigPrefs.Config,
        onBack: () -> Unit,
        onSave: (SensorConfigPrefs.Config) -> Unit
    ) {
        var config by remember { mutableStateOf(initialConfig) }
        var savedConfig by remember { mutableStateOf(initialConfig) }
        var showUnsavedDialog by remember { mutableStateOf(false) }
        var editingIndex by remember { mutableIntStateOf(-1) }
        var showAddDialog by remember { mutableStateOf(false) }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        fun saveCurrent() { onSave(config); savedConfig = config }
        val hasChanges = config != savedConfig
        fun requestBack() { if (hasChanges) showUnsavedDialog = true else onBack() }

        BackHandler { requestBack() }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.sensor_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        SaveConfigIconButton(hasChanges = hasChanges, onClick = { saveCurrent() })
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
            ) {
                // ── Master switch ──
                item(key = "master") {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SwitchRow(
                                title = stringResource(R.string.sensor_hook_enabled),
                                summary = stringResource(R.string.sensor_hook_enabled_summary),
                                checked = config.enabled,
                                onCheckedChange = { config = config.copy(enabled = it) },
                                bold = true
                            )
                        }
                    }
                }

                // ── Vendor replacement ──
                item(key = "vendor_replace") {
                    SectionCard(title = stringResource(R.string.sensor_section_vendor)) {
                        Text(
                            text = stringResource(R.string.sensor_vendor_keywords_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = config.vendorKeywords,
                            onValueChange = { config = config.copy(vendorKeywords = it) },
                            label = { Text(stringResource(R.string.sensor_vendor_keywords_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = stringResource(R.string.sensor_vendor_replace_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = config.vendorReplacement,
                            onValueChange = { config = config.copy(vendorReplacement = it) },
                            label = { Text(stringResource(R.string.sensor_vendor_replace_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilledTonalButton(
                            onClick = {
                                val replacement = config.vendorReplacement
                                val keywords = config.vendorKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                val updated = config.sensorEntries.map { entry ->
                                    entry.copy(
                                        vendor = SensorConfigPrefs.applyCaseAwareReplace(entry.vendor, replacement, keywords),
                                        name = SensorConfigPrefs.applyCaseAwareReplace(entry.name, replacement, keywords)
                                    )
                                }
                                config = config.copy(sensorEntries = updated)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.sensor_vendor_replace_apply))
                        }
                    }
                }

                // ── Hide all ──
                item(key = "hide_all") {
                    SectionCard(title = stringResource(R.string.sensor_hide_all)) {
                        SwitchRow(
                            title = stringResource(R.string.sensor_hide_all),
                            summary = stringResource(R.string.sensor_hide_all_summary),
                            checked = config.hideAll,
                            onCheckedChange = { config = config.copy(hideAll = it) }
                        )
                    }
                }

                // ── Media ──
                item(key = "media") {
                    SectionCard(title = stringResource(R.string.sensor_section_media)) {
                        SwitchRow(stringResource(R.string.sensor_disable_mic), stringResource(R.string.sensor_disable_mic_summary), config.disableMic, onCheckedChange = { config = config.copy(disableMic = it) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SwitchRow(stringResource(R.string.sensor_disable_camera_front), stringResource(R.string.sensor_disable_camera_front_summary), config.disableCameraFront, onCheckedChange = { config = config.copy(disableCameraFront = it) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SwitchRow(stringResource(R.string.sensor_disable_camera_rear), stringResource(R.string.sensor_disable_camera_rear_summary), config.disableCameraRear, onCheckedChange = { config = config.copy(disableCameraRear = it) })
                    }
                }

                // ── Motion ──
                item(key = "motion") {
                    SectionCard(title = stringResource(R.string.sensor_section_motion)) {
                        SwitchRow(stringResource(R.string.sensor_disable_accel), null, config.disableAccel, onCheckedChange = { config = config.copy(disableAccel = it) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SwitchRow(stringResource(R.string.sensor_disable_gyro), null, config.disableGyro, onCheckedChange = { config = config.copy(disableGyro = it) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SwitchRow(stringResource(R.string.sensor_disable_magnetic), null, config.disableMagnetic, onCheckedChange = { config = config.copy(disableMagnetic = it) })
                    }
                }

                // ── Precision ──
                item(key = "precision") {
                    SectionCard(title = stringResource(R.string.sensor_section_precision)) {
                        Text(stringResource(R.string.sensor_precision_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrecisionDropdown(config.precisionLevel) { config = config.copy(precisionLevel = it) }
                    }
                }

                // ── Sensor list header ──
                item(key = "sensor_list_header") {
                    SectionCard(title = stringResource(R.string.sensor_section_list)) {
                        Text(stringResource(R.string.sensor_list_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ── Sensor entries ──
                itemsIndexed(
                    items = config.sensorEntries,
                    key = { idx, entry -> "sensor_${idx}_${entry.type}" }
                ) { index, entry ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardDefaults.elevatedShape)
                            .clickable { editingIndex = index },
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (entry.hidden)
                                MaterialTheme.colorScheme.surfaceContainerLow
                            else
                                MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                    if (entry.isCustom) {
                                        Text(
                                            text = " · ${stringResource(R.string.sensor_custom_tag)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(entry.vendor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    sensorTypeLabel(entry.type) + " · " + stringResource(R.string.sensor_type_format, entry.type),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = !entry.hidden,
                                onCheckedChange = { visible ->
                                    val updated = config.sensorEntries.toMutableList()
                                    updated[index] = entry.copy(hidden = !visible)
                                    config = config.copy(sensorEntries = updated)
                                }
                            )
                        }
                    }
                }

                // ── Add sensor button ──
                item(key = "add_sensor") {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.sensor_add))
                    }
                }
            }
        }

        // ── Unsaved dialog ──
        if (showUnsavedDialog) {
            UnsavedChangesDialog(
                onDismiss = { showUnsavedDialog = false },
                onDiscard = { showUnsavedDialog = false; onBack() },
                onSave = { showUnsavedDialog = false; saveCurrent(); onBack() }
            )
        }

        // ── Edit sensor dialog ──
        if (editingIndex >= 0 && editingIndex < config.sensorEntries.size) {
            val entry = config.sensorEntries[editingIndex]
            SensorEditDialog(
                entry = entry,
                onDismiss = { editingIndex = -1 },
                onSave = { updated ->
                    val list = config.sensorEntries.toMutableList()
                    list[editingIndex] = updated
                    config = config.copy(sensorEntries = list)
                    editingIndex = -1
                },
                onDelete = {
                    val list = config.sensorEntries.toMutableList()
                    list.removeAt(editingIndex)
                    config = config.copy(sensorEntries = list)
                    editingIndex = -1
                }
            )
        }

        // ── Add sensor dialog ──
        if (showAddDialog) {
            SensorEditDialog(
                entry = SensorEntry(name = "New Sensor", vendor = "", type = 1, isCustom = true),
                onDismiss = { showAddDialog = false },
                onSave = { newEntry ->
                    config = config.copy(sensorEntries = config.sensorEntries + newEntry.copy(isCustom = true))
                    showAddDialog = false
                },
                onDelete = null
            )
        }
    }

    // ──────────────────────────────────────────────
    // Edit / Add dialog
    // ──────────────────────────────────────────────

    @Composable
    private fun SensorEditDialog(
        entry: SensorEntry,
        onDismiss: () -> Unit,
        onSave: (SensorEntry) -> Unit,
        onDelete: (() -> Unit)?
    ) {
        var name by remember { mutableStateOf(entry.name) }
        var vendor by remember { mutableStateOf(entry.vendor) }
        var typeStr by remember { mutableStateOf(entry.type.toString()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.sensor_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.sensor_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = vendor,
                        onValueChange = { vendor = it },
                        label = { Text(stringResource(R.string.sensor_field_vendor)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = typeStr,
                        onValueChange = { typeStr = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.sensor_field_type)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.sensor_remove))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSave(entry.copy(name = name, vendor = vendor, type = typeStr.toIntOrNull() ?: entry.type))
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // ──────────────────────────────────────────────
    // Composable helpers
    // ──────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PrecisionDropdown(selectedLevel: Int, onLevelSelected: (Int) -> Unit) {
        val options = listOf(
            SensorConfigPrefs.PRECISION_ORIGINAL to stringResource(R.string.sensor_precision_original),
            SensorConfigPrefs.PRECISION_LOW to stringResource(R.string.sensor_precision_low),
            SensorConfigPrefs.PRECISION_MEDIUM to stringResource(R.string.sensor_precision_medium),
            SensorConfigPrefs.PRECISION_HIGH to stringResource(R.string.sensor_precision_high)
        )
        var expanded by remember { mutableStateOf(false) }
        val selectedText = options.firstOrNull { it.first == selectedLevel }?.second ?: options.first().second

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedText,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sensor_precision_level)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (level, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onLevelSelected(level); expanded = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        summary: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        bold: Boolean = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    title,
                    style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
                )
                if (summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    // ──────────────────────────────────────────────
    // Sensor type label
    // ──────────────────────────────────────────────

    private fun sensorTypeLabel(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic Field"
        Sensor.TYPE_ORIENTATION -> "Orientation"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_PRESSURE -> "Pressure"
        Sensor.TYPE_TEMPERATURE -> "Temperature"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative Humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temperature"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic Field (Uncalibrated)"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game Rotation Vector"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope (Uncalibrated)"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant Motion"
        Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
        Sensor.TYPE_STEP_COUNTER -> "Step Counter"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic Rotation Vector"
        Sensor.TYPE_HEART_RATE -> "Heart Rate"
        Sensor.TYPE_POSE_6DOF -> "Pose 6DOF"
        Sensor.TYPE_STATIONARY_DETECT -> "Stationary Detect"
        Sensor.TYPE_MOTION_DETECT -> "Motion Detect"
        Sensor.TYPE_HEART_BEAT -> "Heart Beat"
        Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> "Low Latency Offbody Detect"
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Accelerometer (Uncalibrated)"
        Sensor.TYPE_HINGE_ANGLE -> "Hinge Angle"
        Sensor.TYPE_HEAD_TRACKER -> "Head Tracker"
        Sensor.TYPE_ACCELEROMETER_LIMITED_AXES -> "Accelerometer (Limited Axes)"
        Sensor.TYPE_GYROSCOPE_LIMITED_AXES -> "Gyroscope (Limited Axes)"
        Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED -> "Accelerometer (Limited, Uncalibrated)"
        Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED -> "Gyroscope (Limited, Uncalibrated)"
        Sensor.TYPE_HEADING -> "Heading"
        else -> "Sensor"
    }
}
