package asia.nana7mi.arirang.hook.util

/**
 * The hook package's one MAC-address validator.
 *
 * `(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$` was defined twice under two names — once as the Wi-Fi
 * config's `isValidMacAddress` and once as the Bluetooth one's `isValidBluetoothAddress` — with
 * the same regex, the same case-insensitivity and the same callers' expectation.
 *
 * Note the two `MODE_*` prefs readers in `data/datastore` hold their own uppercase-only copies of
 * this rule. They deliberately stay there: `data/` must not import `hook/`, and unifying across
 * that boundary needs a validator outside both packages.
 */
internal fun isValidMacAddress(value: String): Boolean = MAC_ADDRESS.matches(value)

/** The address as the six bytes AOSP framework fields expect. */
internal fun macToBytes(mac: String): ByteArray {
    require(isValidMacAddress(mac)) { "Invalid MAC address" }
    val bytes = ByteArray(6)
    val hex = mac.replace(":", "")
    if (hex.length != MAC_HEX_LENGTH) return bytes
    for (i in 0 until 6) {
        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

private const val MAC_HEX_LENGTH = 12

private val MAC_ADDRESS = Regex("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
