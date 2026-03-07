package asia.nana7mi.arirang

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import asia.nana7mi.arirang.data.datastore.AppPreferences

class ArirangApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val savedLang = AppPreferences.getLanguage(this)

        if (!savedLang.isNullOrEmpty() && savedLang != "system") {
            val localeList = LocaleListCompat.forLanguageTags(savedLang)
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }
}
