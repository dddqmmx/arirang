package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.ui.screen.location.LocationConfigScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class LocationConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = LocationConfigPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                LocationConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        LocationConfigPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
