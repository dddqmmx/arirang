package asia.nana7mi.arirang.ui.component.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.BuildConfig

@Composable
fun FeatureCard(
    item: FeatureItem,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
    onFeatureClick: (Class<*>?) -> Unit
) {
    val available = BuildConfig.DEBUG || item.isReleased

    Card(
        modifier = modifier
            .alpha(if (available) 1f else 0.5f)
            .clickable {
                if (available && item.activityClass != null) {
                    onFeatureClick(item.activityClass)
                } else {
                    onUnavailable()
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = stringResource(item.titleRes),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
