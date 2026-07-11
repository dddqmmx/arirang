package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.ui.screen.location.LocationAppConfigScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class LocationAppConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ArirangTheme {
                LocationAppConfigScreen(onBack = { finish() })
            }
        }
    }
}
