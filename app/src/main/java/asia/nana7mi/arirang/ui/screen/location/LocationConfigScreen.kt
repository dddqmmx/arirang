package asia.nana7mi.arirang.ui.screen.location

import asia.nana7mi.arirang.ui.component.location.*
import asia.nana7mi.arirang.ui.component.common.ConfigSectionCard
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs.Profile
import asia.nana7mi.arirang.ui.activity.LocationAppConfigActivity
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationConfigScreen(
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
    val latitudeLabel = stringResource(R.string.location_field_latitude)
    val longitudeLabel = stringResource(R.string.location_field_longitude)
    val altitudeLabel = stringResource(R.string.location_field_altitude)
    val accuracyLabel = stringResource(R.string.location_field_accuracy)
    val speedLabel = stringResource(R.string.location_field_speed)
    val bearingLabel = stringResource(R.string.location_field_bearing)
    val satellitesLabel = stringResource(R.string.location_field_satellites)
    val invalidNumberMessage = stringResource(R.string.location_invalid_number)

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

    fun parseProfile(
        latitude: String,
        longitude: String,
        altitude: String,
        accuracy: String,
        speed: String,
        bearing: String,
        satellites: String
    ): Profile? {
        val parsedLatitude = parseDouble(
            latitude,
            latitudeLabel,
            -90.0,
            90.0,
            invalidNumberMessage
        )
        val parsedLongitude = parseDouble(
            longitude,
            longitudeLabel,
            -180.0,
            180.0,
            invalidNumberMessage
        )
        val parsedAltitude = parseDouble(
            altitude,
            altitudeLabel,
            -500.0,
            10000.0,
            invalidNumberMessage
        )
        val parsedAccuracy = parseFloat(
            accuracy,
            accuracyLabel,
            0.1f,
            10000f,
            invalidNumberMessage
        )
        val parsedSpeed = parseFloat(
            speed,
            speedLabel,
            0.0f,
            400f,
            invalidNumberMessage
        )
        val parsedBearing = parseFloat(
            bearing,
            bearingLabel,
            0.0f,
            360f,
            invalidNumberMessage
        )
        val parsedSatellites = parseInt(
            satellites,
            satellitesLabel,
            0,
            64,
            invalidNumberMessage
        )
        val firstError = listOf(
            parsedLatitude,
            parsedLongitude,
            parsedAltitude,
            parsedAccuracy,
            parsedSpeed,
            parsedBearing,
            parsedSatellites
        ).firstOrNull { it.error != null }?.error

        if (firstError != null) {
            validationError = firstError
            return null
        }

        return Profile(
            latitude = parsedLatitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LATITUDE,
            longitude = parsedLongitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
            altitude = parsedAltitude.doubleValue ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
            accuracy = parsedAccuracy.floatValue ?: LocationConfigPrefs.DEFAULT_ACCURACY,
            speed = parsedSpeed.floatValue ?: LocationConfigPrefs.DEFAULT_SPEED,
            bearing = parsedBearing.floatValue ?: LocationConfigPrefs.DEFAULT_BEARING,
            satellites = parsedSatellites.intValue ?: LocationConfigPrefs.DEFAULT_SATELLITES
        )
    }

    fun parsedConfig(): Config? {
        val profile = parseProfile(
            latitudeText,
            longitudeText,
            altitudeText,
            accuracyText,
            speedText,
            bearingText,
            satellitesText
        ) ?: return null

        validationError = null
        return Config(
            enabled = config.enabled,
            latitude = profile.latitude,
            longitude = profile.longitude,
            altitude = profile.altitude,
            accuracy = profile.accuracy,
            speed = profile.speed,
            bearing = profile.bearing,
            satellites = profile.satellites,
            perPackage = LocationConfigPrefs.loadConfig(context).perPackage
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
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(Intent(context, LocationAppConfigActivity::class.java))
                        },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.location_app_config_entry),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.location_app_config_entry_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null
                        )
                    }
                }
            }

            item {
                ConfigSectionCard(title = stringResource(R.string.location_section_coordinates)) {
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
                ConfigSectionCard(title = stringResource(R.string.location_section_motion)) {
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
