package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.ui.screen.wifi.WifiConfigScreen

class WifiConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { WifiConfigPrefs.loadConfig(this) },
            save = { WifiConfigPrefs.saveConfig(this, it) },
            screen = { initialConfig, onBack, onSave ->
                WifiConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
