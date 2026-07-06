package asia.nana7mi.arirang.ui.component.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.activity.BluetoothConfigActivity
import asia.nana7mi.arirang.ui.activity.ClipboardConfigActivity
import asia.nana7mi.arirang.ui.activity.DeviceInfoConfigActivity
import asia.nana7mi.arirang.ui.activity.LocationConfigActivity
import asia.nana7mi.arirang.ui.activity.PackageListConfigActivity
import asia.nana7mi.arirang.ui.activity.SensorConfigActivity
import asia.nana7mi.arirang.ui.activity.SimConfigActivity
import asia.nana7mi.arirang.ui.activity.UniqueIdentifierConfigActivity
import asia.nana7mi.arirang.ui.activity.WifiConfigActivity

@Composable
fun HomeScreen(
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
                    FeatureItem(R.string.feature_app_list, Icons.Default.Apps, PackageListConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_sensor_info, Icons.Default.Sensors, SensorConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_bluetooth_list, Icons.Default.Bluetooth, BluetoothConfigActivity::class.java, true),
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
                    FeatureItem(R.string.feature_sim_info, Icons.Default.SimCard, SimConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_location, Icons.Default.MyLocation, LocationConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_device_info, Icons.Default.Smartphone, DeviceInfoConfigActivity::class.java, true),
                    FeatureItem(R.string.feature_unique_identifier, Icons.Default.Fingerprint, UniqueIdentifierConfigActivity::class.java, true)
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
