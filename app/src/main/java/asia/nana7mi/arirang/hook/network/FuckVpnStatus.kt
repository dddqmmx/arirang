package asia.nana7mi.arirang.hook.network

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import android.os.Build
import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.MethodHookParam
import asia.nana7mi.arirang.hook.api.HookPackageParam
import java.util.concurrent.atomic.AtomicBoolean
import asia.nana7mi.arirang.hook.target.Targets
import asia.nana7mi.arirang.hook.caller.Callers

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
private const val CONNECTIVITY_SERVICE_NAME = "connectivity"
private const val CONNECTIVITY_SERVICE_CLASS = "com.android.server.ConnectivityService"

class FuckVpnStatus : BaseHookModule(matchSystem = true) {

    

    override fun onHook(param: HookPackageParam) {
        Targets.onServiceClassPublished(CONNECTIVITY_SERVICE_NAME) { binder ->
            installConnectivityHooks(binder.javaClass)
        }

        // The class can also be reachable before the service is published.
        HookBridge.findClassIfExists(CONNECTIVITY_SERVICE_CLASS, param.classLoader)
            ?.let(::installConnectivityHooks)
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
            serviceHooked.set(false)
        }
    }

    /**
     * The config to apply to the current Binder caller, or null to leave the
     * result untouched — which is also what happens whenever anything is
     * uncertain, so a failure here degrades to "no spoofing" rather than to a
     * broken network stack.
     */
    /**
     * Whether the config governs [callerUid] — which is not necessarily the Binder caller.
     *
     * Two things have to hold, and they are separate questions: the caller must be an app rather
     * than the platform, and the config must not exempt it. Framework internals reach
     * ConnectivityService in-process, where `Binder.getCallingUid()` reports the platform's own
     * uid, and rewriting their answers would feed spoofed data back into the platform's own
     * networking. This is the `where` precondition of every gate below.
     */
    private fun governs(callerUid: Int, config: VpnHookConfig): Boolean =
        !Callers.isPlatform(callerUid) && config.appliesTo(Callers.packagesFor(callerUid))

    /** For the surfaces where the Binder caller is the app being served. */
    private fun binderCallerGoverned(param: MethodHookParam, config: VpnHookConfig): Boolean =
        governs(Binder.getCallingUid(), config)

    /**
     * For `linkPropertiesRestrictedForCallerPermissions(lp, callerPid, callerUid)`, which names
     * its caller in the arguments because it also serves the async callback path.
     */
    private fun permissionsCallerGoverned(param: MethodHookParam, config: VpnHookConfig): Boolean {
        val callerUid = (param.args.getOrNull(2) as? Number)?.toInt() ?: return false
        return governs(callerUid, config)
    }

    /**
     * For a capability that is being dispatched to a callback rather than returned to a caller.
     *
     * The destined app's uid is an argument rather than the Binder caller, because on that path
     * this runs on ConnectivityService's own thread where `Binder.getCallingUid()` is the
     * platform uid.
     */
    private fun deliveredCallerGoverned(param: MethodHookParam, config: VpnHookConfig): Boolean {
        val raw = when (param.args.size) {
            // Android 14+ (6 args): (nc, includeLocationSensitiveInfo, callingPid, callingUid, …)
            6 -> param.args.getOrNull(3)
            // Android 12-13 (5 args): (nc, callerPid, callerUid, …)
            5 -> param.args.getOrNull(2)
            else -> param.args.getOrNull(3) ?: param.args.getOrNull(2)
        }
        val callerUid = (raw as? Number)?.toInt() ?: return false
        return governs(callerUid, config)
    }

    // ---- NetworkCapabilities: the modern detection path ----

    private fun hookNetworkCapabilities(service: Class<*>) {
        val hook = VpnConfig.vpnTransport.after(where = ::binderCallerGoverned) { current ->
            val original = result as? NetworkCapabilities ?: return@after

            val spoofed = spoofCapabilities(original, current.spoofedTransport)
                ?: return@after
            if (spoofed !== original) {
                result = spoofed
                HookLog.i(HookLog.Module.CORE, "hid VPN transport from uid ${Binder.getCallingUid()}")
            }
        }

        HookBridge.hookAllMethods(service, "getNetworkCapabilities", hook)
        HookBridge.hookAllMethods(service, "getDefaultNetworkCapabilitiesForUser", hook)
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
        // callback delivery.
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
                if (info != null && info.javaClass.name.contains("VpnTransportInfo")) {
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

            // If removing VPN left no transports at all, ensure it reports at least WIFI
            val hasRemainingTransport = copy.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    copy.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    copy.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!hasRemainingTransport) {
                HookBridge.callMethod(copy, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI)
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
        return info.javaClass.name.contains("VpnTransportInfo")
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
        val hook = VpnConfig.vpnTransport.after(where = ::binderCallerGoverned) { current ->
            val original = result as? NetworkInfo ?: return@after
            @Suppress("DEPRECATION")
            if (original.type != TYPE_VPN) return@after

            val spoofed = spoofNetworkInfo(original, current.spoofedTransport)
                ?: return@after
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
        val syncHook = VpnConfig.enabled.after(where = ::binderCallerGoverned) { current ->
            val original = result as? LinkProperties ?: return@after

            val spoofed = spoofLinkProperties(original, current)
                ?: return@after
            if (spoofed !== original) {
                result = spoofed
                HookLog.i(HookLog.Module.CORE, "spoofed LinkProperties for uid ${Binder.getCallingUid()}")
            }
        }

        HookBridge.hookAllMethods(service, "getLinkProperties", syncHook)
        HookBridge.hookAllMethods(service, "getLinkPropertiesForType", syncHook)
        HookBridge.hookAllMethods(service, "getActiveLinkProperties", syncHook)

        // Internal method called by both sync getters and async callbacks:
        // linkPropertiesRestrictedForCallerPermissions(LinkProperties lp, int callerPid, int callerUid)
        HookBridge.hookAllMethods(
            service,
            "linkPropertiesRestrictedForCallerPermissions",
            VpnConfig.enabled.after(where = ::permissionsCallerGoverned) { current ->
                val original = result as? LinkProperties ?: return@after

                val spoofed = spoofLinkProperties(original, current)
                    ?: return@after
                if (spoofed !== original) {
                    result = spoofed
                    HookLog.i(
                        HookLog.Module.CORE,
                        "spoofed LinkProperties callback for uid ${args.getOrNull(2)}"
                    )
                }
            }
        )
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
        val hook = VpnConfig.proxy.after(where = ::binderCallerGoverned) {
            if (result == null) return@after
            result = null
            HookLog.i(HookLog.Module.CORE, "hid proxy from uid ${Binder.getCallingUid()}")
        }

        HookBridge.hookAllMethods(service, "getProxyForNetwork", hook)
        HookBridge.hookAllMethods(service, "getGlobalProxy", hook)
    }

    /**
     * Coverage for NetworkCallback leaks.
     *
     * In Android 14+ ConnectivityService, callbacks and queries use
     * `createWithSensitiveInfoSanitizedIfNecessaryWhenParceled()`.
     * In Android 12-13, it was `createWithLocationInfoSanitizedIfNecessaryWhenParceled()`.
     *
     * The destined app's uid is passed as an explicit argument rather than
     * read from the Binder thread, because on the callback path this method runs on
     * ConnectivityService's own thread where `Binder.getCallingUid()` is the
     * platform uid and would disable spoofing entirely.
     */
    private fun hookCapabilityDispatch(service: Class<*>) {
        val hook = VpnConfig.vpnTransport.after(where = ::deliveredCallerGoverned) { current ->
            val original = result as? NetworkCapabilities ?: return@after
            val spoofed = spoofCapabilities(original, current.spoofedTransport)
                ?: return@after
            if (spoofed !== original) {
                result = spoofed
                HookLog.i(HookLog.Module.CORE, "hid VPN transport for a dispatched callback")
            }
        }

        HookBridge.hookAllMethods(service, "createWithSensitiveInfoSanitizedIfNecessaryWhenParceled", hook)
        HookBridge.hookAllMethods(service, "createWithLocationInfoSanitizedIfNecessaryWhenParceled", hook)
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
