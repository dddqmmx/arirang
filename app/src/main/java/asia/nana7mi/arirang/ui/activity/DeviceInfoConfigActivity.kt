package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.DeviceInfoPrefs
import asia.nana7mi.arirang.ui.screen.device.DeviceInfoConfigScreen

class DeviceInfoConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { DeviceInfoPrefs.loadConfig(this) },
            save = { DeviceInfoPrefs.saveConfig(this, it) },
            rebootRequired = true,
            screen = { initialConfig, onBack, onSave ->
                DeviceInfoConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
