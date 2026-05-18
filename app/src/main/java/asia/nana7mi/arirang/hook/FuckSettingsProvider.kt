package asia.nana7mi.arirang.hook

import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

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
                        val bundle = Bundle()
                        bundle.putString(Settings.NameValueTable.VALUE, "1145141919810")
                        param.result = bundle
                    }
                }
            }
        )
    }
}