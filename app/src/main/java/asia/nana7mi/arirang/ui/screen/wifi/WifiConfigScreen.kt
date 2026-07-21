package asia.nana7mi.arirang.ui.screen.wifi

import asia.nana7mi.arirang.ui.component.wifi.*
import asia.nana7mi.arirang.ui.component.common.ToggleSettingRow
import asia.nana7mi.arirang.ui.component.common.ExpandableSectionCard
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WifiConfigScreen(
    initialConfig: WifiConfigPrefs.Config,
    onBack: () -> Unit,
    onSave: (WifiConfigPrefs.Config) -> Unit
) {
    var config by remember { mutableStateOf(initialConfig) }
    var savedConfig by remember { mutableStateOf(initialConfig) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var currentExpanded by remember { mutableStateOf(true) }
    var nearbyExpanded by remember { mutableStateOf(true) }
    val scanNetworkExpanded = remember { mutableStateMapOf<Int, Boolean>() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hasChanges = config != savedConfig

    fun saveCurrent() {
        onSave(config)
        savedConfig = config
    }

    fun requestBack() {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    BackHandler { requestBack() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.wifi_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val lan = randomLanAddresses()
                        config = config.copy(
                            currentBssid = randomBssid(),
                            ipAddress = lan.ipAddress,
                            gateway = lan.gateway,
                            dns1 = lan.dns1,
                            dns2 = lan.dns2,
                            scanResults = config.scanResults.map { network ->
                                network.copy(bssid = randomBssid())
                            }.ifEmpty {
                                listOf(
                                    WifiConfigPrefs.ScanNetwork(
                                        ssid = WifiConfigPrefs.DEFAULT_SCAN_SSID,
                                        bssid = randomBssid()
                                    )
                                )
                            }
                        )
                    }) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = stringResource(R.string.unique_randomize_all)
                        )
                    }
                    SaveConfigIconButton(hasChanges = hasChanges, onClick = { saveCurrent() })
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .toggleable(
                                value = config.enabled,
                                onValueChange = { config = config.copy(enabled = it) },
                                role = Role.Switch
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.wifi_hook_enabled),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.wifi_hook_enabled_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = config.enabled, onCheckedChange = null)
                    }
                }
            }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.wifi_section_current),
                    expanded = currentExpanded,
                    onExpandedChange = { currentExpanded = !currentExpanded },
                    icon = Icons.Default.Wifi
                ) {
                    WifiTextField(
                        label = stringResource(R.string.wifi_field_current_ssid),
                        value = config.currentSsid,
                        onValueChange = { config = config.copy(currentSsid = it) }
                    )
                    WifiTextField(
                        label = stringResource(R.string.wifi_field_current_bssid),
                        value = config.currentBssid,
                        onValueChange = { config = config.copy(currentBssid = it) },
                        onRandom = { config = config.copy(currentBssid = randomBssid()) }
                    )
                    WifiTextField(
                        label = stringResource(R.string.wifi_field_ip_address),
                        value = config.ipAddress,
                        onValueChange = { config = config.copy(ipAddress = it) },
                        onRandom = {
                            val lan = randomLanAddresses()
                            config = config.copy(
                                ipAddress = lan.ipAddress,
                                gateway = lan.gateway,
                                dns1 = lan.dns1,
                                dns2 = lan.dns2
                            )
                        }
                    )
                    WifiTextField(
                        label = stringResource(R.string.wifi_field_gateway),
                        value = config.gateway,
                        onValueChange = { config = config.copy(gateway = it) },
                        onRandom = {
                            val lan = randomLanAddresses()
                            config = config.copy(
                                ipAddress = lan.ipAddress,
                                gateway = lan.gateway,
                                dns1 = lan.dns1,
                                dns2 = lan.dns2
                            )
                        }
                    )
                    WifiTextField(
                        label = stringResource(R.string.wifi_field_dns1),
                        value = config.dns1,
                        onValueChange = { config = config.copy(dns1 = it) },
                        onRandom = {
                            val dns = randomDnsPair()
                            config = config.copy(dns1 = dns.first, dns2 = dns.second)
                        }
                    )
                    WifiTextField(
                        label = stringResource(R.string.wifi_field_dns2),
                        value = config.dns2,
                        onValueChange = { config = config.copy(dns2 = it) },
                        onRandom = {
                            val dns = randomDnsPair()
                            config = config.copy(dns1 = dns.first, dns2 = dns.second)
                        }
                    )
                }
            }

            item {
                ExpandableSectionCard(
                    title = stringResource(R.string.wifi_section_scan_result),
                    expanded = nearbyExpanded,
                    onExpandedChange = { nearbyExpanded = !nearbyExpanded },
                    icon = Icons.Default.WifiFind,
                    trailingAction = {
                        IconButton(onClick = {
                            val next = WifiConfigPrefs.defaultScanNetwork(config.scanResults.size)
                            val nextIndex = config.scanResults.size
                            config = config.copy(scanResults = config.scanResults + next)
                            nearbyExpanded = true
                            scanNetworkExpanded[nextIndex] = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wifi_add_scan_result))
                        }
                    }
                ) {
                    ToggleSettingRow(
                        title = stringResource(R.string.wifi_hide_all_scan_results),
                        summary = stringResource(R.string.wifi_hide_all_scan_results_summary),
                        checked = config.hideScanResults,
                        onCheckedChange = { config = config.copy(hideScanResults = it) }
                    )

                    if (!config.hideScanResults) {
                        config.scanResults.forEachIndexed { index, network ->
                            if (index > 0) HorizontalDivider()
                            ScanNetworkEditor(
                                index = index,
                                network = network,
                                expanded = scanNetworkExpanded[index] ?: true,
                                canRemove = true,
                                onExpandedChange = {
                                    scanNetworkExpanded[index] = !(scanNetworkExpanded[index] ?: true)
                                },
                                onNetworkChange = { changed ->
                                    config = config.copy(
                                        scanResults = config.scanResults.toMutableList().also {
                                            it[index] = changed
                                        }
                                    )
                                },
                                onRemove = {
                                    config = config.copy(
                                        scanResults = config.scanResults.filterIndexed { itemIndex, _ ->
                                            itemIndex != index
                                        }
                                    )
                                    scanNetworkExpanded.remove(index)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnsavedDialog) {
        UnsavedChangesDialog(
            onDismiss = { showUnsavedDialog = false },
            onDiscard = {
                showUnsavedDialog = false
                onBack()
            },
            onSave = {
                showUnsavedDialog = false
                saveCurrent()
                onBack()
            }
        )
    }
}
