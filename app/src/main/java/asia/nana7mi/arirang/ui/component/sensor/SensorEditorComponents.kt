package asia.nana7mi.arirang.ui.component.sensor

import android.hardware.Sensor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs.SensorEntry

@Composable
internal fun SensorEditDialog(
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

// ──────────────────────────────────────────────
// Sensor type label
// ──────────────────────────────────────────────

@Composable
internal fun sensorTypeLabel(type: Int): String = stringResource(
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
