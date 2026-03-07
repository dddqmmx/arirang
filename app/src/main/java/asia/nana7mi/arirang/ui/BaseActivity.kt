package asia.nana7mi.arirang.ui

import asia.nana7mi.arirang.data.datastore.AppPreferences
import asia.nana7mi.arirang.util.LocaleHelper
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(base: Context) {
        val lang = AppPreferences.getLanguage(base)
        val context = if (lang.isNullOrEmpty() || lang == "system" || lang == "null") {
            base
        } else {
            LocaleHelper.setLocale(base, lang)
        }
        super.attachBaseContext(context)
    }
}