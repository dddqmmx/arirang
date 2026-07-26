package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.ui.screen.location.LocationConfigScreen

class LocationConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { LocationConfigPrefs.loadConfig(this) },
            save = { LocationConfigPrefs.saveConfig(this, it) },
            screen = { initialConfig, onBack, onSave ->
                LocationConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
