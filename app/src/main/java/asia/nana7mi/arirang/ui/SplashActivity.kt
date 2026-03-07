// 新建文件: SplashActivity.kt
package asia.nana7mi.arirang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import asia.nana7mi.arirang.data.datastore.AppPreferences

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppPreferences.isSetupCompleted(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, InitActivity::class.java))
        }
        finish()
    }
}