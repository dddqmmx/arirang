package asia.nana7mi.arirang.hook.settings

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.RealtimeHookConfig

import android.os.Bundle
import android.provider.Settings
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.schema.BluetoothConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.IdentifierConfigSchema
import de.robv.android.xposed.callbacks.XC_LoadPackage

// Android ID is handled at SettingsProvider so apps receive the rewritten value
// through the normal Settings.Secure path instead of per-app hooks.
class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

    private companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ANDROID_ID = "android_id"
        private const val REFRESH_INTERVAL_MS = 1_000L
    }

    private data class StringSetting(val enabled: Boolean = false, val value: String? = null)

    private val androidIdConfig = RealtimeHookConfig(
        defaultValue = StringSetting(),
        refreshIntervalMs = REFRESH_INTERVAL_MS,
        readSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.UNIQUE_IDENTIFIER,
                force = force,
                allowBind = true,
                logName = "unique identifier"
            )
        },
        parseSnapshot = {
            runCatching { IdentifierConfigSchema.fromJson(it) }.getOrNull()?.let { schema ->
                StringSetting(schema.enabled, schema.androidId.takeIf(String::isNotBlank))
            }
        },
        readFallback = {
            parseStoredSetting(UniqueIdentifierPrefs.PREFS_NAME, KEY_ANDROID_ID)
        }
    )

    private val bluetoothNameConfig = RealtimeHookConfig(
        defaultValue = StringSetting(),
        refreshIntervalMs = REFRESH_INTERVAL_MS,
        readSnapshot = { force ->
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.BLUETOOTH,
                force = force,
                allowBind = true,
                logName = "Bluetooth"
            )
        },
        parseSnapshot = {
            runCatching { BluetoothConfigSchema.fromJson(it) }.getOrNull()?.let { schema ->
                StringSetting(schema.enabled, schema.deviceName.takeIf(String::isNotBlank))
            }
        },
        readFallback = {
            parseStoredSetting(BluetoothConfigPrefs.PREFS_NAME, BluetoothConfigPrefs.KEY_DEVICE_NAME)
        }
    )

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
            afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val method = args[0] as? String
                val request = args[1] as? String
                
                // Diagnostic: Log all interesting requests
                if (request?.contains("blue", ignoreCase = true) == true || 
                    request?.contains("name", ignoreCase = true) == true ||
                    request?.contains("device", ignoreCase = true) == true) {
                    HookLog.d(HookLog.Module.SETTINGS, "Settings call: method=$method, request=$request")
                }

                // Handle Android ID (Secure)
                val callMethodGetSecure = runCatching {
                    HookBridge.getStaticObjectField(Settings::class.java, "CALL_METHOD_GET_SECURE") as String
                }.getOrNull() ?: "get_secure"

                if (method == callMethodGetSecure && request == Settings.Secure.ANDROID_ID) {
                    val androidId = readAndroidIdFromConfig() ?: return@afterHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, androidId)
                    result = bundle
                    return@afterHookedMethod
                }

                if (method == callMethodGetSecure && request == "bluetooth_name") {
                    val bluetoothName = readBluetoothNameFromConfig() ?: return@afterHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, bluetoothName)
                    result = bundle
                    HookLog.d(HookLog.Module.SETTINGS, "spoof Settings.Secure.bluetooth_name")
                    return@afterHookedMethod
                }

                // Handle Bluetooth Name (Global)
                val callMethodGetGlobal = runCatching {
                    HookBridge.getStaticObjectField(Settings::class.java, "CALL_METHOD_GET_GLOBAL") as String
                }.getOrNull() ?: "get_global"

                if (method == callMethodGetGlobal && (request == "bluetooth_name" || request == "device_name")) {
                    val bluetoothName = readBluetoothNameFromConfig() ?: return@afterHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, bluetoothName)
                    result = bundle
                    HookLog.d(HookLog.Module.SETTINGS, "spoof Settings.Global.$request")
                    return@afterHookedMethod
                }
            }
        )
    }

    private fun readAndroidIdFromConfig(): String? {
        return androidIdConfig.current().takeIf { it.enabled }?.value
    }

    private fun readBluetoothNameFromConfig(): String? {
        return bluetoothNameConfig.current().takeIf { it.enabled }?.value
    }

    private fun parseStoredSetting(prefsName: String, valueKey: String): StringSetting {
        val values = HookConfigFile.readSharedPrefsValues(
            prefsName = prefsName,
            logModule = HookLog.Module.SETTINGS,
            logName = prefsName
        ).orEmpty()
        return StringSetting(
            enabled = values[KEY_ENABLED]?.toBooleanStrictOrNull() == true,
            value = values[valueKey]?.takeIf { it.isNotBlank() }
        )
    }

}
