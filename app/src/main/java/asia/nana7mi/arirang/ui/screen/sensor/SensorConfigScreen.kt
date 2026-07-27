package asia.nana7mi.arirang.ui.screen.sensor

import asia.nana7mi.arirang.ui.component.common.ConfigScreenScaffold
import asia.nana7mi.arirang.ui.component.sensor.*
import asia.nana7mi.arirang.ui.component.common.SensorSwitchRow
import asia.nana7mi.arirang.ui.component.common.ConfigSectionCard
import android.content.Intent
import android.hardware.Sensor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.ui.activity.SensorListActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensorConfigScreen(
    initialConfig: SensorConfigPrefs.Config,
    onBack: () -> Unit,
    onSave: (SensorConfigPrefs.Config) -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(initialConfig) }
    var savedConfig by remember { mutableStateOf(initialConfig) }

    val listLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Reload config when returning from the list activity to sync any changes made there
        val newConfig = SensorConfigPrefs.loadConfig(context)
        config = newConfig
        savedConfig = newConfig
    }

    fun saveCurrent(): Boolean { onSave(config); savedConfig = config; return true }
    val hasChanges = config != savedConfig

    ConfigScreenScaffold(
        title = stringResource(R.string.sensor_config_title),
        hasChanges = hasChanges,
        onSave = { saveCurrent() },
        onBack = onBack
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
                        SensorSwitchRow(
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
                ConfigSectionCard(title = stringResource(R.string.sensor_section_precision)) {
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

}

// ──────────────────────────────────────────────
// Composable helpers
// ──────────────────────────────────────────────
