package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs.Config
import asia.nana7mi.arirang.ui.component.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class LocationConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = LocationConfigPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                LocationConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        LocationConfigPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LocationConfigScreen(
        initialConfig: Config,
        onBack: () -> Unit,
        onSave: (Config) -> Unit
    ) {
        var config by remember { mutableStateOf(initialConfig) }
        var savedConfig by remember { mutableStateOf(initialConfig) }
        var latitudeText by remember { mutableStateOf(initialConfig.latitude.toString()) }
        var longitudeText by remember { mutableStateOf(initialConfig.longitude.toString()) }
        var altitudeText by remember { mutableStateOf(initialConfig.altitude.toString()) }
        var accuracyText by remember { mutableStateOf(initialConfig.accuracy.toString()) }
        var speedText by remember { mutableStateOf(initialConfig.speed.toString()) }
        var bearingText by remember { mutableStateOf(initialConfig.bearing.toString()) }
        var satellitesText by remember { mutableStateOf(initialConfig.satellites.toString()) }
        var savedLatitudeText by remember { mutableStateOf(latitudeText) }
        var savedLongitudeText by remember { mutableStateOf(longitudeText) }
        var savedAltitudeText by remember { mutableStateOf(altitudeText) }
        var savedAccuracyText by remember { mutableStateOf(accuracyText) }
        var savedSpeedText by remember { mutableStateOf(speedText) }
        var savedBearingText by remember { mutableStateOf(bearingText) }
        var savedSatellitesText by remember { mutableStateOf(satellitesText) }
        var revision by remember { mutableLongStateOf(0L) }
        var validationError by remember { mutableStateOf<String?>(null) }
        var showUnsavedDialog by remember { mutableStateOf(false) }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        val context = LocalContext.current

        fun applyConfig(updated: Config) {
            config = updated
            latitudeText = updated.latitude.toString()
            longitudeText = updated.longitude.toString()
            altitudeText = updated.altitude.toString()
            accuracyText = updated.accuracy.toString()
            speedText = updated.speed.toString()
            bearingText = updated.bearing.toString()
            satellitesText = updated.satellites.toString()
            validationError = null
            revision++
        }

        fun parsedConfig(): Config? {
            val latitude = parseDouble(
                latitudeText,
                context.getString(R.string.location_field_latitude),
                -90.0,
                90.0,
                context.getString(R.string.location_invalid_number)
            )
            val longitude = parseDouble(
                longitudeText,
                context.getString(R.string.location_field_longitude),
                -180.0,
                180.0,
                context.getString(R.string.location_invalid_number)
            )
            val altitude = parseDouble(
                altitudeText,
                context.getString(R.string.location_field_altitude),
                -500.0,
                10000.0,
                context.getString(R.string.location_invalid_number)
            )
            val accuracy = parseFloat(
                accuracyText,
                context.getString(R.string.location_field_accuracy),
                0.1f,
                10000f,
                context.getString(R.string.location_invalid_number)
            )
            val speed = parseFloat(
                speedText,
                context.getString(R.string.location_field_speed),
                0.0f,
                400f,
                context.getString(R.string.location_invalid_number)
            )
            val bearing = parseFloat(
                bearingText,
                context.getString(R.string.location_field_bearing),
                0.0f,
                360f,
                context.getString(R.string.location_invalid_number)
            )
            val satellites = parseInt(
                satellitesText,
                context.getString(R.string.location_field_satellites),
                0,
                64,
                context.getString(R.string.location_invalid_number)
            )
            val firstError = listOf(latitude, longitude, altitude, accuracy, speed, bearing, satellites)
                .firstOrNull { it.error != null }
                ?.error

            if (firstError != null) {
                validationError = firstError
                return null
            }

            validationError = null
            return Config(
                enabled = config.enabled,
                latitude = latitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LATITUDE,
                longitude = longitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
                altitude = altitude.doubleValue ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
                accuracy = accuracy.floatValue ?: LocationConfigPrefs.DEFAULT_ACCURACY,
                speed = speed.floatValue ?: LocationConfigPrefs.DEFAULT_SPEED,
                bearing = bearing.floatValue ?: LocationConfigPrefs.DEFAULT_BEARING,
                satellites = satellites.intValue ?: LocationConfigPrefs.DEFAULT_SATELLITES
            )
        }

        fun saveCurrent(): Boolean {
            val current = parsedConfig() ?: return false
            onSave(current)
            config = current
            savedConfig = current
            savedLatitudeText = latitudeText
            savedLongitudeText = longitudeText
            savedAltitudeText = altitudeText
            savedAccuracyText = accuracyText
            savedSpeedText = speedText
            savedBearingText = bearingText
            savedSatellitesText = satellitesText
            return true
        }

        val hasChanges = config.enabled != savedConfig.enabled ||
            latitudeText != savedLatitudeText ||
            longitudeText != savedLongitudeText ||
            altitudeText != savedAltitudeText ||
            accuracyText != savedAccuracyText ||
            speedText != savedSpeedText ||
            bearingText != savedBearingText ||
            satellitesText != savedSatellitesText

        fun requestBack() {
            if (hasChanges) {
                showUnsavedDialog = true
            } else {
                onBack()
            }
        }

        BackHandler { requestBack() }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.location_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { applyConfig(Config(enabled = true)) }) {
                            Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.location_default_pyongyang))
                        }
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
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .toggleable(
                                    value = config.enabled,
                                    onValueChange = { config = config.copy(enabled = it) },
                                    role = Role.Switch
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.location_hook_enabled),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.location_hook_enabled_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = config.enabled, onCheckedChange = null)
                        }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.location_section_coordinates)) {
                        LocationTextField(
                            label = stringResource(R.string.location_field_latitude),
                            value = latitudeText,
                            revision = revision,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { latitudeText = it }
                        )
                        LocationTextField(
                            label = stringResource(R.string.location_field_longitude),
                            value = longitudeText,
                            revision = revision,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { longitudeText = it }
                        )
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.location_section_motion)) {
                        LocationTextField(
                            label = stringResource(R.string.location_field_altitude),
                            value = altitudeText,
                            revision = revision,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { altitudeText = it }
                        )
                        LocationTextField(
                            label = stringResource(R.string.location_field_accuracy),
                            value = accuracyText,
                            revision = revision,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { accuracyText = it }
                        )
                        LocationTextField(
                            label = stringResource(R.string.location_field_speed),
                            value = speedText,
                            revision = revision,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { speedText = it }
                        )
                        LocationTextField(
                            label = stringResource(R.string.location_field_bearing),
                            value = bearingText,
                            revision = revision,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { bearingText = it }
                        )
                        LocationTextField(
                            label = stringResource(R.string.location_field_satellites),
                            value = satellitesText,
                            revision = revision,
                            keyboardType = KeyboardType.Number,
                            onValueChange = { satellitesText = it.filter(Char::isDigit) }
                        )
                    }
                }

                validationError?.let { error ->
                    item {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        if (showUnsavedDialog) {
            UnsavedChangesDialog(
                onDismiss = { showUnsavedDialog = false },
                onDiscard = {
                    showUnsavedDialog = false
                    onBack()
                },
                onSave = {
                    if (saveCurrent()) {
                        showUnsavedDialog = false
                        onBack()
                    }
                }
            )
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                content()
            }
        }
    }

    @Composable
    private fun LocationTextField(
        label: String,
        value: String,
        revision: Long,
        keyboardType: KeyboardType,
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
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    private fun parseDouble(text: String, label: String, min: Double, max: Double, errorFormat: String): ParsedValue {
        val value = text.trim().toDoubleOrNull()
        return if (value == null || value !in min..max) {
            ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
        } else {
            ParsedValue(doubleValue = value)
        }
    }

    private fun parseFloat(text: String, label: String, min: Float, max: Float, errorFormat: String): ParsedValue {
        val value = text.trim().toFloatOrNull()
        return if (value == null || value < min || value > max) {
            ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
        } else {
            ParsedValue(floatValue = value)
        }
    }

    private fun parseInt(text: String, label: String, min: Int, max: Int, errorFormat: String): ParsedValue {
        val value = text.trim().toIntOrNull()
        return if (value == null || value !in min..max) {
            ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
        } else {
            ParsedValue(intValue = value)
        }
    }

    private data class ParsedValue(
        val doubleValue: Double? = null,
        val floatValue: Float? = null,
        val intValue: Int? = null,
        val error: String? = null
    )
}
