package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.ui.screen.advanced.AdvancedSettingsScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class AdvancedSettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArirangTheme {
                AdvancedSettingsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}
