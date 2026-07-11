package asia.nana7mi.arirang.ui.component.location

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs.Profile

@Composable
internal fun AppLocationRow(
    app: AppEntry,
    profile: Profile?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            app.icon?.let { icon ->
                Image(
                    bitmap = remember(app.packageName) { icon.toBitmap().asImageBitmap() },
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = profile?.let {
                        stringResource(
                            R.string.location_app_configured,
                            it.latitude.toString(),
                            it.longitude.toString()
                        )
                    } ?: stringResource(R.string.location_app_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (profile == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.location_remove_app_profile)
                    )
                }
            }
        }
    }
}

@Composable
internal fun EditProfileDialog(
    app: AppEntry,
    profile: Profile?,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: (() -> Unit)?
) {
    var enabled by remember(app.packageName) { mutableStateOf(profile?.enabled ?: false) }
    var latitudeText by remember(app.packageName) {
        mutableStateOf((profile?.latitude ?: LocationConfigPrefs.DEFAULT_LATITUDE).toString())
    }
    var longitudeText by remember(app.packageName) {
        mutableStateOf((profile?.longitude ?: LocationConfigPrefs.DEFAULT_LONGITUDE).toString())
    }
    var altitudeText by remember(app.packageName) {
        mutableStateOf((profile?.altitude ?: LocationConfigPrefs.DEFAULT_ALTITUDE).toString())
    }
    var accuracyText by remember(app.packageName) {
        mutableStateOf((profile?.accuracy ?: LocationConfigPrefs.DEFAULT_ACCURACY).toString())
    }
    var speedText by remember(app.packageName) {
        mutableStateOf((profile?.speed ?: LocationConfigPrefs.DEFAULT_SPEED).toString())
    }
    var bearingText by remember(app.packageName) {
        mutableStateOf((profile?.bearing ?: LocationConfigPrefs.DEFAULT_BEARING).toString())
    }
    var satellitesText by remember(app.packageName) {
        mutableStateOf((profile?.satellites ?: LocationConfigPrefs.DEFAULT_SATELLITES).toString())
    }
    var revision by remember(app.packageName) { mutableLongStateOf(0L) }
    var validationError by remember(app.packageName) { mutableStateOf<String?>(null) }
    val latitudeLabel = stringResource(R.string.location_field_latitude)
    val longitudeLabel = stringResource(R.string.location_field_longitude)
    val altitudeLabel = stringResource(R.string.location_field_altitude)
    val accuracyLabel = stringResource(R.string.location_field_accuracy)
    val speedLabel = stringResource(R.string.location_field_speed)
    val bearingLabel = stringResource(R.string.location_field_bearing)
    val satellitesLabel = stringResource(R.string.location_field_satellites)
    val invalidNumberMessage = stringResource(R.string.location_invalid_number)

    fun parseProfile(): Profile? {
        val parsedLatitude = parseDouble(
            latitudeText,
            latitudeLabel,
            -90.0,
            90.0,
            invalidNumberMessage
        )
        val parsedLongitude = parseDouble(
            longitudeText,
            longitudeLabel,
            -180.0,
            180.0,
            invalidNumberMessage
        )
        val parsedAltitude = parseDouble(
            altitudeText,
            altitudeLabel,
            -500.0,
            10000.0,
            invalidNumberMessage
        )
        val parsedAccuracy = parseFloat(
            accuracyText,
            accuracyLabel,
            0.1f,
            10000f,
            invalidNumberMessage
        )
        val parsedSpeed = parseFloat(
            speedText,
            speedLabel,
            0.0f,
            400f,
            invalidNumberMessage
        )
        val parsedBearing = parseFloat(
            bearingText,
            bearingLabel,
            0.0f,
            360f,
            invalidNumberMessage
        )
        val parsedSatellites = parseInt(
            satellitesText,
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

        validationError = null
        return Profile(
            enabled = enabled,
            latitude = parsedLatitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LATITUDE,
            longitude = parsedLongitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
            altitude = parsedAltitude.doubleValue ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
            accuracy = parsedAccuracy.floatValue ?: LocationConfigPrefs.DEFAULT_ACCURACY,
            speed = parsedSpeed.floatValue ?: LocationConfigPrefs.DEFAULT_SPEED,
            bearing = parsedBearing.floatValue ?: LocationConfigPrefs.DEFAULT_BEARING,
            satellites = parsedSatellites.intValue ?: LocationConfigPrefs.DEFAULT_SATELLITES
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.icon?.let { icon ->
                    Image(
                        bitmap = remember(app.packageName) { icon.toBitmap().asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column {
                    Text(stringResource(R.string.location_edit_app_location))
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = enabled,
                            onValueChange = { enabled = it },
                            role = androidx.compose.ui.semantics.Role.Switch
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.location_hook_enabled),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(checked = enabled, onCheckedChange = null)
                }

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
                validationError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                parseProfile()?.let(onSave)
                revision++
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text(stringResource(R.string.location_remove_app_profile))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )
}

private fun parseDouble(text: String, label: String, min: Double, max: Double, errorFormat: String): AppLocationParsedValue {
    val value = text.trim().toDoubleOrNull()
    return if (value == null || value !in min..max) {
        AppLocationParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
    } else {
        AppLocationParsedValue(doubleValue = value)
    }
}

private fun parseFloat(text: String, label: String, min: Float, max: Float, errorFormat: String): AppLocationParsedValue {
    val value = text.trim().toFloatOrNull()
    return if (value == null || value !in min..max) {
        AppLocationParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
    } else {
        AppLocationParsedValue(floatValue = value)
    }
}

private fun parseInt(text: String, label: String, min: Int, max: Int, errorFormat: String): AppLocationParsedValue {
    val value = text.trim().toIntOrNull()
    return if (value == null || value !in min..max) {
        AppLocationParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
    } else {
        AppLocationParsedValue(intValue = value)
    }
}

private data class AppLocationParsedValue(
    val doubleValue: Double? = null,
    val floatValue: Float? = null,
    val intValue: Int? = null,
    val error: String? = null
)

internal data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystemApp: Boolean,
    val icon: android.graphics.drawable.Drawable? = null
)

internal enum class AppTypeFilter {
    USER,
    SYSTEM,
    ALL
}
