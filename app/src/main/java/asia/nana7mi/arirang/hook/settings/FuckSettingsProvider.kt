package asia.nana7mi.arirang.hook.settings

import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.afterHookedMethod
import asia.nana7mi.arirang.hook.network.VpnConfig
import asia.nana7mi.arirang.hook.api.HookPackageParam
import asia.nana7mi.arirang.hook.caller.Callers
import asia.nana7mi.arirang.hook.gms.UniqueIdentifierConfig
import asia.nana7mi.arirang.hook.bluetooth.BluetoothConfig

// Android ID is handled at SettingsProvider so apps receive the rewritten value
// through the normal Settings.Secure path instead of per-app hooks.
class FuckSettingsProvider : BaseHookModule(targetPackages = setOf("com.android.providers.settings")) {

    private companion object {
        private val ALWAYS_ON_VPN_KEYS = setOf("always_on_vpn_app", "always_on_vpn_lockdown")
        private val ALWAYS_ON_VPN_BYPASS = setOf("com.android.settings", "com.android.systemui")
    }

    override fun isEnabled(): Boolean {
        return true
    }
    override fun onHook(param: HookPackageParam) {
        val classLoader = param.classLoader

        HookLog.i(HookLog.Module.SETTINGS, "Installing settings hook for ${param.packageName}")

        try {
            val lmsClass = HookBridge.findClassIfExists(
                "com.android.providers.settings.SettingsProvider",
                classLoader
            )?: return
            hookCall(lmsClass)
        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.SETTINGS, "hook failed for ${param.packageName}", t)
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

    /**
     * Both values come from the owning config's own store, as declared fields.
     *
     * This module used to hold a second reader of the unique-identifier config and a second
     * reader of the Bluetooth one, each with its own refresh clock — so it and the owning module
     * were free to act on two different values of one config in one process.
     *
     * The two declared fields already carry the old readers' conditions — the feature being
     * switched on and the value being non-blank — so they are used directly; routing them
     * through the owning modules' callbacks would also apply those callbacks' caller tests.
     */
    private fun readAndroidIdFromConfig(): String? = UniqueIdentifierConfig.spoofedAndroidId.current()

    private fun readBluetoothNameFromConfig(): String? = BluetoothConfig.spoofedDeviceName.current()

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
        if (Callers.isPlatform(callingUid)) return false
        val current = VpnConfig.current()
        if (!current.enabled || !current.hideAlwaysOnVpn) return false
        val callerPackages = Callers.packagesFor(callingUid)
        if (callerPackages.any { it in ALWAYS_ON_VPN_BYPASS }) return false
        return current.appliesTo(callerPackages)
    }


}
