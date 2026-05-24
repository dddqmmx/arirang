package asia.nana7mi.arirang.hook

import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import java.lang.reflect.Method

/**
 * Feasibility hook for rewriting the current Wi-Fi identity and nearby Wi-Fi scan results.
 *
 * Hook points are based on AOSP Wi-Fi sources:
 * - WifiManager.getConnectionInfo() -> IWifiManager.getConnectionInfo(callingPackage, featureId)
 * - WifiManager.getScanResults() -> IWifiManager.getScanResults(callingPackage, featureId)
 * - WifiServiceImpl.getConnectionInfo(String, String)
 * - WifiServiceImpl.getScanResults(String, String)
 */
class FuckWifi : BaseHookModule(
    targetPackages = setOf("android", "com.android.wifi"),
    matchClient = true
) {
    @Volatile
    private var cachedConfig = WifiConfig()

    @Volatile
    private var cachedConfigAt = 0L

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

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
                hookClientWifiApis(lpparam.classLoader)
            } else {
                hookWifiService(lpparam.classLoader)
            }
            HookLog.i(HookLog.Module.WIFI, "Wi-Fi privacy hook installed for ${lpparam.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.WIFI, "Wi-Fi privacy hook failed for ${lpparam.packageName}", it)
        }
    }

    private fun hookClientWifiApis(classLoader: ClassLoader) {
        val wifiManagerClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiManager", classLoader)
            ?: XposedHelpers.findClassIfExists("android.net.wifi.WifiManager", null)
            ?: return

        XposedBridge.hookAllMethods(wifiManagerClass, "getConnectionInfo", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = currentConfig()
                if (!config.enabled) return
                param.result = spoofedWifiInfo(config)
            }
        })

        XposedBridge.hookAllMethods(wifiManagerClass, "getScanResults", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = currentConfig()
                if (!config.enabled) return
                param.result = spoofedScanResults(config)
            }
        })

        hookNetworkCapabilitiesTransportInfo(classLoader)
        hookWifiInfoGetters(classLoader)
    }

    private fun hookWifiService(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", classLoader)
            ?: return

        serviceClass.declaredMethods
            .filter { it.name == "getConnectionInfo" && it.returnsWifiInfo() }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = currentConfig()
                        if (!config.enabled || !shouldHideForServiceCaller()) return
                        param.result = spoofedWifiInfo(config)
                    }
                })
            }

        serviceClass.declaredMethods
            .filter { it.name == "getScanResults" }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = currentConfig()
                        if (!config.enabled || !shouldHideForServiceCaller()) return
                        param.result = parceledListSlice(classLoader, spoofedScanResults(config))
                    }
                })
            }
    }

    private fun hookNetworkCapabilitiesTransportInfo(classLoader: ClassLoader) {
        val networkCapabilitiesClass = XposedHelpers.findClassIfExists(
            "android.net.NetworkCapabilities",
            classLoader
        ) ?: NetworkCapabilities::class.java

        XposedBridge.hookAllMethods(networkCapabilitiesClass, "getTransportInfo", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = currentConfig()
                if (!config.enabled) return
                if (param.result is WifiInfo) {
                    param.result = spoofedWifiInfo(config)
                }
            }
        })
    }

    private fun hookWifiInfoGetters(classLoader: ClassLoader) {
        val wifiInfoClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiInfo", classLoader)
            ?: WifiInfo::class.java

        mapOf<String, (WifiConfig) -> String?>(
            "getSSID" to { "\"${it.currentSsid}\"" },
            "getBSSID" to { it.currentBssid },
            "getMacAddress" to { DEFAULT_MAC_ADDRESS }
        ).forEach { (methodName, valueProvider) ->
            XposedBridge.hookAllMethods(wifiInfoClass, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = currentConfig()
                    if (!config.enabled) return
                    param.result = valueProvider(config)
                }
            })
        }
    }

    private fun currentConfig(): WifiConfig {
        val now = SystemClock.uptimeMillis()
        if (now - cachedConfigAt < CONFIG_REFRESH_INTERVAL_MS) return cachedConfig

        return synchronized(this) {
            val checkedAt = SystemClock.uptimeMillis()
            if (checkedAt - cachedConfigAt < CONFIG_REFRESH_INTERVAL_MS) {
                return@synchronized cachedConfig
            }
            val prefs = XSharedPreferences(BuildConfig.APPLICATION_ID, WifiConfigPrefs.PREFS_NAME).apply {
                makeWorldReadable()
                reload()
            }
            cachedConfig = WifiConfig(
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
            cachedConfigAt = checkedAt
            cachedConfig
        }
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
        val sliceClass = XposedHelpers.findClassIfExists("android.content.pm.ParceledListSlice", classLoader)
            ?: XposedHelpers.findClassIfExists("android.content.pm.ParceledListSlice", null)
            ?: return null
        return XposedHelpers.newInstance(sliceClass, list)
    }

    private fun wifiSsid(ssid: String): Any? {
        val wifiSsidClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiSsid", null)
            ?: return null
        return runCatching {
            XposedHelpers.callStaticMethod(wifiSsidClass, "fromBytes", ssid.toByteArray(Charsets.UTF_8))
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

    private fun shouldHideForServiceCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        return callingUid >= Process.FIRST_APPLICATION_UID && callingUid != Process.myUid()
    }

    private fun Method.returnsWifiInfo(): Boolean {
        return returnType.name == "android.net.wifi.WifiInfo"
    }

    private companion object {
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
        private const val DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00"
    }
}
