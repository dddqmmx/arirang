package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.OutlinedTextField
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
import asia.nana7mi.arirang.ui.component.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme
import java.security.SecureRandom

class WifiConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = WifiConfigPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                WifiConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        WifiConfigPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WifiConfigScreen(
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
                            config = WifiConfigPrefs.Config()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.wifi_apply_defaults))
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
                        SwitchRow(
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

    @Composable
    private fun ExpandableSectionCard(
        title: String,
        expanded: Boolean,
        onExpandedChange: () -> Unit,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        trailingAction: (@Composable () -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onExpandedChange() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    trailingAction?.invoke()
                    IconButton(onClick = onExpandedChange) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                }
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        summary: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }

    @Composable
    private fun ScanNetworkEditor(
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
    private fun WifiTextField(
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

    companion object {
        private val bssidRandom = SecureRandom()

        private fun randomBssid(): String = buildString {
            repeat(6) { i ->
                if (i > 0) append(':')
                append("%02X".format(bssidRandom.nextInt(256)))
            }
        }
    }
}
