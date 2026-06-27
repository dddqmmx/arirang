package asia.nana7mi.arirang.hook.wifi

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import de.robv.android.xposed.XposedHelpers

internal fun spoofedWifiInfo(config: WifiHookConfig): Any {
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

@Suppress("DEPRECATION")
internal fun spoofedScanResults(config: WifiHookConfig): List<ScanResult> {
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

internal fun parceledListSlice(classLoader: ClassLoader, list: List<ScanResult>): Any? {
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
