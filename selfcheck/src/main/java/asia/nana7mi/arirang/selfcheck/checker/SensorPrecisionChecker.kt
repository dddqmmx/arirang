package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import asia.nana7mi.arirang.selfcheck.BuildConfig
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How many decimal places the sensor data arriving in this process actually carries, against what
 * the submodule was configured to reduce them to.
 *
 * The only [continuous] check: its section is a **live** reading rather than a snapshot, so the
 * flow emits the settled first reading and then keeps emitting at [LIVE_INTERVAL_MS] until the
 * collector stops. That keeps the sampling in one place instead of two — this same measurement
 * used to be written once as a check and once inside the activity for the live monitor, which is
 * why the two could disagree about what a level meant.
 *
 * Listeners are released in the [channelFlow]'s `finally`, so a section that is no longer being
 * watched — a run that took only its first result, a monitor the user stopped, an activity that
 * went away — costs the sensor service nothing.
 */
class SensorPrecisionChecker : Check {

    override val titleRes: Int = R.string.self_check_sensor_precision_title
    override val navLabelRes: Int = R.string.self_check_nav_sensor_precision
    override val continuous: Boolean = true

    override fun run(context: Context): Flow<CheckResult> = channelFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        if (manager == null) {
            send(
                CheckResult(
                    CheckState.BLOCKED,
                    context.getString(R.string.self_check_status_not_supported),
                    "No SensorManager"
                )
            )
            return@channelFlow
        }

        val watched = CheckDefinitions.SENSOR_TYPES.filter { manager.getDefaultSensor(it.key) != null }
        if (watched.isEmpty()) {
            send(noSensor(context))
            return@channelFlow
        }

        val latest = ConcurrentHashMap<Int, FloatArray>()
        val listeners = watched.entries.associate { (type, _) ->
            type to object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    latest[type] = event.values.copyOf(3)
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
            }
        }

        try {
            listeners.forEach { (type, listener) ->
                manager.registerListener(listener, manager.getDefaultSensor(type), SensorManager.SENSOR_DELAY_NORMAL)
            }
            val configuredLevel = withContext(Dispatchers.IO) { readConfiguredLevel(context) }

            // The settled reading: every watched sensor answered, or the bound says reporting what
            // there is beats waiting longer. A run takes this one and stops here.
            withTimeoutOrNull(FIRST_READING_TIMEOUT_MS) {
                while (latest.size < watched.size) delay(POLL_INTERVAL_MS)
            }
            send(read(context, watched, latest, configuredLevel))

            while (true) {
                delay(LIVE_INTERVAL_MS)
                send(read(context, watched, latest, configuredLevel))
            }
        } finally {
            listeners.values.forEach { manager.unregisterListener(it) }
        }
    }

    private fun read(
        context: Context,
        watched: Map<Int, String>,
        latest: Map<Int, FloatArray>,
        configuredLevel: Int,
    ): CheckResult {
        val readings = watched.mapNotNull { (type, name) -> latest[type]?.let { name to it } }
        if (readings.isEmpty()) {
            return CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_status_not_visible),
                context.getString(R.string.self_check_sensor_precision_no_sensor)
            )
        }

        val worst = readings.maxOf { decimalPlaces(it.second) }
        val content = buildString {
            for ((name, values) in readings) {
                appendLine("$name:")
                appendLine("  X: ${values[0]}  Y: ${values[1]}  Z: ${values[2]}")
                appendLine("  → ${decimalPlaces(values)} decimal place(s)")
            }
            appendLine(context.getString(R.string.self_check_sensor_precision_worst, worst))
            if (configuredLevel > 0) {
                appendLine(context.getString(R.string.self_check_sensor_precision_configured, configuredLevel))
            }
        }

        return when {
            configuredLevel > 0 && worst <= configuredLevel -> CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_sensor_precision_reduced, worst),
                content
            )

            configuredLevel > 0 -> CheckResult(
                CheckState.LEAKED,
                context.getString(R.string.self_check_sensor_precision_config_mismatch, configuredLevel, worst),
                content
            )

            else -> CheckResult(
                CheckState.VISIBLE,
                context.getString(R.string.self_check_sensor_precision_original),
                content
            )
        }
    }

    /**
     * The decimal places [values] carries, counted after trailing zeroes are dropped so that a
     * value the device rounded on purpose (`9.800000`) is not read as six places of precision.
     */
    private fun decimalPlaces(values: FloatArray): Int = values.maxOf { value ->
        val text = value.toString()
        val dot = text.indexOf('.')
        if (dot < 0) 0 else text.substring(dot + 1).trimEnd('0').length
    }

    /**
     * The worst level the submodule config asks for, or 0 when none is configured or none is
     * readable — the app's own prefs first, then the config file the submodule writes.
     */
    private fun readConfiguredLevel(context: Context): Int {
        val fromPrefs = runCatching {
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_KEY, null)
                ?.let { org.json.JSONObject(it) }
                ?: return@runCatching 0
            json.keys().asSequence().maxOfOrNull { json.getInt(it as String) } ?: 0
        }.getOrDefault(0)
        if (fromPrefs > 0) return fromPrefs

        return runCatching {
            val file = File(
                "/data/user_de/0/${BuildConfig.TARGET_PACKAGE_NAME}/files/" +
                    "${BuildConfig.SUBMODULE_CONFIG_DIR}/${BuildConfig.SUBMODULE_CONFIG_FILE}"
            )
            if (!file.canRead()) return@runCatching 0
            val rules = org.json.JSONObject(file.readText()).optJSONArray("sensorPrecisionRules")
                ?: return@runCatching 0
            (0 until rules.length()).maxOfOrNull { rules.getJSONObject(it).optInt("level", 0) } ?: 0
        }.getOrDefault(0)
    }

    private fun noSensor(context: Context) = CheckResult(
        CheckState.BLOCKED,
        context.getString(R.string.self_check_status_not_supported),
        context.getString(R.string.self_check_sensor_precision_no_sensor)
    )

    private companion object {
        const val PREFS_NAME = "sensor_config_prefs"
        const val PREFS_KEY = "precision_by_sensor_type"

        /** How long the settled first reading waits for every watched sensor to answer. */
        const val FIRST_READING_TIMEOUT_MS = 2000L

        /** How often that wait re-checks, and how often the reading is re-sent while live. */
        const val POLL_INTERVAL_MS = 50L
        const val LIVE_INTERVAL_MS = 500L
    }
}
