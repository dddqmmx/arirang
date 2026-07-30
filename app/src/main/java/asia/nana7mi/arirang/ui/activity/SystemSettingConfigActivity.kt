package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.ui.screen.systemsetting.SystemSettingConfigScreen

class SystemSettingConfigActivity : BaseActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setConfigScreenContent(
            load = { SystemSettingPrefs.loadConfig(this) },
            save = { SystemSettingPrefs.saveConfig(this, it) },
            screen = { initialConfig, onBack, onSave ->
                SystemSettingConfigScreen(initialConfig, onBack, onSave)
            }
        )
    }
}
