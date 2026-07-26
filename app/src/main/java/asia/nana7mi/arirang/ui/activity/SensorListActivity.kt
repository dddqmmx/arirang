package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.ui.screen.sensor.SensorListScreen

class SensorListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { SensorConfigPrefs.loadConfig(this) },
            save = { SensorConfigPrefs.saveConfig(this, it) },
            rebootRequired = true,
            screen = { initialConfig, onBack, onSave ->
                SensorListScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
