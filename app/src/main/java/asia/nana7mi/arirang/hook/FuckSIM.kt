package asia.nana7mi.arirang.hook

import android.util.Log
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.model.SimInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections

class FuckSIM : BaseHookModule(targetPackages = setOf("com.android.phone")) {

    companion object {
        private const val PREFS_NAME = "sim_config_prefs"
        private const val ENABLED_KEY = "enabled"
        private const val MODE_KEY = "mode"
        private const val WHITELIST_KEY = "whitelist"
        private const val BLACKLIST_KEY = "blacklist"
        private const val SIM_INFO_LIST_KEY = "sim_info_list"
        private const val LAST_MODIFIED_KEY = "last_modified"

        private var PACKAGE_SET: Set<String> = emptySet()
        private var ENABLED = false
        private var MODE = Mode.WHITELIST
        private var SIM_INFO_SET: List<SimInfo> = emptyList()
        private var lastLoadedTimestamp: Long = -1
        private val gson = Gson()

        fun parseSimInfo(json: String?): List<SimInfo> {
            return if (json != null) {
                val listType = object : TypeToken<List<SimInfo>>() {}.type
                gson.fromJson(json, listType)
            } else {
                emptyList()
            }
        }

        private fun getPref(): XSharedPreferences? {
            val pref = XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_NAME)
            return if (pref.file.canRead()) pref else null
        }

        private fun loadConfigIfUpdated() {
            val pref = getPref() ?: return
            pref.reload()
            val newTimestamp = pref.getLong(LAST_MODIFIED_KEY, -1)
            if (newTimestamp == lastLoadedTimestamp) return

            lastLoadedTimestamp = newTimestamp
            ENABLED = pref.getBoolean(ENABLED_KEY, false)
            val modeInt = pref.getInt(MODE_KEY, 0) // 0 = WHITELIST, 1 = BLACKLIST
            MODE = if (modeInt == 1) Mode.BLACKLIST else Mode.WHITELIST

            val rawSet = pref.getStringSet(if (MODE == Mode.WHITELIST) WHITELIST_KEY else BLACKLIST_KEY, null)
            PACKAGE_SET = if (rawSet != null) HashSet(rawSet) else emptySet()

            val simInfoListJson = pref.getString(SIM_INFO_LIST_KEY, null)
            SIM_INFO_SET = parseSimInfo(simInfoListJson)
        }
    }

    enum class Mode { WHITELIST, BLACKLIST }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("SIM hook loading for package: ${lpparam.packageName}, process: ${lpparam.processName}")

        try {
            val subscriptionManagerService = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.subscription.SubscriptionManagerService",
                lpparam.classLoader
            )
            if (subscriptionManagerService == null) {
                XposedBridge.log("Class not found subscriptionManagerService")
                return
            }
            XposedBridge.log("Class found")
            hookMethods(subscriptionManagerService)
        } catch (t: Throwable) {
            XposedBridge.log("Hook failed: ${t.message}\n${Log.getStackTraceString(t)}")
        }
    }

    private fun hookMethods(computerEngine: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                computerEngine,
                "getActiveSubscriptionInfoList",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        loadConfigIfUpdated()
                        if (!ENABLED) return

                        val callingPackage = param.args[0] as? String
                        if (shouldFilter(callingPackage)) {
                            param.result = emptyList<Any>()
                            return
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Error hooking methods: ${e.message}\n${Log.getStackTraceString(e)}")
        }
    }

    private fun shouldFilter(callingPackage: String?): Boolean {
        if (callingPackage == null) return true
        return when (MODE) {
            Mode.WHITELIST -> !PACKAGE_SET.contains(callingPackage)
            Mode.BLACKLIST -> PACKAGE_SET.contains(callingPackage)
        }
    }
}
