package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.DeviceInfoPrefs
import asia.nana7mi.arirang.model.DevicePresetCatalog
import asia.nana7mi.arirang.ui.component.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class DeviceInfoConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = DeviceInfoPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                DeviceInfoConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        DeviceInfoPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success_reboot_required), Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DeviceInfoConfigScreen(
        initialConfig: DeviceInfoPrefs.Config,
        onBack: () -> Unit,
        onSave: (DeviceInfoPrefs.Config) -> Unit
    ) {
        var config by remember { mutableStateOf(initialConfig) }
        var savedConfig by remember { mutableStateOf(initialConfig) }
        var expanded by remember { mutableStateOf(false) }
        var buildTimeText by remember { mutableStateOf(initialConfig.time.toString()) }
        var showUnsavedDialog by remember { mutableStateOf(false) }
        var revision by remember { mutableLongStateOf(0L) }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        val selectedPreset = DevicePresetCatalog.ALL.firstOrNull { it.id == config.presetId }?.label
            ?: stringResource(R.string.device_preset_custom)

        fun updateConfig(updated: DeviceInfoPrefs.Config) {
            config = updated
            buildTimeText = updated.time.toString()
            revision++
        }

        fun currentConfig(): DeviceInfoPrefs.Config {
            return config.copy(time = buildTimeText.toLongOrNull() ?: config.time)
        }

        fun saveCurrent() {
            val current = currentConfig()
            config = current
            onSave(current)
            savedConfig = current
        }

        val hasChanges = currentConfig() != savedConfig

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
                    title = { Text(stringResource(R.string.device_info_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
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
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.device_hook_enabled),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.device_hook_enabled_summary),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = config.enabled,
                                    onCheckedChange = { config = config.copy(enabled = it, presetId = DevicePresetCatalog.CUSTOM_ID) }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedPreset,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.device_preset_title)) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    DevicePresetCatalog.ALL.forEach { preset ->
                                        DropdownMenuItem(
                                            text = { Text(preset.label) },
                                            onClick = {
                                                updateConfig(
                                                    config.copy(
                                                        enabled = config.enabled,
                                                        presetId = preset.id,
                                                        brand = preset.brand,
                                                        manufacturer = preset.manufacturer,
                                                        model = preset.model,
                                                        device = preset.device,
                                                        product = preset.product,
                                                        board = preset.board,
                                                        hardware = preset.hardware,
                                                        display = preset.display,
                                                        host = preset.host,
                                                        id = preset.buildId,
                                                        tags = preset.tags,
                                                        type = preset.type,
                                                        user = preset.user,
                                                        fingerprint = preset.fingerprint,
                                                        time = preset.time
                                                    )
                                                )
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.device_section_identity)) {
                        DeviceTextField(stringResource(R.string.device_field_brand), config.brand, revision) {
                            config = config.copy(brand = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_manufacturer), config.manufacturer, revision) {
                            config = config.copy(manufacturer = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_model), config.model, revision) {
                            config = config.copy(model = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.device_section_build_target)) {
                        DeviceTextField(stringResource(R.string.device_field_device), config.device, revision) {
                            config = config.copy(device = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_product), config.product, revision) {
                            config = config.copy(product = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_board), config.board, revision) {
                            config = config.copy(board = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_hardware), config.hardware, revision) {
                            config = config.copy(hardware = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.device_section_build_meta)) {
                        DeviceTextField(stringResource(R.string.device_field_display), config.display, revision) {
                            config = config.copy(display = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_id), config.id, revision) {
                            config = config.copy(id = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_fingerprint), config.fingerprint, revision, singleLine = false) {
                            config = config.copy(fingerprint = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_tags), config.tags, revision) {
                            config = config.copy(tags = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_type), config.type, revision) {
                            config = config.copy(type = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_user), config.user, revision) {
                            config = config.copy(user = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        DeviceTextField(stringResource(R.string.device_field_host), config.host, revision) {
                            config = config.copy(host = it, presetId = DevicePresetCatalog.CUSTOM_ID)
                        }
                        OutlinedTextField(
                            value = buildTimeText,
                            onValueChange = {
                                buildTimeText = it.filter(Char::isDigit)
                                config = config.copy(presetId = DevicePresetCatalog.CUSTOM_ID)
                            },
                            label = { Text(stringResource(R.string.device_field_time)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
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
    private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                content()
            }
        }
    }

    @Composable
    private fun DeviceTextField(
        label: String,
        value: String,
        revision: Long,
        singleLine: Boolean = true,
        onValueChange: (String) -> Unit
    ) {
        var localValue by remember(revision, label) { mutableStateOf(value) }
        OutlinedTextField(
            value = localValue,
            onValueChange = {
                localValue = it
                onValueChange(it)
            },
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            modifier = Modifier.fillMaxWidth()
        )
        if (!singleLine) Spacer(modifier = Modifier.height(2.dp))
    }
}
