package asia.nana7mi.arirang.ui.component.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.theme.StatusWarning

@Composable
fun StatusCard(activated: Boolean, submoduleVersion: String?) {
    val containerColor: Color
    val contentColor: Color
    val statusIcon: ImageVector
    val statusText: String
    val versionText: String?

    when {
        activated && submoduleVersion != null -> {
            containerColor = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
            statusIcon = Icons.Default.CheckCircle
            statusText = stringResource(R.string.status_normal)
            versionText = stringResource(R.string.status_submodule_version, submoduleVersion)
        }
        activated -> {
            containerColor = StatusWarning
            contentColor = Color(0xFF332B00)
            statusIcon = Icons.Default.Warning
            statusText = stringResource(R.string.status_submodule_missing)
            versionText = null
        }
        else -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainer
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            statusIcon = Icons.Default.Info
            statusText = stringResource(R.string.status_deactivated)
            versionText = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.status_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                if (versionText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = versionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                }
            }
        }
    }
}
