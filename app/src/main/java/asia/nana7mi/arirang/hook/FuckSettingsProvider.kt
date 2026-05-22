package asia.nana7mi.arirang.hook

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

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
            hookCall(lmsClass, classLoader)

        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.SETTINGS, "hook failed", t)
        }
    }

    private fun hookCall(lmsClass: Class<*>, classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            lmsClass, "call",
            String::class.java,
            String::class.java,
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val method = param.args[0] as? String
                    val request = param.args[1] as? String
                    val callMethodGetSecureField = XposedHelpers.findField(Settings::class.java, "CALL_METHOD_GET_SECURE")
                    val callMethodGetSecure = callMethodGetSecureField.get(null) as String
                    if (method == callMethodGetSecure && request == Settings.Secure.ANDROID_ID) {
                        val androidId = readAndroidIdFromConfig(param.thisObject) ?: return
                        val bundle = Bundle()
                        bundle.putString(Settings.NameValueTable.VALUE, androidId)
                        param.result = bundle
                    }
                }
            }
        )
    }

    private fun readAndroidIdFromConfig(settingsProvider: Any?): String? {
        val context = runCatching {
            XposedHelpers.callMethod(settingsProvider, "getContext") as? Context
        }.getOrNull()
        val snapshot = HookNotifyClient.readUniqueIdentifierConfigSnapshot(
            allowBind = true,
            bindContext = context
        )
            ?: return null
        return runCatching {
            val root = JSONObject(snapshot)
            if (!root.optString(KEY_ENABLED).toBooleanStrictOrNull().orFalse()) return@runCatching null
            root.optString(KEY_ANDROID_ID).takeIf { it.isNotBlank() }
        }.onFailure {
            HookLog.w(HookLog.Module.SETTINGS, "failed to parse unique identifier config: ${it.message}")
        }.getOrNull()
    }

    private fun Boolean?.orFalse(): Boolean {
        return this ?: false
    }
}
