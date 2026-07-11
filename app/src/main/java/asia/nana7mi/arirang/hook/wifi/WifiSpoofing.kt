package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.HookBridge

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo

internal fun spoofedWifiInfo(config: WifiHookConfig): Any? {
    val wifiInfo = runCatching { HookBridge.newInstance(WifiInfo::class.java) }.getOrNull() ?: return null
    wifiInfo.also {
        val wifiSsid = wifiSsid(config.currentSsid)
        runCatching { HookBridge.callMethod(it, "setSSID", wifiSsid) }
        runCatching { HookBridge.callMethod(it, "setBSSID", config.currentBssid) }
        runCatching { HookBridge.callMethod(it, "setMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.callMethod(it, "setRssi", -42) }
        runCatching { HookBridge.callMethod(it, "setFrequency", 2412) }
        runCatching { HookBridge.callMethod(it, "setNetworkId", 0) }
        runCatching { HookBridge.setObjectField(it, "mWifiSsid", wifiSsid) }
        runCatching { HookBridge.setObjectField(it, "mBSSID", config.currentBssid) }
        runCatching { HookBridge.setObjectField(it, "mMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.setIntField(it, "mRssi", -42) }
        runCatching { HookBridge.setIntField(it, "mFrequency", 2412) }
        runCatching { HookBridge.setIntField(it, "mNetworkId", 0) }
    }
    val bssid = runCatching { HookBridge.callMethod(wifiInfo, "getBSSID") as? String }.getOrNull()
    return wifiInfo.takeIf { bssid.equals(config.currentBssid, ignoreCase = true) }
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
