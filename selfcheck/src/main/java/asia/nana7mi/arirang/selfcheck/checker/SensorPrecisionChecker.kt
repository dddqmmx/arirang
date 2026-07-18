package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import asia.nana7mi.arirang.selfcheck.BuildConfig
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SensorPrecisionChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_sensor_precision_title
    override val navChipId: Int = R.id.navSensorPrecisionChip

    private data class SensorPrecisionData(
        val name: String,
        val values: FloatArray,
        val decimalPlaces: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SensorPrecisionData) return false
            return name == other.name && values.contentEquals(other.values) && decimalPlaces == other.decimalPlaces
        }
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + values.contentHashCode()
            result = 31 * result + decimalPlaces
            return result
        }
    }

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val mgr = context.getSystemService(SensorManager::class.java)
            if (mgr == null) {
                return@withContext CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_supported), "No SensorManager")
            }

            val results = mutableListOf<SensorPrecisionData>()
            var anySensor = false

            for ((type, name) in CheckDefinitions.SENSOR_TYPES) {
                val sensor = mgr.getDefaultSensor(type) ?: continue
                anySensor = true
                val data = readSensorData(mgr, sensor)
                if (data != null) {
                    results.add(SensorPrecisionData(name, data, analyzeDecimalPrecision(data)))
                }
            }

            if (!anySensor) {
                return@withContext CheckResult(
                    CheckState.BLOCKED,
                    context.getString(R.string.self_check_status_not_supported),
                    context.getString(R.string.self_check_sensor_precision_no_sensor)
                )
            }
            if (results.isEmpty()) {
                return@withContext CheckResult(
                    CheckState.BLOCKED,
                    context.getString(R.string.self_check_status_not_visible),
                    context.getString(R.string.self_check_sensor_precision_no_sensor)
                )
            }

            val maxDecimalPlaces = results.maxOf { it.decimalPlaces }
            val configLevel = readSensorPrecisionConfig(context)

            val content = buildString {
                for (r in results) {
                    appendLine("${r.name}:")
                    appendLine("  X: ${r.values[0]}  Y: ${r.values[1]}  Z: ${r.values[2]}")
                    appendLine("  → ${r.decimalPlaces} decimal place(s)")
                }
                appendLine("Worst case: $maxDecimalPlaces decimal place(s)")
                if (configLevel > 0) {
                    appendLine("Configured level: $configLevel")
                }
            }

            when {
                configLevel > 0 && maxDecimalPlaces <= configLevel -> {
                    CheckResult(
                        CheckState.BLOCKED,
                        context.getString(R.string.self_check_sensor_precision_reduced, maxDecimalPlaces),
                        content
                    )
                }
                configLevel > 0 && maxDecimalPlaces > configLevel -> {
                    CheckResult(
                        CheckState.LEAKED,
                        context.getString(R.string.self_check_sensor_precision_config_mismatch, configLevel, maxDecimalPlaces),
                        content
                    )
                }
                else -> {
                    CheckResult(
                        CheckState.VISIBLE,
                        context.getString(R.string.self_check_sensor_precision_original),
                        content
                    )
                }
            }
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    private fun readSensorData(sensorManager: SensorManager, sensor: Sensor): FloatArray? {
        val latch = CountDownLatch(1)
        var data: FloatArray? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                data = floatArrayOf(event.values[0], event.values[1], event.values[2])
                latch.countDown()
                sensorManager.unregisterListener(this)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        val received = latch.await(2, TimeUnit.SECONDS)
        sensorManager.unregisterListener(listener)

        return if (received) data else null
    }

    private fun analyzeDecimalPrecision(data: FloatArray): Int {
        var maxDecimalPlaces = 0
        for (value in data) {
            val str = value.toString()
            val dotIndex = str.indexOf('.')
            if (dotIndex >= 0) {
                val decimalPart = str.substring(dotIndex + 1)
                val nonZeroLength = decimalPart.trimEnd('0').length
                maxDecimalPlaces = maxOf(maxDecimalPlaces, nonZeroLength)
            }
        }
        return maxDecimalPlaces
    }

    private fun readSensorPrecisionConfig(context: Context): Int {
        val fromPrefs = try {
            val prefs = context.getSharedPreferences("sensor_config_prefs", Context.MODE_PRIVATE)
            val precisionJson = prefs.getString("precision_by_sensor_type", null)
            if (precisionJson == null) 0 else {
                val json = org.json.JSONObject(precisionJson)
                json.keys().asSequence().maxOfOrNull { json.getInt(it as String) } ?: 0
            }
        } catch (_: Exception) { 0 }
        if (fromPrefs > 0) return fromPrefs

        return try {
            val targetPkg = BuildConfig.TARGET_PACKAGE_NAME
            val configDir = BuildConfig.SUBMODULE_CONFIG_DIR
            val configFile = BuildConfig.SUBMODULE_CONFIG_FILE
            val file = File("/data/user_de/0/$targetPkg/files/$configDir/$configFile")
            if (!file.canRead()) return 0
            val json = org.json.JSONObject(file.readText())
            val rules = json.optJSONArray("sensorPrecisionRules") ?: return 0
            var maxLevel = 0
            for (i in 0 until rules.length()) {
                val level = rules.getJSONObject(i).optInt("level", 0)
                if (level > maxLevel) maxLevel = level
            }
            maxLevel
        } catch (_: Exception) { 0 }
    }
}
