package asia.nana7mi.arirang.ui.screen.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs.SpoofedTransport
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.ConfigScreenScaffold
import asia.nana7mi.arirang.ui.component.common.ConfigSectionCard
import asia.nana7mi.arirang.ui.component.common.LabelledDropdown
import asia.nana7mi.arirang.ui.component.common.PickerItem
import asia.nana7mi.arirang.ui.component.common.SearchablePickerDialog
import asia.nana7mi.arirang.ui.component.common.ToggleSettingRow
import asia.nana7mi.arirang.ui.component.common.loadInstalledApps
import asia.nana7mi.arirang.ui.component.vpn.ExemptAppRow
import asia.nana7mi.arirang.ui.component.vpn.labelRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VpnStatusConfigScreen(
    initialConfig: VpnStatusPrefs.Config,
    onBack: () -> Unit,
    onSave: (VpnStatusPrefs.Config) -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(initialConfig) }
    var savedConfig by remember { mutableStateOf(initialConfig) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var showAppPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { apps = loadInstalledApps(context) }

    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
    val transports = SpoofedTransport.entries
    val transportLabels = transports.map { stringResource(it.labelRes()) }

    fun saveCurrent(): Boolean {
        onSave(config)
        savedConfig = config
        return true
    }

    ConfigScreenScaffold(
        title = stringResource(R.string.feature_vpn_status),
        hasChanges = config != savedConfig,
        onSave = { saveCurrent() },
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item(key = "enable") {
                ConfigSectionCard(title = stringResource(R.string.vpn_section_enable)) {
                    ToggleSettingRow(
                        title = stringResource(R.string.vpn_enable),
                        summary = stringResource(R.string.vpn_enable_summary),
                        checked = config.enabled,
                        onCheckedChange = { config = config.copy(enabled = it) }
                    )
                }
            }

            item(key = "visibility") {
                ConfigSectionCard(title = stringResource(R.string.vpn_section_visibility)) {
                    ToggleSettingRow(
                        title = stringResource(R.string.vpn_hide_transport),
                        summary = stringResource(R.string.vpn_hide_transport_summary),
                        checked = config.hideVpnTransport,
                        onCheckedChange = { config = config.copy(hideVpnTransport = it) }
                    )
                    // Only meaningful once the VPN transport is being removed —
                    // there is nothing to substitute for otherwise.
                    AnimatedVisibility(visible = config.hideVpnTransport) {
                        LabelledDropdown(
                            label = stringResource(R.string.vpn_spoofed_transport),
                            selectedLabel = transportLabels[transports.indexOf(config.spoofedTransport)],
                            options = transportLabels,
                            onSelect = { index ->
                                transports.getOrNull(index)?.let {
                                    config = config.copy(spoofedTransport = it)
                                }
                            }
                        )
                    }
                }
            }

            item(key = "network") {
                ConfigSectionCard(title = stringResource(R.string.vpn_section_network)) {
                    ToggleSettingRow(
                        title = stringResource(R.string.vpn_hide_interfaces),
                        summary = stringResource(R.string.vpn_hide_interfaces_summary),
                        checked = config.hideVpnInterfaces,
                        onCheckedChange = { config = config.copy(hideVpnInterfaces = it) }
                    )
                    ToggleSettingRow(
                        title = stringResource(R.string.vpn_hide_always_on),
                        summary = stringResource(R.string.vpn_hide_always_on_summary),
                        checked = config.hideAlwaysOnVpn,
                        onCheckedChange = { config = config.copy(hideAlwaysOnVpn = it) }
                    )
                    ToggleSettingRow(
                        title = stringResource(R.string.vpn_hide_proxy),
                        summary = stringResource(R.string.vpn_hide_proxy_summary),
                        checked = config.hideProxy,
                        onCheckedChange = { config = config.copy(hideProxy = it) }
                    )
                }
            }

            item(key = "exempt") {
                ConfigSectionCard(title = stringResource(R.string.vpn_section_exempt)) {
                    Text(
                        text = stringResource(R.string.vpn_exempt_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (config.exemptPackages.isEmpty()) {
                        Text(
                            text = stringResource(R.string.vpn_exempt_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        HorizontalDivider()
                        config.exemptPackages.sorted().forEach { packageName ->
                            ExemptAppRow(
                                packageName = packageName,
                                app = appsByPackage[packageName],
                                onRemove = {
                                    config = config.copy(
                                        exemptPackages = config.exemptPackages - packageName
                                    )
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                    FilledTonalButton(
                        onClick = { showAppPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(
                            text = stringResource(R.string.vpn_add_app),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        val candidates = remember(apps, config.exemptPackages) {
            apps.asSequence()
                .filterNot { it.packageName in config.exemptPackages }
                .sortedBy { it.label.lowercase() }
                .map { PickerItem(id = it.packageName, title = it.label, subtitle = it.packageName) }
                .toList()
        }
        SearchablePickerDialog(
            title = stringResource(R.string.vpn_add_app),
            items = candidates,
            selectedId = null,
            searchPlaceholder = stringResource(R.string.hint_package_or_app_name),
            onDismiss = { showAppPicker = false },
            onSelect = { packageName ->
                showAppPicker = false
                if (config.exemptPackages.size < VpnStatusPrefs.MAX_EXEMPT_PACKAGES) {
                    config = config.copy(exemptPackages = config.exemptPackages + packageName)
                }
            }
        )
    }
}
