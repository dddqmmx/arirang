package asia.nana7mi.arirang

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import asia.nana7mi.arirang.data.datastore.AppPreferences
import asia.nana7mi.arirang.data.datastore.SubmoduleConfigFiles
import asia.nana7mi.arirang.data.sync.RemotePreferencesSyncManager
import com.google.android.material.color.DynamicColors

class ArirangApp : Application() {

    override fun onCreate() {
        super.onCreate()

        RemotePreferencesSyncManager.init(this)

        val savedLang = AppPreferences.getLanguage(this)

        if (!savedLang.isNullOrEmpty() && savedLang != "system") {
            val localeList = LocaleListCompat.forLanguageTags(savedLang)
            AppCompatDelegate.setApplicationLocales(localeList)
        }

        DynamicColors.applyToActivitiesIfAvailable(this)
        runCatching { SubmoduleConfigFiles.write(this) }
    }
}
