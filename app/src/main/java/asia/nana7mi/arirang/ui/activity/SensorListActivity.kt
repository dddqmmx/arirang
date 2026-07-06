package asia.nana7mi.arirang.ui.activity

import android.hardware.Sensor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs.SensorEntry
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class SensorListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = SensorConfigPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                SensorListScreen(
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
    private fun SensorListScreen(
        initialConfig: SensorConfigPrefs.Config,
        onBack: () -> Unit,
        onSave: (SensorConfigPrefs.Config) -> Unit
    ) {
        val context = LocalContext.current
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
                    title = { Text(stringResource(R.string.sensor_section_list)) },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val sm = context.getSystemService(SensorManager::class.java)
                                val deviceEntries = sm?.getSensorList(Sensor.TYPE_ALL).orEmpty()
                                    .map { SensorEntry(name = it.name, vendor = it.vendor, type = it.type) }
                                    .distinctBy { it.type }
                                    .sortedBy { it.type }
                                config = config.copy(
                                    sensorEntries = deviceEntries,
                                    vendorKeywords = "",
                                    vendorReplacement = ""
                                )
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reset))
                        }
                        SaveConfigIconButton(hasChanges = hasChanges, onClick = { saveCurrent() })
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sensor_add))
                }
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
                // ── Vendor replacement ──
                if (!config.hideAll) {
                    item(key = "vendor_replace") {
                        SectionCard(title = stringResource(R.string.sensor_section_vendor)) {
                            Text(
                                text = stringResource(R.string.sensor_vendor_keywords_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = config.vendorKeywords,
                                onValueChange = { config = config.copy(vendorKeywords = it.replace(" ", "")) },
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
                                onValueChange = { config = config.copy(vendorReplacement = it.replace(" ", "")) },
                                label = { Text(stringResource(R.string.sensor_vendor_replace_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilledTonalButton(
                                onClick = {
                                    val replacement = config.vendorReplacement.replace(" ", "")
                                    val keywords = config.vendorKeywords.split(",").map { it.replace(" ", "") }.filter { it.isNotEmpty() }
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

                if (!config.hideAll) {
                    // ── Sensor list header ──
                    item(key = "sensor_list_header") {
                        SectionCard(title = stringResource(R.string.sensor_section_list)) {
                            Text(stringResource(R.string.sensor_list_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
    
                    // ── Sensor entries ──
                    itemsIndexed(
                        items = config.sensorEntries,
                        key = { _, entry -> entry.id }
                    ) { index, entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingIndex = index },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (entry.hidden)
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Icon Box
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            if (entry.hidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Sensors,
                                        contentDescription = null,
                                        tint = if (entry.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
    
                                // Text Column
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                            color = if (entry.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (entry.isCustom) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.sensor_custom_tag),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = entry.vendor,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = sensorTypeLabel(entry.type),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.sensor_type_format, entry.type),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
    
                                // Actions Row
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Switch(
                                        checked = !entry.hidden,
                                        onCheckedChange = { visible ->
                                            val updated = config.sensorEntries.toMutableList()
                                            updated[index] = entry.copy(hidden = !visible)
                                            config = config.copy(sensorEntries = updated)
                                        }
                                    )
                                    IconButton(
                                        onClick = {
                                            val updated = config.sensorEntries.toMutableList()
                                            updated.removeAt(index)
                                            config = config.copy(sensorEntries = updated)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.sensor_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
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
                entry = SensorEntry(name = stringResource(R.string.sensor_new_default_name), vendor = "", type = 1, isCustom = true),
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

    @Composable
    private fun sensorTypeLabel(type: Int): String = stringResource(
        when (type) {
            Sensor.TYPE_ACCELEROMETER -> R.string.sensor_type_accelerometer
            Sensor.TYPE_MAGNETIC_FIELD -> R.string.sensor_type_magnetic_field
            Sensor.TYPE_ORIENTATION -> R.string.sensor_type_orientation
            Sensor.TYPE_GYROSCOPE -> R.string.sensor_type_gyroscope
            Sensor.TYPE_LIGHT -> R.string.sensor_type_light
            Sensor.TYPE_PRESSURE -> R.string.sensor_type_pressure
            Sensor.TYPE_TEMPERATURE -> R.string.sensor_type_temperature
            Sensor.TYPE_PROXIMITY -> R.string.sensor_type_proximity
            Sensor.TYPE_GRAVITY -> R.string.sensor_type_gravity
            Sensor.TYPE_LINEAR_ACCELERATION -> R.string.sensor_type_linear_acceleration
            Sensor.TYPE_ROTATION_VECTOR -> R.string.sensor_type_rotation_vector
            Sensor.TYPE_RELATIVE_HUMIDITY -> R.string.sensor_type_relative_humidity
            Sensor.TYPE_AMBIENT_TEMPERATURE -> R.string.sensor_type_ambient_temperature
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> R.string.sensor_type_magnetic_field_uncalibrated
            Sensor.TYPE_GAME_ROTATION_VECTOR -> R.string.sensor_type_game_rotation_vector
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> R.string.sensor_type_gyroscope_uncalibrated
            Sensor.TYPE_SIGNIFICANT_MOTION -> R.string.sensor_type_significant_motion
            Sensor.TYPE_STEP_DETECTOR -> R.string.sensor_type_step_detector
            Sensor.TYPE_STEP_COUNTER -> R.string.sensor_type_step_counter
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> R.string.sensor_type_geomagnetic_rotation_vector
            Sensor.TYPE_HEART_RATE -> R.string.sensor_type_heart_rate
            Sensor.TYPE_POSE_6DOF -> R.string.sensor_type_pose_6dof
            Sensor.TYPE_STATIONARY_DETECT -> R.string.sensor_type_stationary_detect
            Sensor.TYPE_MOTION_DETECT -> R.string.sensor_type_motion_detect
            Sensor.TYPE_HEART_BEAT -> R.string.sensor_type_heart_beat
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> R.string.sensor_type_low_latency_offbody_detect
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> R.string.sensor_type_accelerometer_uncalibrated
            Sensor.TYPE_HINGE_ANGLE -> R.string.sensor_type_hinge_angle
            Sensor.TYPE_HEAD_TRACKER -> R.string.sensor_type_head_tracker
            Sensor.TYPE_ACCELEROMETER_LIMITED_AXES -> R.string.sensor_type_accelerometer_limited_axes
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES -> R.string.sensor_type_gyroscope_limited_axes
            Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED -> R.string.sensor_type_accelerometer_limited_axes_uncalibrated
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED -> R.string.sensor_type_gyroscope_limited_axes_uncalibrated
            Sensor.TYPE_HEADING -> R.string.sensor_type_heading
            else -> R.string.sensor_type_generic
        }
    )
}
