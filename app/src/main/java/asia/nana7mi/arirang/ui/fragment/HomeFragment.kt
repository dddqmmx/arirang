package asia.nana7mi.arirang.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.activity.ClipboardConfigActivity
import asia.nana7mi.arirang.ui.activity.DeviceInfoConfigActivity
import asia.nana7mi.arirang.ui.activity.LocationConfigActivity
import asia.nana7mi.arirang.ui.activity.PackageListConfigActivity
import asia.nana7mi.arirang.ui.activity.SelfCheckActivity
import asia.nana7mi.arirang.ui.activity.SimConfigActivity
import asia.nana7mi.arirang.ui.activity.UniqueIdentifierConfigActivity
import asia.nana7mi.arirang.ui.activity.WifiConfigActivity
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArirangTheme {
                    HomeScreen(
                        activated = isXposedActivation(),
                        onFeatureClick = ::openFeature
                    )
                }
            }
        }
    }

    private fun openFeature(activityClass: Class<*>?) {
        activityClass ?: return
        startActivity(Intent(requireContext(), activityClass))
    }

    fun isXposedActivation(): Boolean {
        return false
    }
}

private data class FeatureItem(
    val titleRes: Int,
    val icon: ImageVector,
    val activityClass: Class<*>?,
    val isReleased: Boolean
)

@Composable
private fun HomeScreen(
    activated: Boolean,
    onFeatureClick: (Class<*>?) -> Unit
) {
    var showUnavailableDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_home),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            StatusCard(activated = activated)
        }

        item {
            FeatureSection(
                title = stringResource(R.string.category_permission),
                items = listOf(
                    FeatureItem(R.string.feature_clipboard, Icons.Default.ContentPaste, ClipboardConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_sensor_info, Icons.Default.Sensors, null, false),
                    FeatureItem(R.string.feature_bluetooth_list, Icons.Default.Bluetooth, null, false),
                    FeatureItem(R.string.feature_wifi_list, Icons.Default.Wifi, WifiConfigActivity::class.java, true)
                ),
                onUnavailable = { showUnavailableDialog = true },
                onFeatureClick = onFeatureClick
            )
        }

        item {
            FeatureSection(
                title = stringResource(R.string.category_anonymization),
                items = listOf(
                    FeatureItem(R.string.feature_app_list, Icons.Default.Apps, PackageListConfigActivity::class.java, false),
                    FeatureItem(R.string.feature_sim_info, Icons.Default.SimCard, SimConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_location, Icons.Default.MyLocation, LocationConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_device_info, Icons.Default.Smartphone, DeviceInfoConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_unique_identifier, Icons.Default.Fingerprint, UniqueIdentifierConfigActivity::class.java, true)
                ),
                onUnavailable = { showUnavailableDialog = true },
                onFeatureClick = onFeatureClick
            )
        }

        item {
            FeatureSection(
                title = stringResource(R.string.category_audit),
                items = listOf(
                    FeatureItem(R.string.feature_permission_stats, Icons.Default.Security, SelfCheckActivity::class.java, true)
                ),
                onUnavailable = { showUnavailableDialog = true },
                onFeatureClick = onFeatureClick
            )
        }
    }

    if (showUnavailableDialog) {
        AlertDialog(
            onDismissRequest = { showUnavailableDialog = false },
            confirmButton = {
                TextButton(onClick = { showUnavailableDialog = false }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            text = {
                Text(text = stringResource(R.string.feature_not_available))
            }
        )
    }
}

@Composable
private fun StatusCard(activated: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (activated) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.status_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(if (activated) R.string.status_activated else R.string.status_deactivated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeatureSection(
    title: String,
    items: List<FeatureItem>,
    onUnavailable: () -> Unit,
    onFeatureClick: (Class<*>?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { item ->
                    FeatureCard(
                        item = item,
                        modifier = Modifier.weight(1f),
                        onUnavailable = onUnavailable,
                        onFeatureClick = onFeatureClick
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    item: FeatureItem,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
    onFeatureClick: (Class<*>?) -> Unit
) {
    val context = LocalContext.current
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
