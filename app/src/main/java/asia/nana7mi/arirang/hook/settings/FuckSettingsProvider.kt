package asia.nana7mi.arirang.hook.settings

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.orFalse

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import de.robv.android.xposed.callbacks.XC_LoadPackage

// Android ID is handled at SettingsProvider so apps receive the rewritten value
// through the normal Settings.Secure path instead of per-app hooks.
class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

    private companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ANDROID_ID = "android_id"
    }

    override fun isEnabled(): Boolean {
        return true
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        HookLog.i(HookLog.Module.SETTINGS, "Installing settings hook for ${lpparam.packageName}")

        try {
            val lmsClass = HookBridge.findClassIfExists(
                "com.android.providers.settings.SettingsProvider",
                classLoader
            )?: return
            hookCall(lmsClass)
        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.SETTINGS, "hook failed for ${lpparam.packageName}", t)
        }
    }

    private fun hookCall(lmsClass: Class<*>) {
        HookBridge.findAndHookMethod(
            lmsClass, "call",
            String::class.java,
            String::class.java,
            Bundle::class.java,
            beforeHookedMethod {
                val method = args[0] as? String
                val request = args[1] as? String
                
                // Diagnostic: Log all interesting requests
                if (request?.contains("blue", ignoreCase = true) == true || 
                    request?.contains("name", ignoreCase = true) == true ||
                    request?.contains("device", ignoreCase = true) == true) {
                    HookLog.i(HookLog.Module.SETTINGS, "Settings call: method=$method, request=$request")
                }

                // Handle Android ID (Secure)
                val callMethodGetSecure = runCatching {
                    HookBridge.getStaticObjectField(Settings::class.java, "CALL_METHOD_GET_SECURE") as String
                }.getOrNull() ?: "get_secure"

                if (method == callMethodGetSecure && request == Settings.Secure.ANDROID_ID) {
                    val androidId = readAndroidIdFromConfig(thisObject) ?: return@beforeHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, androidId)
                    result = bundle
                    return@beforeHookedMethod
                }

                if (method == callMethodGetSecure && request == "bluetooth_name") {
                    val bluetoothName = readBluetoothNameFromConfig(thisObject) ?: return@beforeHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, bluetoothName)
                    result = bundle
                    HookLog.i(HookLog.Module.SETTINGS, "spoof Settings.Secure.bluetooth_name -> $bluetoothName")
                    return@beforeHookedMethod
                }

                // Handle Bluetooth Name (Global)
                val callMethodGetGlobal = runCatching {
                    HookBridge.getStaticObjectField(Settings::class.java, "CALL_METHOD_GET_GLOBAL") as String
                }.getOrNull() ?: "get_global"

                if (method == callMethodGetGlobal && (request == "bluetooth_name" || request == "device_name")) {
                    val bluetoothName = readBluetoothNameFromConfig(thisObject) ?: return@beforeHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, bluetoothName)
                    result = bundle
                    HookLog.i(HookLog.Module.SETTINGS, "spoof Settings.Global.$request -> $bluetoothName")
                    return@beforeHookedMethod
                }
            }
        )
    }

    private fun readAndroidIdFromConfig(settingsProvider: Any?): String? {
        val context = runCatching {
            HookBridge.callMethod(settingsProvider, "getContext") as? Context
        }.getOrNull()
        val snapshot = ArirangClient.readConfigSnapshot(
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

    private fun readBluetoothNameFromConfig(settingsProvider: Any?): String? {
        val context = runCatching {
            HookBridge.callMethod(settingsProvider, "getContext") as? Context
        }.getOrNull()
        val snapshot = ArirangClient.readConfigSnapshot(
            configName = "bluetooth",
            allowBind = true,
            bindContext = context,
            logName = "Bluetooth"
        )
        val values = snapshot
            ?.let { HookConfigFile.readSnapshotValues(it, HookLog.Module.SETTINGS, "Bluetooth") }
            ?: HookConfigFile.readSharedPrefsValues(
                prefsName = "bluetooth_config",
                logModule = HookLog.Module.SETTINGS,
                logName = "Bluetooth"
            )
            ?: return null

        if (!values["enabled"]?.toBooleanStrictOrNull().orFalse()) return null
        return values["device_name"]?.takeIf { it.isNotBlank() }
    }

}
