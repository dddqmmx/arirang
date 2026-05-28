package asia.nana7mi.arirang.hook

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.SystemClock
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Feasibility hook for rewriting the current Wi-Fi identity and nearby Wi-Fi scan results.
 *
 * Hook points are based on AOSP Wi-Fi sources:
 * - WifiManager.getConnectionInfo() -> IWifiManager.getConnectionInfo(callingPackage, featureId)
 * - WifiManager.getScanResults() -> IWifiManager.getScanResults(callingPackage, featureId)
 * - WifiServiceImpl.getConnectionInfo(String, String)
 * - WifiServiceImpl.getScanResults(String, String)
 * - WifiServiceImpl.mScanRequestProxy.getScanResults()
 */
class FuckWifi : BaseHookModule(
    targetPackages = setOf("android", "com.android.wifi")
) {
    private data class WifiConfig(
        val enabled: Boolean = true,
        val currentSsid: String = WifiConfigPrefs.DEFAULT_CURRENT_SSID,
        val currentBssid: String = WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
        val hideScanResults: Boolean = false,
        val scanResults: List<ScanNetwork> = listOf(ScanNetwork())
    )

    private data class ScanNetwork(
        val ssid: String = WifiConfigPrefs.DEFAULT_SCAN_SSID,
        val bssid: String = WifiConfigPrefs.DEFAULT_SCAN_BSSID
    )

    private val hardcodedConfig = WifiConfig(
        currentSsid = "1919810",
        currentBssid = "02:00:00:19:19:81",
        scanResults = listOf(
            ScanNetwork(ssid = "114514", bssid = "02:00:00:11:45:14"),
            ScanNetwork(ssid = "114", bssid = "02:00:00:00:01:14")
        )
    )
    private val configFile = HookConfigFile(
        configName = "wifi",
        prefsName = WifiConfigPrefs.PREFS_NAME,
        defaultValue = WifiConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            HookNotifyClient.readConfigSnapshot(
                configName = "wifi",
                force = force,
                allowBind = true,
                logName = "Wi-Fi"
            )
        },
        parseRealtimeSnapshot = ::parseConfigSnapshot,
        readStoredConfig = ::readConfigFromPrefs
    )
    private val hookedServiceClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedScanRequestProxyClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedWifiSystemServiceClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    @Volatile
    private var systemServiceManagerHookInstalled = false

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            HookLog.i(
                HookLog.Module.WIFI,
                "installing Wi-Fi hooks for ${lpparam.packageName} classLoader=${lpparam.classLoader}"
            )
            hookWifiService(lpparam.classLoader)
            hookWifiSystemServiceManager(lpparam.classLoader)
            HookLog.i(HookLog.Module.WIFI, "Wi-Fi privacy hook installed for ${lpparam.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.WIFI, "Wi-Fi privacy hook failed for ${lpparam.packageName}", it)
        }
    }

    private fun hookWifiService(classLoader: ClassLoader) {
        val serviceClasses = WIFI_SERVICE_CLASS_NAMES.mapNotNull { className ->
            XposedHelpers.findClassIfExists(className, classLoader).also { foundClass ->
                HookLog.d(
                    HookLog.Module.WIFI,
                    "lookup $className in $classLoader -> ${foundClass?.name ?: "null"}"
                )
            }
        }.distinct()

        if (serviceClasses.isEmpty()) {
            HookLog.w(
                HookLog.Module.WIFI,
                "Wi-Fi service class not found in $classLoader; waiting for SystemServiceManager.startServiceFromJar"
            )
            return
        }

        serviceClasses.forEach { hookWifiServiceClass(classLoader, it) }
    }

    private fun hookWifiSystemServiceManager(classLoader: ClassLoader) {
        synchronized(this) {
            if (systemServiceManagerHookInstalled) return
            systemServiceManagerHookInstalled = true
        }

        val managerClass = XposedHelpers.findClassIfExists(
            "com.android.server.SystemServiceManager",
            classLoader
        )
        if (managerClass == null) {
            HookLog.w(HookLog.Module.WIFI, "SystemServiceManager class not found in $classLoader")
            return
        }

        XposedBridge.hookAllMethods(managerClass, "startServiceFromJar", afterHookedMethod {
            val className = args.firstOrNull() as? String ?: return@afterHookedMethod
            val jarPath = args.getOrNull(1) as? String
            if (className != WIFI_SYSTEM_SERVICE_CLASS) return@afterHookedMethod

            val wifiService = result ?: run {
                HookLog.w(HookLog.Module.WIFI, "WifiService startServiceFromJar returned null")
                return@afterHookedMethod
            }
            HookLog.i(
                HookLog.Module.WIFI,
                "WifiService started from jar serviceClass=$className jarPath=$jarPath class=${wifiService.javaClass.name} classLoader=${wifiService.javaClass.classLoader}"
            )
            hookWifiSystemServiceClass(wifiService.javaClass)
            hookWifiServiceInstance(wifiService)
        })
        HookLog.i(HookLog.Module.WIFI, "installed SystemServiceManager.startServiceFromJar watcher for Wi-Fi service")
    }

    private fun hookWifiSystemServiceClass(wifiServiceClass: Class<*>) {
        synchronized(hookedWifiSystemServiceClasses) {
            if (!hookedWifiSystemServiceClasses.add(wifiServiceClass)) return
        }

        wifiServiceClass.declaredMethods
            .filter { it.name == "onStart" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                XposedBridge.hookMethod(method, afterHookedMethod {
                    HookLog.i(HookLog.Module.WIFI, "WifiService.onStart observed; resolving WifiServiceImpl")
                    hookWifiServiceInstance(thisObject)
                })
            }
    }

    private fun hookWifiServiceInstance(wifiService: Any) {
        val impl = runCatching {
            XposedHelpers.getObjectField(wifiService, "mImpl")
        }.getOrNull() ?: wifiService.javaClass.declaredFields
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(wifiService)?.takeIf { it.javaClass.name == WIFI_SERVICE_IMPL_CLASS }
                }.getOrNull()
            }

        if (impl == null) {
            HookLog.w(
                HookLog.Module.WIFI,
                "WifiService mImpl not found fields=${wifiService.javaClass.declaredFields.joinToString { "${it.type.name} ${it.name}" }}"
            )
            return
        }

        HookLog.i(
            HookLog.Module.WIFI,
            "found WifiServiceImpl instance class=${impl.javaClass.name} classLoader=${impl.javaClass.classLoader}"
        )
        hookWifiServiceClass(impl.javaClass.classLoader ?: wifiService.javaClass.classLoader, impl.javaClass)
        hookScanRequestProxyFromWifiServiceImpl(impl)
    }

    private fun hookScanRequestProxyFromWifiServiceImpl(wifiServiceImpl: Any) {
        val scanRequestProxy = runCatching {
            XposedHelpers.getObjectField(wifiServiceImpl, "mScanRequestProxy")
        }.getOrNull()

        if (scanRequestProxy == null) {
            HookLog.w(
                HookLog.Module.WIFI,
                "WifiServiceImpl mScanRequestProxy not found fields=${wifiServiceImpl.javaClass.declaredFields.joinToString { "${it.type.name} ${it.name}" }}"
            )
            return
        }

        HookLog.i(
            HookLog.Module.WIFI,
            "found ScanRequestProxy instance class=${scanRequestProxy.javaClass.name} classLoader=${scanRequestProxy.javaClass.classLoader}"
        )
        hookScanRequestProxyClass(scanRequestProxy.javaClass)
    }

    private fun hookScanRequestProxyClass(scanRequestProxyClass: Class<*>) {
        synchronized(hookedScanRequestProxyClasses) {
            if (!hookedScanRequestProxyClasses.add(scanRequestProxyClass)) {
                HookLog.d(HookLog.Module.WIFI, "ScanRequestProxy class already hooked: ${scanRequestProxyClass.name}")
                return
            }
        }

        val candidateMethods = scanRequestProxyClass.declaredMethods
            .filter { it.name == "getScanResults" }
        HookLog.i(
            HookLog.Module.WIFI,
            "ScanRequestProxy ${scanRequestProxyClass.name} candidates=${candidateMethods.joinToString { it.signature() }}"
        )

        var hookedScanResults = 0
        candidateMethods
            .filter { it.returnsList() }
            .forEach { method ->
                XposedBridge.hookMethod(method, beforeHookedMethod {
                    val config = currentConfig()
                    if (!config.enabled) return@beforeHookedMethod
                    HookLog.i(HookLog.Module.WIFI, "spoof ScanRequestProxy.getScanResults via ${method.signature()}")
                    result = spoofedScanResults(config)
                })
                hookedScanResults++
            }

        HookLog.i(
            HookLog.Module.WIFI,
            "hooked ScanRequestProxy ${scanRequestProxyClass.name} scanResults=$hookedScanResults"
        )
    }

    private fun hookWifiServiceClass(classLoader: ClassLoader, serviceClass: Class<*>) {
        synchronized(hookedServiceClasses) {
            if (!hookedServiceClasses.add(serviceClass)) {
                HookLog.d(HookLog.Module.WIFI, "Wi-Fi service class already hooked: ${serviceClass.name}")
                return
            }
        }

        var hookedConnectionInfo = 0
        var hookedScanResults = 0
        val candidateMethods = serviceClass.declaredMethods
            .filter { it.name == "getConnectionInfo" || it.name == "getScanResults" }
        HookLog.i(
            HookLog.Module.WIFI,
            "Wi-Fi service ${serviceClass.name} candidates=${candidateMethods.joinToString { it.signature() }}"
        )

        candidateMethods.forEach { method ->
            when {
                method.name == "getConnectionInfo" && method.returnsWifiInfo() -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        HookLog.i(HookLog.Module.WIFI, "spoof getConnectionInfo via ${method.signature()}")
                        result = spoofedWifiInfo(config)
                    })
                    hookedConnectionInfo++
                }

                method.name == "getScanResults" && method.returnsScanResults() -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        HookLog.i(HookLog.Module.WIFI, "spoof getScanResults via ${method.signature()}")
                        result = method.wrapScanResults(classLoader, spoofedScanResults(config))
                    })
                    hookedScanResults++
                }
            }
        }

        HookLog.i(
            HookLog.Module.WIFI,
            "hooked Wi-Fi service ${serviceClass.name} connectionInfo=$hookedConnectionInfo scanResults=$hookedScanResults"
        )
    }

    private fun Method.wrapScanResults(classLoader: ClassLoader, results: List<ScanResult>): Any? {
        return when {
            returnsParceledListSlice() -> parceledListSlice(classLoader, results)
            returnsList() -> results
            else -> null
        }
    }

    private fun currentConfig(): WifiConfig {
        if (DEBUG_HARDCODED_CONFIG) return hardcodedConfig
        return configFile.current()
    }

    private fun parseConfigSnapshot(snapshot: String): WifiConfig? {
        return runCatching {
            val json = JSONObject(snapshot)
            WifiConfig(
                enabled = json.optBoolean(WifiConfigPrefs.KEY_ENABLED, true),
                currentSsid = json.optString(WifiConfigPrefs.KEY_CURRENT_SSID, "")
                    .takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_SSID,
                currentBssid = json.optString(WifiConfigPrefs.KEY_CURRENT_BSSID, "")
                    .takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
                hideScanResults = json.optBoolean(WifiConfigPrefs.KEY_HIDE_SCAN_RESULTS, false),
                scanResults = parseScanResults(
                    json.optJSONArray(WifiConfigPrefs.KEY_SCAN_RESULTS)?.toString(),
                    json.optString(WifiConfigPrefs.KEY_SCAN_SSID).takeIf { it.isNotBlank() },
                    json.optString(WifiConfigPrefs.KEY_SCAN_BSSID).takeIf { it.isNotBlank() }
                )
            )
        }.onFailure {
            HookLog.w(HookLog.Module.WIFI, "failed to parse Wi-Fi config snapshot: ${it.message}")
        }.getOrNull()
    }

    private fun readConfigFromPrefs(prefs: de.robv.android.xposed.XSharedPreferences): WifiConfig {
        return WifiConfig(
            enabled = prefs.getBoolean(WifiConfigPrefs.KEY_ENABLED, true),
            currentSsid = prefs.getString(WifiConfigPrefs.KEY_CURRENT_SSID, null)
                ?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_SSID,
            currentBssid = prefs.getString(WifiConfigPrefs.KEY_CURRENT_BSSID, null)
                ?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_CURRENT_BSSID,
            hideScanResults = prefs.getBoolean(WifiConfigPrefs.KEY_HIDE_SCAN_RESULTS, false),
            scanResults = parseScanResults(
                prefs.getString(WifiConfigPrefs.KEY_SCAN_RESULTS, null),
                prefs.getString(WifiConfigPrefs.KEY_SCAN_SSID, null),
                prefs.getString(WifiConfigPrefs.KEY_SCAN_BSSID, null)
            )
        )
    }

    private fun spoofedWifiInfo(config: WifiConfig): Any {
        return XposedHelpers.newInstance(WifiInfo::class.java).also { wifiInfo ->
            val wifiSsid = wifiSsid(config.currentSsid)
            runCatching { XposedHelpers.callMethod(wifiInfo, "setSSID", wifiSsid) }
            runCatching { XposedHelpers.callMethod(wifiInfo, "setBSSID", config.currentBssid) }
            runCatching { XposedHelpers.callMethod(wifiInfo, "setMacAddress", DEFAULT_MAC_ADDRESS) }
            runCatching { XposedHelpers.callMethod(wifiInfo, "setRssi", -42) }
            runCatching { XposedHelpers.callMethod(wifiInfo, "setFrequency", 2412) }
            runCatching { XposedHelpers.callMethod(wifiInfo, "setNetworkId", 0) }
            runCatching { XposedHelpers.setObjectField(wifiInfo, "mWifiSsid", wifiSsid) }
            runCatching { XposedHelpers.setObjectField(wifiInfo, "mBSSID", config.currentBssid) }
            runCatching { XposedHelpers.setObjectField(wifiInfo, "mMacAddress", DEFAULT_MAC_ADDRESS) }
            runCatching { XposedHelpers.setIntField(wifiInfo, "mRssi", -42) }
            runCatching { XposedHelpers.setIntField(wifiInfo, "mFrequency", 2412) }
            runCatching { XposedHelpers.setIntField(wifiInfo, "mNetworkId", 0) }
        }
    }

    private fun spoofedScanResults(config: WifiConfig): List<ScanResult> {
        if (config.hideScanResults) return emptyList()
        return config.scanResults.map {
            ScanResult().apply {
                SSID = it.ssid
                runCatching {
                    XposedHelpers.setObjectField(this, "wifiSsid", wifiSsid(it.ssid))
                }
                BSSID = it.bssid
                capabilities = "[WPA2-PSK-CCMP][ESS]"
                level = -45
                frequency = 2412
                timestamp = System.nanoTime() / 1_000L
            }
        }
    }

    private fun parceledListSlice(classLoader: ClassLoader, list: List<ScanResult>): Any? {
        val sliceClass = XposedHelpers.findClassIfExists("com.android.modules.utils.ParceledListSlice", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.modules.utils.ParceledListSlice", null)
            ?: XposedHelpers.findClassIfExists("android.content.pm.ParceledListSlice", classLoader)
            ?: XposedHelpers.findClassIfExists("android.content.pm.ParceledListSlice", null)
            ?: return null
        return XposedHelpers.newInstance(sliceClass, list)
    }

    private fun wifiSsid(ssid: String): Any? {
        val wifiSsidClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiSsid", null)
            ?: return null
        return runCatching {
            XposedHelpers.callStaticMethod(wifiSsidClass, "fromBytes", ssid.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: runCatching {
            XposedHelpers.callStaticMethod(wifiSsidClass, "fromUtf8Text", ssid)
        }.getOrNull() ?: runCatching {
            XposedHelpers.callStaticMethod(wifiSsidClass, "createFromByteArray", ssid.toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun parseScanResults(json: String?, legacySsid: String?, legacyBssid: String?): List<ScanNetwork> {
        val parsed = runCatching {
            val array = JSONArray(json ?: "")
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ScanNetwork(
                    ssid = item.optString("ssid", WifiConfigPrefs.DEFAULT_SCAN_SSID),
                    bssid = item.optString("bssid", WifiConfigPrefs.DEFAULT_SCAN_BSSID)
                )
            }
        }.getOrDefault(emptyList())
            .filter { it.ssid.isNotBlank() || it.bssid.isNotBlank() }

        if (parsed.isNotEmpty()) return parsed
        return listOf(
            ScanNetwork(
                ssid = legacySsid?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_SCAN_SSID,
                bssid = legacyBssid?.takeIf { it.isNotBlank() } ?: WifiConfigPrefs.DEFAULT_SCAN_BSSID
            )
        )
    }

    private fun Method.returnsWifiInfo(): Boolean {
        return returnType.name == "android.net.wifi.WifiInfo"
    }

    private fun Method.returnsScanResults(): Boolean {
        return returnsParceledListSlice() || returnsList()
    }

    private fun Method.returnsParceledListSlice(): Boolean {
        return returnType.name == "com.android.modules.utils.ParceledListSlice" ||
            returnType.name == "android.content.pm.ParceledListSlice"
    }

    private fun Method.returnsList(): Boolean {
        return java.util.List::class.java.isAssignableFrom(returnType)
    }

    private fun Method.signature(): String {
        return "${returnType.name} ${declaringClass.name}.$name(${parameterTypes.joinToString { it.name }})"
    }

    private companion object {
        private const val DEBUG_HARDCODED_CONFIG = false
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
        private const val DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00"
        private const val WIFI_SYSTEM_SERVICE_CLASS = "com.android.server.wifi.WifiService"
        private const val WIFI_SERVICE_IMPL_CLASS = "com.android.server.wifi.WifiServiceImpl"
        private val WIFI_SERVICE_CLASS_NAMES = listOf(
            WIFI_SERVICE_IMPL_CLASS
        )
    }
}
