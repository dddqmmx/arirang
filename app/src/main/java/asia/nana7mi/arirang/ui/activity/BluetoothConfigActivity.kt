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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.ui.component.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class BluetoothConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = BluetoothConfigPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                BluetoothConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        BluetoothConfigPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BluetoothConfigScreen(
        initialConfig: BluetoothConfigPrefs.Config,
        onBack: () -> Unit,
        onSave: (BluetoothConfigPrefs.Config) -> Unit
    ) {
        var config by remember { mutableStateOf(initialConfig) }
        var savedConfig by remember { mutableStateOf(initialConfig) }
        var showUnsavedDialog by remember { mutableStateOf(false) }
        var connectedExpanded by remember { mutableStateOf(true) }
        var nearbyExpanded by remember { mutableStateOf(true) }
        val connectedDeviceExpanded = remember { mutableStateMapOf<Int, Boolean>() }
        val scanResultExpanded = remember { mutableStateMapOf<Int, Boolean>() }
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
                    title = { Text(stringResource(R.string.bluetooth_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            config = BluetoothConfigPrefs.Config()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.bluetooth_apply_defaults))
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
                                    text = stringResource(R.string.bluetooth_hook_enabled),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.bluetooth_hook_enabled_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = config.enabled, onCheckedChange = null)
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.bluetooth_field_local_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.bluetooth_field_local_name_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            BluetoothTextField(
                                label = stringResource(R.string.bluetooth_field_local_name),
                                value = config.deviceName,
                                onValueChange = { config = config.copy(deviceName = it) }
                            )
                        }
                    }
                }

                item {
                    ExpandableSectionCard(
                        title = stringResource(R.string.bluetooth_section_connected),
                        expanded = connectedExpanded,
                        onExpandedChange = { connectedExpanded = !connectedExpanded },
                        icon = Icons.Default.Bluetooth,
                        trailingAction = {
                            IconButton(onClick = {
                                val next = BluetoothConfigPrefs.defaultDevice(config.connectedDevices.size, false)
                                val nextIndex = config.connectedDevices.size
                                config = config.copy(connectedDevices = config.connectedDevices + next)
                                connectedExpanded = true
                                connectedDeviceExpanded[nextIndex] = true
                            }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bluetooth_add_connected_device))
                            }
                        }
                    ) {
                        SwitchRow(
                            title = stringResource(R.string.bluetooth_hide_all_connected),
                            summary = stringResource(R.string.bluetooth_hide_all_connected_summary),
                            checked = config.hideConnectedDevices,
                            onCheckedChange = { config = config.copy(hideConnectedDevices = it) }
                        )

                        if (!config.hideConnectedDevices) {
                            config.connectedDevices.forEachIndexed { index, device ->
                                if (index > 0) HorizontalDivider()
                                DeviceEditor(
                                    title = stringResource(R.string.bluetooth_connected_device_title, index + 1),
                                    removeContentDescription = stringResource(R.string.bluetooth_remove_connected_device),
                                    device = device,
                                    expanded = connectedDeviceExpanded[index] ?: (config.connectedDevices.size == 1),
                                    canRemove = true,
                                    onExpandedChange = {
                                        connectedDeviceExpanded[index] = !(connectedDeviceExpanded[index] ?: (config.connectedDevices.size == 1))
                                    },
                                    onDeviceChange = { changed ->
                                        config = config.copy(
                                            connectedDevices = config.connectedDevices.toMutableList().also {
                                                it[index] = changed
                                            }
                                        )
                                    },
                                    onRemove = {
                                        config = config.copy(
                                            connectedDevices = config.connectedDevices.filterIndexed { itemIndex, _ ->
                                                itemIndex != index
                                            }
                                        )
                                        connectedDeviceExpanded.remove(index)
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    ExpandableSectionCard(
                        title = stringResource(R.string.bluetooth_section_scan_result),
                        expanded = nearbyExpanded,
                        onExpandedChange = { nearbyExpanded = !nearbyExpanded },
                        icon = Icons.Default.BluetoothSearching,
                        trailingAction = {
                            IconButton(onClick = {
                                val next = BluetoothConfigPrefs.defaultDevice(config.scanResults.size, true)
                                val nextIndex = config.scanResults.size
                                config = config.copy(scanResults = config.scanResults + next)
                                nearbyExpanded = true
                                scanResultExpanded[nextIndex] = true
                            }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bluetooth_add_scan_result))
                            }
                        }
                    ) {
                        SwitchRow(
                            title = stringResource(R.string.bluetooth_hide_all_scan_results),
                            summary = stringResource(R.string.bluetooth_hide_all_scan_results_summary),
                            checked = config.hideScanResults,
                            onCheckedChange = { config = config.copy(hideScanResults = it) }
                        )

                        if (!config.hideScanResults) {
                            config.scanResults.forEachIndexed { index, device ->
                                if (index > 0) HorizontalDivider()
                                DeviceEditor(
                                    title = stringResource(R.string.bluetooth_scan_result_title, index + 1),
                                    removeContentDescription = stringResource(R.string.bluetooth_remove_scan_result),
                                    device = device,
                                    expanded = scanResultExpanded[index] ?: (config.scanResults.size == 1),
                                    canRemove = true,
                                    onExpandedChange = {
                                        scanResultExpanded[index] = !(scanResultExpanded[index] ?: (config.scanResults.size == 1))
                                    },
                                    onDeviceChange = { changed ->
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
                                        scanResultExpanded.remove(index)
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
    private fun DeviceEditor(
        title: String,
        removeContentDescription: String,
        device: BluetoothConfigPrefs.Device,
        expanded: Boolean,
        canRemove: Boolean,
        onExpandedChange: () -> Unit,
        onDeviceChange: (BluetoothConfigPrefs.Device) -> Unit,
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
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = listOf(device.name, device.address)
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
                        contentDescription = removeContentDescription,
                        tint = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BluetoothTextField(
                        label = stringResource(R.string.bluetooth_field_name),
                        value = device.name,
                        onValueChange = { onDeviceChange(device.copy(name = it)) }
                    )
                    BluetoothTextField(
                        label = stringResource(R.string.bluetooth_field_address),
                        value = device.address,
                        onValueChange = { onDeviceChange(device.copy(address = it)) }
                    )
                }
            }
        }
    }

    @Composable
    private fun BluetoothTextField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
