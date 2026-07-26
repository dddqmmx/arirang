package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.ui.screen.identifier.UniqueIdentifierConfigScreen

class UniqueIdentifierConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { UniqueIdentifierPrefs.loadConfig(this) },
            save = { UniqueIdentifierPrefs.saveConfig(this, it) },
            rebootRequired = true,
            screen = { initialConfig, onBack, onSave ->
                UniqueIdentifierConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
