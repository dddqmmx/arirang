package asia.nana7mi.arirang.hook.settings

import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.data.datastore.schema.BluetoothConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.IdentifierConfigSchema
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.RealtimeHookConfig
import asia.nana7mi.arirang.hook.core.afterHookedMethod
import asia.nana7mi.arirang.hook.network.VpnHookConfigFile
import asia.nana7mi.arirang.hook.util.CallerPackages
import de.robv.android.xposed.callbacks.XC_LoadPackage

// Android ID is handled at SettingsProvider so apps receive the rewritten value
// through the normal Settings.Secure path instead of per-app hooks.
class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

    private companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ANDROID_ID = "android_id"
        private const val REFRESH_INTERVAL_MS = 1_000L
        private val ALWAYS_ON_VPN_KEYS = setOf("always_on_vpn_app", "always_on_vpn_lockdown")
        private val ALWAYS_ON_VPN_BYPASS = setOf("com.android.settings", "com.android.systemui")
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

    /** Shares the VPN config with FuckVpnStatus; each host process caches its own copy. */
    private val vpnConfig = VpnHookConfigFile.create()

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

                // Always-on VPN. Reported as unset so an app cannot infer a VPN
                // from the configuration even when the transport is hidden.
                if (method == callMethodGetSecure && request in ALWAYS_ON_VPN_KEYS) {
                    if (!shouldHideAlwaysOnVpn()) return@afterHookedMethod
                    val bundle = Bundle()
                    bundle.putString(Settings.NameValueTable.VALUE, null)
                    result = bundle
                    HookLog.d(HookLog.Module.SETTINGS, "spoof Settings.Secure.$request")
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

    /**
     * Whether the always-on VPN keys should read as unset for the current caller.
     *
     * [ALWAYS_ON_VPN_BYPASS] is not user configurable on purpose: these are the
     * surfaces through which the always-on VPN is *configured*, and blanking the
     * value for them would show the user their own VPN setting had vanished.
     * Hiding it from them protects nothing, since they are not what an app uses
     * to detect a VPN.
     */
    private fun shouldHideAlwaysOnVpn(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (CallerPackages.isPlatformCaller(callingUid)) return false
        val current = vpnConfig.current()
        if (!current.enabled || !current.hideAlwaysOnVpn) return false
        val callerPackages = CallerPackages.forUid(callingUid)
        if (callerPackages.any { it in ALWAYS_ON_VPN_BYPASS }) return false
        return current.appliesTo(callerPackages)
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
