package asia.nana7mi.arirang.ui.activity

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs.SensorEntry
import asia.nana7mi.arirang.ui.screen.sensor.SensorConfigScreen
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
}
