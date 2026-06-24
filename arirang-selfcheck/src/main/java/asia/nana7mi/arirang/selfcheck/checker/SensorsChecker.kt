package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SensorsChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_sensors_title
    override val navChipId: Int = R.id.navSensorsChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val sensorManager = context.getSystemService(SensorManager::class.java)
            val sensors = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            val values = sensors.take(20).map { sensor ->
                "${sensor.name} | ${sensor.vendor}"
            }
            CheckResult(
                if (values.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                context.resources.getQuantityString(R.plurals.self_check_sensors_status, sensors.size, sensors.size),
                if (values.isEmpty()) context.getString(R.string.self_check_sensors_hidden) else values.joinToString("\n")
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
