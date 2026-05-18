package asia.nana7mi.arirang.hook

import android.os.Bundle
import android.provider.Settings
import android.util.Xml
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileInputStream

// Android ID is handled at SettingsProvider so apps receive the rewritten value
// through the normal Settings.Secure path instead of per-app hooks.
class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

    private companion object {
        private const val PREFS_NAME = "unique_identifier_prefs"
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
            XposedBridge.log("FuckSetting: Hook 过程出错 - ${t.message}")
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
                        val androidId = readUniqueIdentifierValues()
                            ?.takeIf { it[KEY_ENABLED]?.toBooleanStrictOrNull() == true }
                            ?.get(KEY_ANDROID_ID)
                            ?.takeIf { it.isNotBlank() }
                            ?: return
                        val bundle = Bundle()
                        bundle.putString(Settings.NameValueTable.VALUE, androidId)
                        param.result = bundle
                    }
                }
            }
        )
    }

    private fun readUniqueIdentifierValues(): Map<String, String>? {
        val candidates = listOf(
            File("/data/user/0/${BuildConfig.APPLICATION_ID}/shared_prefs/$PREFS_NAME.xml"),
            File("/data/data/${BuildConfig.APPLICATION_ID}/shared_prefs/$PREFS_NAME.xml")
        )
        val prefsFile = candidates.firstOrNull { it.isFile && it.canRead() } ?: return null
        return runCatching {
            val values = linkedMapOf<String, String>()
            FileInputStream(prefsFile).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, Charsets.UTF_8.name())

                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        val key = parser.getAttributeValue(null, "name")
                        if (!key.isNullOrBlank()) {
                            when (tagName) {
                                "boolean", "int", "long", "float" -> {
                                    parser.getAttributeValue(null, "value")?.let { values[key] = it }
                                }
                                "string" -> values[key] = parser.nextText()
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            values
        }.onFailure {
            XposedBridge.log("FuckSetting: failed to read unique identifier config - ${it.message}")
        }.getOrNull()
    }
}
