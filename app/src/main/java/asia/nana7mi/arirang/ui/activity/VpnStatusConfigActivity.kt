package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs
import asia.nana7mi.arirang.ui.screen.vpn.VpnStatusConfigScreen

/**
 * Extends [BaseActivity] rather than ComponentActivity: its `attachBaseContext`
 * applies the in-app language override this screen's strings need.
 */
class VpnStatusConfigActivity : BaseActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { VpnStatusPrefs.loadConfig(this) },
            save = { VpnStatusPrefs.saveConfig(this, it) },
            screen = { initialConfig, onBack, onSave ->
                VpnStatusConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
