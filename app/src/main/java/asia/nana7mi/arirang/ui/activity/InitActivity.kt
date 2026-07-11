package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import asia.nana7mi.arirang.ui.screen.init.InitScreen

class InitActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InitScreen()
        }
    }
}
