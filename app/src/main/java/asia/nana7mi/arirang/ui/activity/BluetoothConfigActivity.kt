package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.ui.screen.bluetooth.BluetoothConfigScreen

class BluetoothConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { BluetoothConfigPrefs.loadConfig(this) },
            save = { BluetoothConfigPrefs.saveConfig(this, it) },
            screen = { initialConfig, onBack, onSave ->
                BluetoothConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
