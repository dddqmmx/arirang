package asia.nana7mi.arirang.ui.activity

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.fragment.HomeFragment
import asia.nana7mi.arirang.ui.fragment.SettingsFragment
import asia.nana7mi.arirang.ui.fragment.AboutFragment
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()

        setContentView(R.layout.main)

        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener { item ->
            val selectedFragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_settings -> SettingsFragment()
                R.id.nav_about -> AboutFragment()
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
