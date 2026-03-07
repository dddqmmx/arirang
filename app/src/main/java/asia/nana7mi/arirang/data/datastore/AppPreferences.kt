package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit

object AppPreferences {

    private const val PREFS_NAME = "arirang_prefs"

    private const val KEY_SETUP_COMPLETED = "is_setup_completed"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_REGION = "app_region"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    fun setSetupCompleted(context: Context, isCompleted: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SETUP_COMPLETED, isCompleted)
        }
    }

    fun isSetupCompleted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setLanguage(context: Context, languageCode: String) {
        prefs(context).edit {
            putString(KEY_LANGUAGE, languageCode)
        }
    }

    fun getLanguage(context: Context): String? {
        return prefs(context).getString(KEY_LANGUAGE, null)
    }

    fun setRegion(context: Context, regionCode: String) {
        prefs(context).edit {
            putString(KEY_REGION, regionCode)
        }
    }

    fun getRegion(context: Context): String? {
        return prefs(context).getString(KEY_REGION, null)
    }
}