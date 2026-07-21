package asia.nana7mi.arirang.ui.component.wifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import java.security.SecureRandom

@Composable
internal fun ScanNetworkEditor(
    index: Int,
    network: WifiConfigPrefs.ScanNetwork,
    expanded: Boolean,
    canRemove: Boolean,
    onExpandedChange: () -> Unit,
    onNetworkChange: (WifiConfigPrefs.ScanNetwork) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wifi_scan_result_title, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOf(network.ssid, network.bssid)
                        .filter { it.isNotBlank() }
                        .joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onExpandedChange) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.wifi_remove_scan_result),
                    tint = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WifiTextField(
                    label = stringResource(R.string.wifi_field_scan_ssid),
                    value = network.ssid,
                    onValueChange = { onNetworkChange(network.copy(ssid = it)) }
                )
                WifiTextField(
                    label = stringResource(R.string.wifi_field_scan_bssid),
                    value = network.bssid,
                    onValueChange = { onNetworkChange(network.copy(bssid = it)) },
                    onRandom = { onNetworkChange(network.copy(bssid = randomBssid())) }
                )
            }
        }
    }
}

@Composable
internal fun WifiTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onRandom: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = {
            if (onRandom != null) {
                IconButton(onClick = onRandom) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_randomize))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private val wifiRandom = SecureRandom()

internal fun randomBssid(): String = buildString {
    repeat(6) { i ->
        if (i > 0) append(':')
        // Keep locally-administered unicast bit so values look like valid soft MACs.
        val octet = if (i == 0) {
            (wifiRandom.nextInt(256) and 0xFE) or 0x02
        } else {
            wifiRandom.nextInt(256)
        }
        append("%02X".format(octet))
    }
}

/** Private RFC1918 host in 10.x / 172.16-31.x / 192.168.x. */
internal fun randomPrivateIpv4(): String {
    return when (wifiRandom.nextInt(3)) {
        0 -> "10.${wifiRandom.nextInt(256)}.${wifiRandom.nextInt(256)}.${hostOctet()}"
        1 -> "172.${16 + wifiRandom.nextInt(16)}.${wifiRandom.nextInt(256)}.${hostOctet()}"
        else -> "192.168.${wifiRandom.nextInt(256)}.${hostOctet()}"
    }
}

private val COMMON_DNS_PAIRS = listOf(
    "1.1.1.1" to "1.0.0.1",           // Cloudflare
    "8.8.8.8" to "8.8.4.4",           // Google
    "9.9.9.9" to "149.112.112.112",   // Quad9
    "208.67.222.222" to "208.67.220.220", // OpenDNS
    "94.140.14.14" to "94.140.15.15", // AdGuard
    "8.26.56.26" to "8.20.247.20",    // Comodo
    "76.76.2.0" to "76.76.10.0",      // Control D
    "185.228.168.9" to "185.228.169.9" // CleanBrowsing
)

/**
 * Random gateway + host IP on the same /24, plus a well-known public DNS pair.
 * Host is never .0/.1/.255 and never equal to the gateway.
 */
internal fun randomLanAddresses(): LanAddresses {
    val base = when (wifiRandom.nextInt(3)) {
        0 -> "10.${wifiRandom.nextInt(256)}.${wifiRandom.nextInt(256)}"
        1 -> "172.${16 + wifiRandom.nextInt(16)}.${wifiRandom.nextInt(256)}"
        else -> "192.168.${wifiRandom.nextInt(256)}"
    }
    val gatewayHost = 1 + wifiRandom.nextInt(3) // .1 / .2 / .3
    var host = hostOctet()
    while (host == gatewayHost) {
        host = hostOctet()
    }
    val dns = COMMON_DNS_PAIRS.random(wifiRandom)
    return LanAddresses(
        ipAddress = "$base.$host",
        gateway = "$base.$gatewayHost",
        dns1 = dns.first,
        dns2 = dns.second
    )
}

/** Pick a well-known public DNS pair only. */
internal fun randomDnsPair(): Pair<String, String> = COMMON_DNS_PAIRS.random(wifiRandom)

internal data class LanAddresses(
    val ipAddress: String,
    val gateway: String,
    val dns1: String,
    val dns2: String
)

private fun hostOctet(): Int = 2 + wifiRandom.nextInt(253) // 2..254

private fun <T> List<T>.random(random: SecureRandom): T = this[random.nextInt(size)]
