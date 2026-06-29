package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.HookBridge

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo

internal fun spoofedWifiInfo(config: WifiHookConfig): Any {
    return HookBridge.newInstance(WifiInfo::class.java).also { wifiInfo ->
        val wifiSsid = wifiSsid(config.currentSsid)
        runCatching { HookBridge.callMethod(wifiInfo, "setSSID", wifiSsid) }
        runCatching { HookBridge.callMethod(wifiInfo, "setBSSID", config.currentBssid) }
        runCatching { HookBridge.callMethod(wifiInfo, "setMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.callMethod(wifiInfo, "setRssi", -42) }
        runCatching { HookBridge.callMethod(wifiInfo, "setFrequency", 2412) }
        runCatching { HookBridge.callMethod(wifiInfo, "setNetworkId", 0) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mWifiSsid", wifiSsid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mBSSID", config.currentBssid) }
        runCatching { HookBridge.setObjectField(wifiInfo, "mMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.setIntField(wifiInfo, "mRssi", -42) }
        runCatching { HookBridge.setIntField(wifiInfo, "mFrequency", 2412) }
        runCatching { HookBridge.setIntField(wifiInfo, "mNetworkId", 0) }
    }
}

@Suppress("DEPRECATION")
internal fun spoofedScanResults(config: WifiHookConfig): List<ScanResult> {
    if (config.hideScanResults) return emptyList()
    return config.scanResults.map {
        ScanResult().apply {
            SSID = it.ssid
            runCatching {
                HookBridge.setObjectField(this, "wifiSsid", wifiSsid(it.ssid))
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
    val sliceClass = HookBridge.findClassIfExists("com.android.modules.utils.ParceledListSlice", classLoader)
        ?: HookBridge.findClassIfExists("com.android.modules.utils.ParceledListSlice", null)
        ?: HookBridge.findClassIfExists("android.content.pm.ParceledListSlice", classLoader)
        ?: HookBridge.findClassIfExists("android.content.pm.ParceledListSlice", null)
        ?: return null
    return HookBridge.newInstance(sliceClass, list)
}

private fun wifiSsid(ssid: String): Any? {
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
