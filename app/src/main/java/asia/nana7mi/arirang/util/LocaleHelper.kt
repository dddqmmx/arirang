package asia.nana7mi.arirang.util

import android.content.Context
import android.content.res.Configuration
import java.util.*

object LocaleHelper {
    fun setLocale(context: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}