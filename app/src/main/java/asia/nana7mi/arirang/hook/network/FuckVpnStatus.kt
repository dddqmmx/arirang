package asia.nana7mi.arirang.hook.network

import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.afterHookedMethod
import asia.nana7mi.arirang.hook.util.CallerPackages
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hides VPN and proxy state from applications, inside system_server.
 *
 * All three surfaces are Binder entry points on ConnectivityService, so the
 * caller is identifiable and nothing is injected into the app itself.
 *
 * ## What this cannot do
 *
 * `VpnStatusPrefs.Config.hideVpnInterfaces` has no implementation here and
 * cannot have one: `NetworkInterface.getNetworkInterfaces()` resolves through
 * libcore's `getifaddrs()` inside the calling app's own process and never
 * reaches system_server. Hiding `tun*` from it would mean loading into every
 * third-party app, which the project's design constraint forbids.
 */
class FuckVpnStatus : BaseHookModule(matchSystem = true) {

    private val config = VpnHookConfigFile.create()

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val service = HookBridge.findClassIfExists(
            "com.android.server.ConnectivityService",
            lpparam.classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.CORE, "ConnectivityService not found; VPN spoofing inactive")
            return
        }

        HookLog.i(HookLog.Module.CORE, "installing VPN status hooks on ${service.name}")
        hookNetworkCapabilities(service)
        hookNetworkInfo(service)
        hookProxy(service)
    }

    /**
     * The config to apply to the current Binder caller, or null to leave the
     * result untouched — which is also what happens whenever anything is
     * uncertain, so a failure here degrades to "no spoofing" rather than to a
     * broken network stack.
     */
    private fun activeConfig(): VpnHookConfig? {
        val callingUid = Binder.getCallingUid()
        if (CallerPackages.isPlatformCaller(callingUid)) return null
        val current = config.current()
        if (!current.enabled) return null
        return current.takeIf { it.appliesTo(CallerPackages.forUid(callingUid)) }
    }

    // ---- NetworkCapabilities: the modern detection path ----

    private fun hookNetworkCapabilities(service: Class<*>) {
        HookBridge.hookAllMethods(
            service,
            "getNetworkCapabilities",
            afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val current = activeConfig() ?: return@afterHookedMethod
                if (!current.hideVpnTransport) return@afterHookedMethod
                val original = result as? NetworkCapabilities ?: return@afterHookedMethod
                if (!original.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@afterHookedMethod

                val spoofed = spoofCapabilities(original, current.spoofedTransport)
                    ?: return@afterHookedMethod
                result = spoofed
                HookLog.d(HookLog.Module.CORE, "hid VPN transport from uid ${Binder.getCallingUid()}")
            }
        )
    }

    /**
     * Returns a modified copy, never a mutation of [original].
     *
     * ConnectivityService hands out capability objects it continues to hold, so
     * editing one in place would rewrite the platform's own view of the network
     * rather than just this caller's answer.
     */
    private fun spoofCapabilities(
        original: NetworkCapabilities,
        spoofedTransport: String
    ): NetworkCapabilities? = runCatching {
        val copy = HookBridge.newInstance(NetworkCapabilities::class.java, original) as NetworkCapabilities
        HookBridge.callMethod(copy, "removeTransportType", NetworkCapabilities.TRANSPORT_VPN)
        substituteTransport(spoofedTransport)?.let { transport ->
            HookBridge.callMethod(copy, "addTransportType", transport)
        }
        // Detection code checks either the transport or this capability, which a
        // real VPN network does not carry. Both have to agree or the mismatch is
        // itself a tell.
        HookBridge.callMethod(copy, "addCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        copy
    }.onFailure {
        HookLog.w(HookLog.Module.CORE, "failed to rewrite NetworkCapabilities: ${it.message}")
    }.getOrNull()

    private fun substituteTransport(spoofedTransport: String): Int? = when (spoofedTransport) {
        VpnHookConfig.SPOOFED_TRANSPORT_WIFI -> NetworkCapabilities.TRANSPORT_WIFI
        VpnHookConfig.SPOOFED_TRANSPORT_CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
        VpnHookConfig.SPOOFED_TRANSPORT_ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
        // UNCHANGED: drop the VPN transport and leave whatever the underlying
        // network already reported.
        else -> null
    }

    // ---- NetworkInfo: the legacy detection path ----

    private fun hookNetworkInfo(service: Class<*>) {
        val hook = afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            val current = activeConfig() ?: return@afterHookedMethod
            if (!current.hideVpnTransport) return@afterHookedMethod
            val original = result as? NetworkInfo ?: return@afterHookedMethod
            @Suppress("DEPRECATION")
            if (original.type != TYPE_VPN) return@afterHookedMethod

            val spoofed = spoofNetworkInfo(original, current.spoofedTransport)
                ?: return@afterHookedMethod
            result = spoofed
        }

        // getActiveNetworkInfoForUid is deliberately not hooked: it is the
        // platform asking on behalf of some other uid, so the Binder caller is
        // system_server and the answer is not this caller's to change.
        HookBridge.hookAllMethods(service, "getActiveNetworkInfo", hook)
        HookBridge.hookAllMethods(service, "getNetworkInfo", hook)
    }

    private fun spoofNetworkInfo(original: NetworkInfo, spoofedTransport: String): NetworkInfo? =
        runCatching {
            val type = substituteNetworkType(spoofedTransport) ?: return null
            val copy = HookBridge.newInstance(NetworkInfo::class.java, original) as NetworkInfo
            HookBridge.setIntField(copy, "mNetworkType", type.first)
            HookBridge.setObjectField(copy, "mTypeName", type.second)
            copy
        }.onFailure {
            // NetworkInfo is deprecated and its private shape varies between
            // releases; failing here just leaves the legacy path unspoofed
            // rather than affecting the NetworkCapabilities result above.
            HookLog.d(HookLog.Module.CORE, "legacy NetworkInfo rewrite unavailable: ${it.message}")
        }.getOrNull()

    private fun substituteNetworkType(spoofedTransport: String): Pair<Int, String>? =
        when (spoofedTransport) {
            VpnHookConfig.SPOOFED_TRANSPORT_WIFI -> TYPE_WIFI to "WIFI"
            VpnHookConfig.SPOOFED_TRANSPORT_CELLULAR -> TYPE_MOBILE to "MOBILE"
            VpnHookConfig.SPOOFED_TRANSPORT_ETHERNET -> TYPE_ETHERNET to "ETHERNET"
            else -> null
        }

    // ---- Proxy ----

    private fun hookProxy(service: Class<*>) {
        val hook = afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            if (result == null) return@afterHookedMethod
            val current = activeConfig() ?: return@afterHookedMethod
            if (!current.hideProxy) return@afterHookedMethod
            result = null
            HookLog.d(HookLog.Module.CORE, "hid proxy from uid ${Binder.getCallingUid()}")
        }

        HookBridge.hookAllMethods(service, "getProxyForNetwork", hook)
        HookBridge.hookAllMethods(service, "getGlobalProxy", hook)
    }

    private companion object {
        // ConnectivityManager.TYPE_* are deprecated; mirrored here so the legacy
        // path does not pull deprecation warnings through the whole file.
        private const val TYPE_MOBILE = 0
        private const val TYPE_WIFI = 1
        private const val TYPE_ETHERNET = 9
        private const val TYPE_VPN = 17
    }
}
