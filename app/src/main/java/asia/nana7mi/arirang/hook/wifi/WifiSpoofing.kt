package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.BaseHookModule

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo

internal fun spoofedWifiInfo(config: WifiHookConfig): Any {
    return BaseHookModule.newInstance(WifiInfo::class.java).also { wifiInfo ->
        val wifiSsid = wifiSsid(config.currentSsid)
        runCatching { BaseHookModule.callMethod(wifiInfo, "setSSID", wifiSsid) }
        runCatching { BaseHookModule.callMethod(wifiInfo, "setBSSID", config.currentBssid) }
        runCatching { BaseHookModule.callMethod(wifiInfo, "setMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { BaseHookModule.callMethod(wifiInfo, "setRssi", -42) }
        runCatching { BaseHookModule.callMethod(wifiInfo, "setFrequency", 2412) }
        runCatching { BaseHookModule.callMethod(wifiInfo, "setNetworkId", 0) }
        runCatching { BaseHookModule.setObjectField(wifiInfo, "mWifiSsid", wifiSsid) }
        runCatching { BaseHookModule.setObjectField(wifiInfo, "mBSSID", config.currentBssid) }
        runCatching { BaseHookModule.setObjectField(wifiInfo, "mMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { BaseHookModule.setIntField(wifiInfo, "mRssi", -42) }
        runCatching { BaseHookModule.setIntField(wifiInfo, "mFrequency", 2412) }
        runCatching { BaseHookModule.setIntField(wifiInfo, "mNetworkId", 0) }
    }
}

@Suppress("DEPRECATION")
internal fun spoofedScanResults(config: WifiHookConfig): List<ScanResult> {
    if (config.hideScanResults) return emptyList()
    return config.scanResults.map {
        ScanResult().apply {
            SSID = it.ssid
            runCatching {
                BaseHookModule.setObjectField(this, "wifiSsid", wifiSsid(it.ssid))
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
    val sliceClass = BaseHookModule.findClassIfExists("com.android.modules.utils.ParceledListSlice", classLoader)
        ?: BaseHookModule.findClassIfExists("com.android.modules.utils.ParceledListSlice", null)
        ?: BaseHookModule.findClassIfExists("android.content.pm.ParceledListSlice", classLoader)
        ?: BaseHookModule.findClassIfExists("android.content.pm.ParceledListSlice", null)
        ?: return null
    return BaseHookModule.newInstance(sliceClass, list)
}

private fun wifiSsid(ssid: String): Any? {
    val wifiSsidClass = BaseHookModule.findClassIfExists("android.net.wifi.WifiSsid", null)
        ?: return null
    return runCatching {
        BaseHookModule.callStaticMethod(wifiSsidClass, "fromBytes", ssid.toByteArray(Charsets.UTF_8))
    }.getOrNull() ?: runCatching {
        BaseHookModule.callStaticMethod(wifiSsidClass, "fromUtf8Text", ssid)
    }.getOrNull() ?: runCatching {
        BaseHookModule.callStaticMethod(wifiSsidClass, "createFromByteArray", ssid.toByteArray(Charsets.UTF_8))
    }.getOrNull()
}
