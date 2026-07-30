package asia.nana7mi.arirang.ui.component.vpn

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs.SpoofedTransport
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.AppIcon

@StringRes
internal fun SpoofedTransport.labelRes(): Int = when (this) {
    SpoofedTransport.UNCHANGED -> R.string.vpn_transport_unchanged
    SpoofedTransport.WIFI -> R.string.vpn_transport_wifi
    SpoofedTransport.CELLULAR -> R.string.vpn_transport_cellular
    SpoofedTransport.ETHERNET -> R.string.vpn_transport_ethernet
}

/**
 * One package on the exemption list.
 *
 * [app] is null when the package is still loading or is no longer installed; the
 * row then shows the bare package name so an entry for an uninstalled app is
 * still visible and removable rather than silently vanishing from the UI while
 * remaining in the config.
 */
@Composable
internal fun ExemptAppRow(
    packageName: String,
    app: AppEntry?,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app != null) {
            AppIcon(app, size = 32.dp)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app?.label ?: packageName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (app != null) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.vpn_remove_app),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
