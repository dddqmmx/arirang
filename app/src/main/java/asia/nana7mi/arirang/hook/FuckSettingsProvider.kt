package asia.nana7mi.arirang.hook

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

// Android ID is handled at SettingsProvider so apps receive the rewritten value
// through the normal Settings.Secure path instead of per-app hooks.
class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

    private companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ANDROID_ID = "android_id"
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        if (lpparam.packageName != "com.android.providers.settings") return

        try {
            val lmsClass = XposedHelpers.findClass(
                "com.android.providers.settings.SettingsProvider",
                classLoader
            )
            hookCall(lmsClass)

        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.SETTINGS, "hook failed", t)
        }
    }

    private fun hookCall(lmsClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            lmsClass, "call",
            String::class.java,
            String::class.java,
            Bundle::class.java,
            beforeHookedMethod {
                val method = args[0] as? String
                val request = args[1] as? String
                val callMethodGetSecureField = XposedHelpers.findField(Settings::class.java, "CALL_METHOD_GET_SECURE")
                val callMethodGetSecure = callMethodGetSecureField.get(null) as String
                if (method == callMethodGetSecure && request == Settings.Secure.ANDROID_ID) {
                    val androidId = readAndroidIdFromConfig(thisObject) ?: return@beforeHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, androidId)
                    result = bundle
                }
            }
        )
    }

    private fun readAndroidIdFromConfig(settingsProvider: Any?): String? {
        val context = runCatching {
            XposedHelpers.callMethod(settingsProvider, "getContext") as? Context
        }.getOrNull()
        val snapshot = HookNotifyClient.readConfigSnapshot(
            configName = "unique_identifier",
            allowBind = true,
            bindContext = context,
            logName = "unique identifier"
        )
        val values = snapshot
            ?.let { HookConfigFile.readSnapshotValues(it, HookLog.Module.SETTINGS, "unique identifier") }
            ?: HookConfigFile.readSharedPrefsValues(
                prefsName = UniqueIdentifierPrefs.PREFS_NAME,
                logModule = HookLog.Module.SETTINGS,
                logName = "unique identifier"
            )
            ?: return null

        if (!values[KEY_ENABLED]?.toBooleanStrictOrNull().orFalse()) return null
        return values[KEY_ANDROID_ID]?.takeIf { it.isNotBlank() }
    }

    private fun Boolean?.orFalse(): Boolean {
        return this ?: false
    }
}
