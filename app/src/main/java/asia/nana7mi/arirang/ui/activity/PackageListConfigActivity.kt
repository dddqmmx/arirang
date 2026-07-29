package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.ui.screen.packagelist.PackageListConfigScreen
import asia.nana7mi.arirang.ui.theme.ArirangTheme

/**
 * Extends [BaseActivity] rather than ComponentActivity: its `attachBaseContext`
 * applies the in-app language override, which this screen's strings need.
 */
class PackageListConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ArirangTheme {
                PackageListConfigScreen(onBack = { finish() })
            }
        }
    }
}
