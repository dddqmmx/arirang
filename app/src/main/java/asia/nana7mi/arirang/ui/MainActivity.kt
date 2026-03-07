package asia.nana7mi.arirang.ui

import android.content.Intent
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.fragment.HomeFragment
import asia.nana7mi.arirang.ui.fragment.SettingsFragment
import asia.nana7mi.arirang.ui.fragment.UserFragment
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.util.concurrent.ServiceManager
import java.util.Locale

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(intent)
        }

        setContentView(R.layout.main)

        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener { item ->
            val selectedFragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_settings -> SettingsFragment()
                R.id.nav_user -> UserFragment()
                else -> null
            }
            selectedFragment?.let {
                supportFragmentManager.beginTransaction().replace(R.id.container, it).commit()
                true
            } == true
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(
                R.id.container,
                HomeFragment()
            ).commit()
        }
    }


}
