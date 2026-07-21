package asia.nana7mi.arirang.hook.wifi

import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

/**
 * Closes SSID/BSSID leaks that bypass [WifiServiceImpl.getConnectionInfo].
 *
 * Apps (e.g. Hellotalk) commonly read:
 * - [android.net.NetworkCapabilities.getTransportInfo] as [WifiInfo]
 * - deprecated [NetworkInfo.getExtraInfo] (SSID)
 *
 * Both values are produced in system/wifi processes and parceled to the app, so
 * hooks stay system-level and rewrite identity on the write/set path rather than
 * injecting into third-party processes.
 */
internal class WifiConnectivityHooks(
    private val currentConfig: () -> WifiHookConfig
) {
    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    fun hookConnectivitySurfaces(classLoader: ClassLoader) {
        hookNetworkCapabilities(classLoader)
        hookNetworkInfo(classLoader)
        hookWifiInfoWriteToParcel(classLoader)
        hookWifiNetworkAgentSpecifier(classLoader)
    }

    private fun hookNetworkCapabilities(classLoader: ClassLoader) {
        val capabilitiesClass = HookBridge.findClassIfExists(
            "android.net.NetworkCapabilities",
            classLoader
        ) ?: runCatching { Class.forName("android.net.NetworkCapabilities") }.getOrNull()
        if (capabilitiesClass == null) {
            HookLog.w(HookLog.Module.WIFI, "NetworkCapabilities class not found")
            return
        }
        if (!hookedClasses.add(capabilitiesClass)) return

        // Rewrite before capabilities are stored / later parceled to apps.
        HookBridge.hookAllMethods(capabilitiesClass, "setTransportInfo", beforeHookedMethod {
            val config = currentConfig()
            if (!config.enabled) return@beforeHookedMethod
            val wifiInfo = args.firstOrNull() as? WifiInfo ?: return@beforeHookedMethod
            if (isAlreadySpoofed(wifiInfo, config) || isRedactedWifiInfo(wifiInfo)) {
                return@beforeHookedMethod
            }
            spoofedWifiInfo(config)?.let {
                args[0] = it
                HookLog.d(HookLog.Module.WIFI, "spoof NetworkCapabilities.setTransportInfo")
            }
        })

        // Same-process callers (system components) that read capabilities in-host.
        HookBridge.hookAllMethods(capabilitiesClass, "getTransportInfo", afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            val config = currentConfig()
            if (!config.enabled) return@afterHookedMethod
            val wifiInfo = result as? WifiInfo ?: return@afterHookedMethod
            if (isAlreadySpoofed(wifiInfo, config) || isRedactedWifiInfo(wifiInfo)) {
                return@afterHookedMethod
            }
            spoofedWifiInfo(config)?.let {
                result = it
                HookLog.d(HookLog.Module.WIFI, "spoof NetworkCapabilities.getTransportInfo")
            }
        })

        // NetworkCapabilities also exposes SSID via getSsid() / mSSID on modern Android.
        HookBridge.hookAllMethods(capabilitiesClass, "getSsid", afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            val config = currentConfig()
            if (!config.enabled) return@afterHookedMethod
            val current = result as? String
            if (!isVisibleSsid(current) ||
                current?.removeSurrounding("\"") == config.currentSsid
            ) {
                return@afterHookedMethod
            }
            result = quoteSsid(config.currentSsid)
            HookLog.d(HookLog.Module.WIFI, "spoof NetworkCapabilities.getSsid")
        })

        HookLog.i(HookLog.Module.WIFI, "hooked NetworkCapabilities transportInfo accessors")
    }

    private fun hookWifiNetworkAgentSpecifier(classLoader: ClassLoader) {
        val specifierClass = HookBridge.findClassIfExists(
            "android.net.wifi.WifiNetworkAgentSpecifier",
            classLoader
        ) ?: return
        if (!hookedClasses.add(specifierClass)) return

        // Specifier is built with real SSID/BSSID and embedded in NetworkCapabilities.
        // Rewrite getters so apps that inspect the specifier do not see the real AP.
        listOf("getSsid", "getBssid").forEach { methodName ->
            HookBridge.hookAllMethods(specifierClass, methodName, afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val config = currentConfig()
                if (!config.enabled) return@afterHookedMethod
                when (methodName) {
                    "getSsid" -> {
                        val current = result as? String
                        if (!isVisibleSsid(current) ||
                            current?.removeSurrounding("\"") == config.currentSsid
                        ) {
                            return@afterHookedMethod
                        }
                        result = quoteSsid(config.currentSsid)
                    }
                    "getBssid" -> {
                        val current = result as? String
                        if (current.isNullOrBlank() ||
                            current.equals(DEFAULT_MAC_ADDRESS, ignoreCase = true) ||
                            current.equals(config.currentBssid, ignoreCase = true)
                        ) {
                            return@afterHookedMethod
                        }
                        result = config.currentBssid
                    }
                }
                HookLog.d(HookLog.Module.WIFI, "spoof WifiNetworkAgentSpecifier.$methodName")
            })
        }

        // Also rewrite string representation used by dumps/logs and some OEM paths.
        HookBridge.hookAllMethods(specifierClass, "toString", afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            val config = currentConfig()
            if (!config.enabled) return@afterHookedMethod
            val text = result as? String ?: return@afterHookedMethod
            if (!text.contains("SSID=")) return@afterHookedMethod
            result = text
                .replace(Regex("""SSID="[^"]*""""), """SSID="${config.currentSsid}"""")
                .replace(Regex("""BSSID=[0-9a-fA-F:]+"""), "BSSID=${config.currentBssid}")
        })

        HookBridge.hookAllMethods(
            specifierClass,
            "writeToParcel",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = currentConfig()
                    if (!config.enabled) return
                    val specifier = param.thisObject ?: return
                    val originalSsid = runCatching {
                        HookBridge.getObjectField(specifier, "mSsid") as? String
                    }.getOrNull()
                    val originalBssid = runCatching {
                        HookBridge.getObjectField(specifier, "mBssid") as? String
                    }.getOrNull()
                    if (!isVisibleSsid(originalSsid) && originalBssid.isNullOrBlank()) return
                    if (originalSsid?.removeSurrounding("\"") == config.currentSsid &&
                        originalBssid.equals(config.currentBssid, ignoreCase = true)
                    ) {
                        return
                    }
                    param.setObjectExtra(
                        SPECIFIER_RESTORE_KEY,
                        SpecifierIdentitySnapshot(originalSsid, originalBssid)
                    )
                    runCatching {
                        HookBridge.setObjectField(specifier, "mSsid", quoteSsid(config.currentSsid))
                    }
                    runCatching {
                        HookBridge.setObjectField(specifier, "mBssid", config.currentBssid)
                    }
                    HookLog.d(HookLog.Module.WIFI, "spoof WifiNetworkAgentSpecifier.writeToParcel")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val snapshot = param.getObjectExtra(SPECIFIER_RESTORE_KEY) as? SpecifierIdentitySnapshot
                        ?: return
                    val specifier = param.thisObject ?: return
                    runCatching {
                        HookBridge.setObjectField(specifier, "mSsid", snapshot.ssid)
                    }
                    runCatching {
                        HookBridge.setObjectField(specifier, "mBssid", snapshot.bssid)
                    }
                }
            }
        )

        HookLog.i(HookLog.Module.WIFI, "hooked WifiNetworkAgentSpecifier identity accessors")
    }

    private data class SpecifierIdentitySnapshot(
        val ssid: String?,
        val bssid: String?
    )

    @Suppress("DEPRECATION")
    private fun hookNetworkInfo(classLoader: ClassLoader) {
        val networkInfoClass = HookBridge.findClassIfExists(
            "android.net.NetworkInfo",
            classLoader
        ) ?: NetworkInfo::class.java
        if (!hookedClasses.add(networkInfoClass)) return

        HookBridge.hookAllMethods(networkInfoClass, "setExtraInfo", beforeHookedMethod {
            val config = currentConfig()
            if (!config.enabled) return@beforeHookedMethod
            val networkInfo = thisObject as? NetworkInfo ?: return@beforeHookedMethod
            if (networkInfo.type != TYPE_WIFI) return@beforeHookedMethod
            val current = args.firstOrNull() as? String
            if (!isVisibleSsid(current) ||
                current?.removeSurrounding("\"") == config.currentSsid
            ) {
                return@beforeHookedMethod
            }
            args[0] = quoteSsid(config.currentSsid)
            HookLog.d(HookLog.Module.WIFI, "spoof NetworkInfo.setExtraInfo")
        })

        HookBridge.hookAllMethods(networkInfoClass, "getExtraInfo", afterHookedMethod {
            if (hasThrowable()) return@afterHookedMethod
            val config = currentConfig()
            if (!config.enabled) return@afterHookedMethod
            val networkInfo = thisObject as? NetworkInfo ?: return@afterHookedMethod
            if (networkInfo.type != TYPE_WIFI) return@afterHookedMethod
            val current = result as? String
            if (!isVisibleSsid(current) ||
                current?.removeSurrounding("\"") == config.currentSsid
            ) {
                return@afterHookedMethod
            }
            result = quoteSsid(config.currentSsid)
            HookLog.d(HookLog.Module.WIFI, "spoof NetworkInfo.getExtraInfo")
        })

        HookBridge.hookAllMethods(
            networkInfoClass,
            "writeToParcel",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = currentConfig()
                    if (!config.enabled) return
                    val networkInfo = param.thisObject as? NetworkInfo ?: return
                    if (networkInfo.type != TYPE_WIFI) return
                    val current = runCatching {
                        HookBridge.callMethod(networkInfo, "getExtraInfo") as? String
                    }.getOrNull()
                    if (!isVisibleSsid(current) ||
                        current?.removeSurrounding("\"") == config.currentSsid
                    ) {
                        return
                    }
                    param.setObjectExtra(NETWORK_INFO_RESTORE_KEY, current)
                    runCatching {
                        HookBridge.callMethod(
                            networkInfo,
                            "setExtraInfo",
                            quoteSsid(config.currentSsid)
                        )
                    }
                    HookLog.d(HookLog.Module.WIFI, "spoof NetworkInfo.writeToParcel extraInfo")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.getObjectExtra(NETWORK_INFO_RESTORE_KEY) as? String
                        ?: return
                    val networkInfo = param.thisObject as? NetworkInfo ?: return
                    runCatching {
                        HookBridge.callMethod(networkInfo, "setExtraInfo", original)
                    }
                }
            }
        )

        HookLog.i(HookLog.Module.WIFI, "hooked NetworkInfo extraInfo accessors")
    }

    private fun hookWifiInfoWriteToParcel(classLoader: ClassLoader) {
        val wifiInfoClass = HookBridge.findClassIfExists(
            "android.net.wifi.WifiInfo",
            classLoader
        ) ?: WifiInfo::class.java
        if (!hookedClasses.add(wifiInfoClass)) return

        // Critical path: NetworkCapabilities parcels nested WifiInfo to app processes.
        // Temporarily rewrite identity for the parcel write, then restore so the
        // Wi-Fi stack keeps its real in-process connection state.
        HookBridge.hookAllMethods(
            wifiInfoClass,
            "writeToParcel",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = currentConfig()
                    if (!config.enabled) return
                    val wifiInfo = param.thisObject ?: return
                    if (isAlreadySpoofed(wifiInfo, config) || isRedactedWifiInfo(wifiInfo)) return
                    val snapshot = captureIdentity(wifiInfo) ?: return
                    applySpoofedIdentity(wifiInfo, config)
                    param.setObjectExtra(WIFI_INFO_RESTORE_KEY, snapshot)
                    HookLog.d(HookLog.Module.WIFI, "spoof WifiInfo.writeToParcel identity")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val snapshot = param.getObjectExtra(WIFI_INFO_RESTORE_KEY) as? WifiIdentitySnapshot
                        ?: return
                    val wifiInfo = param.thisObject ?: return
                    restoreIdentity(wifiInfo, snapshot)
                }
            }
        )

        HookLog.i(HookLog.Module.WIFI, "hooked WifiInfo.writeToParcel for connectivity leaks")
    }

    private data class WifiIdentitySnapshot(
        val wifiSsid: Any?,
        val ssid: Any?,
        val bssid: Any?,
        val macAddress: Any?
    )

    private fun captureIdentity(wifiInfo: Any): WifiIdentitySnapshot? {
        return runCatching {
            WifiIdentitySnapshot(
                wifiSsid = runCatching { HookBridge.getObjectField(wifiInfo, "mWifiSsid") }.getOrNull(),
                ssid = runCatching { HookBridge.getObjectField(wifiInfo, "mSSID") }.getOrNull(),
                bssid = runCatching { HookBridge.getObjectField(wifiInfo, "mBSSID") }.getOrNull(),
                macAddress = runCatching { HookBridge.getObjectField(wifiInfo, "mMacAddress") }.getOrNull()
            )
        }.getOrNull()
    }

    private fun restoreIdentity(wifiInfo: Any, snapshot: WifiIdentitySnapshot) {
        runCatching { HookBridge.setObjectField(wifiInfo, "mWifiSsid", snapshot.wifiSsid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mSSID", snapshot.ssid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mBSSID", snapshot.bssid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mMacAddress", snapshot.macAddress) }
        // Prefer setters when fields are private/final on some OEM builds.
        runCatching { HookBridge.callMethod(wifiInfo, "setSSID", snapshot.wifiSsid ?: snapshot.ssid) }
        runCatching {
            (snapshot.bssid as? String)?.let { HookBridge.callMethod(wifiInfo, "setBSSID", it) }
        }
        runCatching {
            (snapshot.macAddress as? String)?.let {
                HookBridge.callMethod(wifiInfo, "setMacAddress", it)
            }
        }
    }

    private fun applySpoofedIdentity(wifiInfo: Any, config: WifiHookConfig) {
        val wifiSsid = wifiSsidValue(config.currentSsid)
        runCatching { HookBridge.callMethod(wifiInfo, "setSSID", wifiSsid) }
        runCatching { HookBridge.callMethod(wifiInfo, "setBSSID", config.currentBssid) }
        runCatching { HookBridge.callMethod(wifiInfo, "setMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mWifiSsid", wifiSsid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mBSSID", config.currentBssid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mMacAddress", DEFAULT_MAC_ADDRESS) }
        // Newer Android stores SSID via WifiSsid / mWifiSsid; also try string field.
        runCatching { HookBridge.setObjectField(wifiInfo, "mSSID", config.currentSsid) }
    }

    private fun isAlreadySpoofed(value: Any, config: WifiHookConfig): Boolean {
        val bssid = readBssidField(value)
        val ssid = readSsidField(value)
        return bssid.equals(config.currentBssid, ignoreCase = true) &&
            (ssid == null || ssid == config.currentSsid)
    }

    private fun isRedactedWifiInfo(value: Any?): Boolean {
        value ?: return true
        val bssid = readBssidField(value)
        val ssid = readSsidField(value)
        val bssidRedacted = bssid.isNullOrBlank() ||
            bssid.equals(DEFAULT_MAC_ADDRESS, ignoreCase = true)
        val ssidRedacted = ssid.isNullOrBlank() ||
            ssid.equals(UNKNOWN_SSID, ignoreCase = true)
        return bssidRedacted && ssidRedacted
    }

    private fun readBssidField(value: Any): String? {
        return runCatching { HookBridge.getObjectField(value, "mBSSID") as? String }.getOrNull()
            ?: runCatching { HookBridge.callMethod(value, "getBSSID") as? String }.getOrNull()
    }

    private fun readSsidField(value: Any): String? {
        val fromField = runCatching { HookBridge.getObjectField(value, "mWifiSsid") }.getOrNull()
            ?.let { wifiSsidToString(it) }
        if (!fromField.isNullOrBlank()) return fromField
        val mSsid = runCatching { HookBridge.getObjectField(value, "mSSID") as? String }.getOrNull()
        if (!mSsid.isNullOrBlank()) return mSsid.removeSurrounding("\"")
        return runCatching { HookBridge.callMethod(value, "getSSID") as? String }.getOrNull()
            ?.removeSurrounding("\"")
    }

    private fun wifiSsidToString(wifiSsid: Any): String? {
        return runCatching { HookBridge.callMethod(wifiSsid, "toString") as? String }.getOrNull()
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() }
    }

    private fun isVisibleSsid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val plain = value.removeSurrounding("\"")
        return plain.isNotBlank() && !plain.equals(UNKNOWN_SSID, ignoreCase = true)
    }

    private fun quoteSsid(ssid: String): String {
        return if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid else "\"$ssid\""
    }

    private fun wifiSsidValue(ssid: String): Any? {
        val wifiSsidClass = HookBridge.findClassIfExists("android.net.wifi.WifiSsid", null)
            ?: return null
        return runCatching {
            HookBridge.callStaticMethod(wifiSsidClass, "fromBytes", ssid.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: runCatching {
            HookBridge.callStaticMethod(wifiSsidClass, "fromUtf8Text", ssid)
        }.getOrNull() ?: runCatching {
            HookBridge.callStaticMethod(wifiSsidClass, "createFromByteArray", ssid.toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun beforeHookedMethod(
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    private fun afterHookedMethod(
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    private companion object {
        private const val TYPE_WIFI = 1
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val WIFI_INFO_RESTORE_KEY = "arirang.wifiInfo.restore"
        private const val NETWORK_INFO_RESTORE_KEY = "arirang.networkInfo.extraInfo.restore"
        private const val SPECIFIER_RESTORE_KEY = "arirang.wifiSpecifier.restore"
    }
}
