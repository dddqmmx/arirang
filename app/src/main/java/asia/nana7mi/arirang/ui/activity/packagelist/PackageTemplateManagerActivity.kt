package asia.nana7mi.arirang.ui.activity.packagelist

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.ui.activity.BaseActivity
import asia.nana7mi.arirang.ui.screen.packagelist.PackageTemplateManagerScreen
import asia.nana7mi.arirang.ui.theme.ArirangTheme

class PackageTemplateManagerActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ArirangTheme {
                PackageTemplateManagerScreen(onBack = { finish() })
            }
        }
    }
}
