package asia.nana7mi.arirang.ui.activity

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs.SensorEntry
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class SensorConfigActivity : ComponentActivity() {

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
        val context = LocalContext.current
        var config by remember { mutableStateOf(initialConfig) }
        var savedConfig by remember { mutableStateOf(initialConfig) }
        var showUnsavedDialog by remember { mutableStateOf(false) }

        val listLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Reload config when returning from the list activity to sync any changes made there
            val newConfig = SensorConfigPrefs.loadConfig(context)
            config = newConfig
            savedConfig = newConfig
        }

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

                // ── Sensor List Navigation ──
                item(key = "sensor_list_nav") {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (hasChanges) {
                                    // Optionally we could save or prompt, but for now we just launch.
                                    // To be safe, we can save the current changes first.
                                    saveCurrent()
                                }
                                listLauncher.launch(Intent(context, SensorListActivity::class.java))
                            },
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.sensor_section_list),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.sensor_list_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── Precision ──
                item(key = "precision") {
                    SectionCard(title = stringResource(R.string.sensor_section_precision)) {
                        Text(stringResource(R.string.sensor_precision_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val sensorTypes = listOf(
                            android.hardware.Sensor.TYPE_ACCELEROMETER to stringResource(R.string.sensor_type_accelerometer),
                            android.hardware.Sensor.TYPE_GYROSCOPE to stringResource(R.string.sensor_type_gyroscope),
                            android.hardware.Sensor.TYPE_MAGNETIC_FIELD to stringResource(R.string.sensor_type_magnetic_field),
                            android.hardware.Sensor.TYPE_GRAVITY to stringResource(R.string.sensor_type_gravity),
                            android.hardware.Sensor.TYPE_LINEAR_ACCELERATION to stringResource(R.string.sensor_type_linear_acceleration)
                        )
                        
                        sensorTypes.forEach { (type, name) ->
                            val currentLevel = config.precisionBySensorType[type] ?: SensorConfigPrefs.PRECISION_ORIGINAL
                            PrecisionDropdown(name, currentLevel) { newLevel ->
                                val updatedMap = config.precisionBySensorType.toMutableMap()
                                updatedMap[type] = newLevel
                                config = config.copy(precisionBySensorType = updatedMap)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
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
    }

    // ──────────────────────────────────────────────
    // Composable helpers
    // ──────────────────────────────────────────────

    @Composable
    private fun PrecisionDropdown(labelStr: String, selectedLevel: Int, onLevelSelected: (Int) -> Unit) {
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
}
