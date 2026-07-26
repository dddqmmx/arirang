package asia.nana7mi.arirang.hook.wifi

import asia.nana7mi.arirang.hook.core.HookBridge

import android.net.DhcpInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo

/**
 * Builds the WifiInfo callers should see.
 *
 * Prefers copying [original] and rewriting only the identity fields. WifiInfo's
 * no-arg constructor runs `reset()`, which leaves `mSupplicantState =
 * UNINITIALIZED`, `mLinkSpeed = -1`, `mCurrentSecurityType = UNKNOWN`,
 * `mWifiStandard = UNKNOWN` and roughly fifteen other fields at their defaults.
 * Only SSID/BSSID/MAC/RSSI/frequency/networkId were filled in on top, so a
 * genuinely connected device reported a valid SSID *and* "not connected": the
 * very common `info.supplicantState == SupplicantState.COMPLETED` check failed
 * and apps fell back to cellular or showed an offline UI. That combination is
 * also impossible on real hardware, making it a trivial fingerprint of the
 * module.
 *
 * When no original is available (or the hidden copy constructor is unusable)
 * the from-scratch path is kept, but the connection-state fields are seeded with
 * plausible values rather than left at their reset defaults.
 */
internal fun spoofedWifiInfo(config: WifiHookConfig, original: Any? = null): Any? {
    val copied = (original as? WifiInfo)
        ?.let { runCatching { HookBridge.newInstance(WifiInfo::class.java, it) }.getOrNull() }
    val wifiInfo = copied
        ?: runCatching { HookBridge.newInstance(WifiInfo::class.java) }.getOrNull()
        ?: return null
    if (copied == null) seedPlausibleConnectionState(wifiInfo)
    wifiInfo.also {
        val wifiSsid = wifiSsid(config.currentSsid)
        val ipInt = ipv4ToInt(config.ipAddress)
        runCatching { HookBridge.callMethod(it, "setSSID", wifiSsid) }
        runCatching { HookBridge.callMethod(it, "setBSSID", config.currentBssid) }
        runCatching { HookBridge.callMethod(it, "setMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.callMethod(it, "setRssi", -42) }
        runCatching { HookBridge.callMethod(it, "setFrequency", 2412) }
        runCatching { HookBridge.callMethod(it, "setNetworkId", 0) }
        runCatching { HookBridge.callMethod(it, "setInetAddress", java.net.InetAddress.getByName(config.ipAddress)) }
        runCatching { HookBridge.setObjectField(it, "mWifiSsid", wifiSsid) }
        runCatching { HookBridge.setObjectField(it, "mBSSID", config.currentBssid) }
        runCatching { HookBridge.setObjectField(it, "mMacAddress", DEFAULT_MAC_ADDRESS) }
        runCatching { HookBridge.setIntField(it, "mRssi", -42) }
        runCatching { HookBridge.setIntField(it, "mFrequency", 2412) }
        runCatching { HookBridge.setIntField(it, "mNetworkId", 0) }
        if (ipInt != 0) {
            runCatching { HookBridge.setIntField(it, "mIpAddress", ipInt) }
        }
    }
    val bssid = runCatching { HookBridge.callMethod(wifiInfo, "getBSSID") as? String }.getOrNull()
    return wifiInfo.takeIf { bssid.equals(config.currentBssid, ignoreCase = true) }
}

/**
 * Fills in the connection-state fields a freshly constructed WifiInfo leaves at
 * `reset()` defaults, so a spoofed object at least describes a plausible
 * association rather than an impossible one.
 */
private fun seedPlausibleConnectionState(wifiInfo: Any) {
    runCatching {
        val supplicantStateClass =
            HookBridge.findClassIfExists("android.net.wifi.SupplicantState", null)
        val completed = supplicantStateClass
            ?.let { HookBridge.getStaticObjectField(it, "COMPLETED") }
        if (completed != null) {
            HookBridge.setObjectField(wifiInfo, "mSupplicantState", completed)
        }
    }
    runCatching { HookBridge.setIntField(wifiInfo, "mLinkSpeed", 130) }
    runCatching { HookBridge.setIntField(wifiInfo, "mTxLinkSpeed", 130) }
    runCatching { HookBridge.setIntField(wifiInfo, "mRxLinkSpeed", 130) }
    // WifiInfo.WIFI_STANDARD_11N
    runCatching { HookBridge.setIntField(wifiInfo, "mWifiStandard", 4) }
    // WifiInfo.SECURITY_TYPE_PSK
    runCatching { HookBridge.setIntField(wifiInfo, "mCurrentSecurityType", 2) }
}

@Suppress("DEPRECATION")
internal fun spoofedDhcpInfo(config: WifiHookConfig): DhcpInfo {
    return DhcpInfo().apply {
        ipAddress = ipv4ToInt(config.ipAddress)
        gateway = ipv4ToInt(config.gateway)
        netmask = ipv4ToInt("255.255.255.0")
        dns1 = ipv4ToInt(config.dns1)
        dns2 = ipv4ToInt(config.dns2)
        serverAddress = ipv4ToInt(config.gateway)
        leaseDuration = 3_600
    }
}

internal fun ipv4ToInt(value: String): Int {
    val parts = value.trim().split('.')
    if (parts.size != 4) return 0
    var result = 0
    parts.forEachIndexed { index, part ->
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return 0
        result = result or (octet shl (8 * index))
    }
    return result
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
