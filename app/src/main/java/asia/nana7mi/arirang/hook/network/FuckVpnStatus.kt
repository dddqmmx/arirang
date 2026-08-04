package asia.nana7mi.arirang.hook.network

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import android.os.Build
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.afterHookedMethod
import asia.nana7mi.arirang.hook.core.beforeHookedMethod
import asia.nana7mi.arirang.hook.util.CallerPackages
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hides VPN and proxy state from applications, inside system_server.
 *
 * All three surfaces are Binder entry points on ConnectivityService, so the
 * caller is identifiable and nothing is injected into the app itself.
 *
 * ## What this cannot do
 *
 * `VpnStatusPrefs.Config.hideVpnInterfaces` hides tunnel names returned in
 * ConnectivityService's LinkProperties. It cannot hide them from
 * `NetworkInterface.getNetworkInterfaces()`: that resolves through libcore's
 * `getifaddrs()` inside the calling app's own process and never reaches
 * system_server. Hiding `tun*` from that API would mean loading into every
 * third-party app, which the project's design constraint forbids.
 */
class FuckVpnStatus : BaseHookModule(matchSystem = true) {

    private val config = VpnHookConfigFile.create()

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ConnectivityService may not be loaded yet when this module is injected
        // at system_server startup, and findClassIfExists never triggers a load.
        // Catch the service registration instead: the framework ServiceManager
        // class is always present, and at addService time the binder argument IS
        // the ConnectivityService instance. A direct lookup is still attempted
        // first for the case where the class already happened to be loaded.
        installConnectivityHooks(
            HookBridge.findClassIfExists(
                "com.android.server.ConnectivityService",
                lpparam.classLoader
            )
        )

        val smClass = HookBridge.findClassIfExists("android.os.ServiceManager", lpparam.classLoader)
            ?: run {
                HookLog.w(HookLog.Module.CORE, "ServiceManager not found; VPN spoofing deferred indefinitely")
                return
            }
        HookBridge.hookAllMethods(smClass, "addService", beforeHookedMethod {
            if (args.getOrNull(0) == "connectivity") {
                val binder = args.getOrNull(1) ?: return@beforeHookedMethod
                installConnectivityHooks(binder.javaClass)
            }
        })
    }

    private val serviceHooked = AtomicBoolean(false)

    private fun installConnectivityHooks(service: Class<*>?) {
        if (service == null || !serviceHooked.compareAndSet(false, true)) return
        runCatching {
            HookLog.i(HookLog.Module.CORE, "installing VPN status hooks on ${service.name}")
            hookNetworkCapabilities(service)
            hookNetworkInfo(service)
            hookLinkProperties(service)
            hookProxy(service)
            hookCapabilityDispatch(service)
        }.onFailure {
            HookLog.e(HookLog.Module.CORE, "failed to install VPN status hooks", it)
        }
    }

    /**
     * The config to apply to the current Binder caller, or null to leave the
     * result untouched — which is also what happens whenever anything is
     * uncertain, so a failure here degrades to "no spoofing" rather than to a
     * broken network stack.
     */
    private fun activeConfig(): VpnHookConfig? = activeConfig(Binder.getCallingUid())

    /**
     * The config to apply for a specific target [callingUid], which is not
     * necessarily the Binder caller. NetworkCallback deliveries are marshalled
     * on ConnectivityService's own handler thread where
     * `Binder.getCallingUid()` is the platform uid; those paths pass the
     * destined app's uid down instead.
     */
    private fun activeConfig(callingUid: Int): VpnHookConfig? {
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

                val spoofed = spoofCapabilities(original, current.spoofedTransport)
                    ?: return@afterHookedMethod
                if (spoofed !== original) {
                    result = spoofed
                    HookLog.d(HookLog.Module.CORE, "hid VPN transport from uid ${Binder.getCallingUid()}")
                }
            }
        )
    }

    /**
     * Returns a modified copy, or [original] if no spoofing was needed.
     *
     * ConnectivityService hands out capability objects it continues to hold, so
     * editing one in place would rewrite the platform's own view of the network
     * rather than just this caller's answer.
     */
    private fun spoofCapabilities(
        original: NetworkCapabilities,
        spoofedTransport: String
    ): NetworkCapabilities? {
        // Fast path: nothing to hide, avoid the copy on every single
        // createWithSensitiveInfoSanitizedIfNecessaryWhenParceled() call — that
        // method fires for every NetworkCallback delivery on the device.
        if (!original.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            original.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !hasVpnTransportInfo(original)
        ) {
            return original
        }

        return runCatching {
            var changed = false
            val copy = HookBridge.newInstance(NetworkCapabilities::class.java, original) as NetworkCapabilities

            if (original.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                HookBridge.callMethod(copy, "removeTransportType", NetworkCapabilities.TRANSPORT_VPN)
                substituteTransport(spoofedTransport)?.let { transport ->
                    HookBridge.callMethod(copy, "addTransportType", transport)
                }
                changed = true
            }

            // Hide VpnTransportInfo (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = HookBridge.getObjectField(copy, "mTransportInfo")
                if (info != null && info.javaClass.name.endsWith("VpnTransportInfo")) {
                    HookBridge.setObjectField(copy, "mTransportInfo", null)
                    changed = true
                }
            }

            // Detection code checks either the transport or this capability, which a
            // real VPN network does not carry. Both have to agree or the mismatch is
            // itself a tell.
            if (!original.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                HookBridge.callMethod(copy, "addCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                changed = true
            }

            if (changed) copy else original
        }.onFailure {
            HookLog.w(HookLog.Module.CORE, "failed to rewrite NetworkCapabilities: ${it.message}")
        }.getOrNull()
    }

    private fun hasVpnTransportInfo(capabilities: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val info = runCatching {
            HookBridge.getObjectField(capabilities, "mTransportInfo")
        }.getOrNull() ?: return false
        return info.javaClass.name.endsWith("VpnTransportInfo")
    }

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
        HookBridge.hookAllMethods(service, "getNetworkInfoForNetwork", hook)
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

    // ---- LinkProperties ----

    private fun hookLinkProperties(service: Class<*>) {
        val hook = afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            val current = activeConfig() ?: return@afterHookedMethod
            val original = result as? LinkProperties ?: return@afterHookedMethod

            val spoofed = spoofLinkProperties(original, current)
                ?: return@afterHookedMethod
            if (spoofed !== original) {
                result = spoofed
                HookLog.d(HookLog.Module.CORE, "spoofed LinkProperties for uid ${Binder.getCallingUid()}")
            }
        }

        HookBridge.hookAllMethods(service, "getLinkProperties", hook)
        HookBridge.hookAllMethods(service, "getLinkPropertiesForType", hook)
    }

    private fun spoofLinkProperties(original: LinkProperties, config: VpnHookConfig): LinkProperties? = runCatching {
        var changed = false
        val copy = HookBridge.newInstance(LinkProperties::class.java, original) as LinkProperties

        val iface = original.interfaceName
        if (config.hideVpnInterfaces && iface != null && isVpnInterface(iface)) {
            // setInterfaceName also rebuilds every RouteInfo with the new
            // interface. Writing mIfaceName directly leaves routes referring
            // to tun0, which makes LinkProperties fail while unparcelling in
            // the client with "Route added with non-matching interface".
            copy.setInterfaceName("wlan0")
            changed = true
        }

        if (config.hideProxy && HookBridge.getObjectField(original, "mHttpProxy") != null) {
            HookBridge.setObjectField(copy, "mHttpProxy", null)
            changed = true
        }

        if (changed) copy else original
    }.getOrNull()

    private fun isVpnInterface(name: String): Boolean =
        VPN_INTERFACE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

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

    /**
     * Coverage for NetworkCallback leaks.
     *
     * Android 14 (U) ConnectivityService no longer has the `sendNetworkCapabilities`
     * / `sendLinkProperties` senders this class previously targeted: callback
     * deliveries were refactored onto a Messenger, where each recipient's
     * [NetworkCapabilities] is marshalled per-uid via
     * `createWithSensitiveInfoSanitizedIfNecessaryWhenParceled()`. That method is
     * also the return path for the synchronous `getNetworkCapabilities()` query, so
     * hooking it alone covers both the query and the callback surfaces in one place.
     *
     * The destined app's uid is passed as an explicit argument (args[3]) rather than
     * read from the Binder thread, because on the callback path this method runs on
     * ConnectivityService's own thread where `Binder.getCallingUid()` is the
     * platform uid and would disable spoofing entirely.
     */
    private fun hookCapabilityDispatch(service: Class<*>) {
        HookBridge.hookAllMethods(
            service,
            "createWithSensitiveInfoSanitizedIfNecessaryWhenParceled",
            afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val callingUid = args.getOrNull(3) as? Int ?: return@afterHookedMethod
                val current = activeConfig(callingUid) ?: return@afterHookedMethod
                if (!current.hideVpnTransport) return@afterHookedMethod

                val original = result as? NetworkCapabilities ?: return@afterHookedMethod
                val spoofed = spoofCapabilities(original, current.spoofedTransport)
                    ?: return@afterHookedMethod
                if (spoofed !== original) {
                    result = spoofed
                    HookLog.d(HookLog.Module.CORE, "hid VPN transport for uid $callingUid")
                }
            }
        )
    }

    private companion object {
        // ConnectivityManager.TYPE_* are deprecated; mirrored here so the legacy
        // path does not pull deprecation warnings through the whole file.
        private const val TYPE_MOBILE = 0
        private const val TYPE_WIFI = 1
        private const val TYPE_ETHERNET = 9
        private const val TYPE_VPN = 17
        private val VPN_INTERFACE_PREFIXES = listOf("tun", "ppp", "wg", "utun", "ipsec", "tap")
    }
}
