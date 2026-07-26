package asia.nana7mi.arirang.ui.activity

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs.SensorEntry
import asia.nana7mi.arirang.ui.screen.sensor.SensorConfigScreen

class SensorConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { seedFromDeviceIfEmpty(SensorConfigPrefs.loadConfig(this)) },
            save = { SensorConfigPrefs.saveConfig(this, it) },
            rebootRequired = true,
            screen = { initialConfig, onBack, onSave ->
                SensorConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }

    /** On first launch there are no saved entries, so seed them from the device. */
    private fun seedFromDeviceIfEmpty(config: SensorConfigPrefs.Config): SensorConfigPrefs.Config {
        if (config.sensorEntries.isNotEmpty()) return config

        val sensorManager = getSystemService(SensorManager::class.java)
        val deviceEntries = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            .map { SensorEntry(name = it.name, vendor = it.vendor, type = it.type) }
            .distinctBy { it.type }
            .sortedBy { it.type }
        return config.copy(sensorEntries = deviceEntries)
    }
}
