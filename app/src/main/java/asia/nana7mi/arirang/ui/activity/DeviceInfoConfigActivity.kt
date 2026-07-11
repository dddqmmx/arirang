package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.DeviceInfoPrefs
import asia.nana7mi.arirang.ui.screen.device.DeviceInfoConfigScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class DeviceInfoConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = DeviceInfoPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                DeviceInfoConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        DeviceInfoPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success_reboot_required), Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}
