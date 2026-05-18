package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.core.app.ActivityCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class UniqueIdentifierConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = UniqueIdentifierPrefs.loadConfig(this)

        setContent {
            ArirangTheme {
                UniqueIdentifierConfigScreen(
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onImportImeis = { importCurrentImeis() },
                    onSave = { config ->
                        UniqueIdentifierPrefs.saveConfig(this, config)
                        Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun UniqueIdentifierConfigScreen(
        initialConfig: UniqueIdentifierPrefs.Config,
        onBack: () -> Unit,
        onImportImeis: () -> Map<Int, String>,
        onSave: (UniqueIdentifierPrefs.Config) -> Unit
    ) {
        var config by remember { mutableStateOf(initialConfig) }
        var revision by remember { mutableLongStateOf(0L) }
        val imeiRows = remember {
            mutableStateListOf<Pair<Int, String>>().apply {
                addAll(initialConfig.imeiList().ifEmpty { listOf(0 to UniqueIdentifierPrefs.defaultImeiForSlot(0)) })
            }
        }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        fun updateImeis() {
            config = config.copy(imeiBySlot = imeiRows.toMap().toSortedMap())
        }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.unique_identifier_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val imported = onImportImeis()
                            if (imported.isNotEmpty()) {
                                imeiRows.clear()
                                imeiRows.addAll(imported.toSortedMap().toList())
                                updateImeis()
                                revision++
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_import_imei))
                        }
                        IconButton(onClick = {
                            updateImeis()
                            onSave(config.copy(imeiBySlot = imeiRows.toMap().toSortedMap()))
                        }) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        val nextSlot = ((imeiRows.maxOfOrNull { it.first } ?: -1) + 1).coerceAtLeast(0)
                        imeiRows.add(nextSlot to UniqueIdentifierPrefs.defaultImeiForSlot(nextSlot))
                        updateImeis()
                        revision++
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.unique_add_imei_slot)) }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp)
            ) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.unique_hook_enabled),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.unique_hook_enabled_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { config = config.copy(enabled = it) }
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.unique_section_device_ids)) {
                        IdentifierTextField(
                            label = stringResource(R.string.unique_field_android_id),
                            value = config.androidId,
                            revision = revision,
                            onRandom = {
                                config = config.copy(androidId = UniqueIdentifierPrefs.randomAndroidId())
                                revision++
                            },
                            onValueChange = { config = config.copy(androidId = it) }
                        )
                        IdentifierTextField(
                            label = stringResource(R.string.unique_field_gaid),
                            value = config.gaid,
                            revision = revision,
                            onRandom = {
                                config = config.copy(gaid = UniqueIdentifierPrefs.randomGaid())
                                revision++
                            },
                            onValueChange = { config = config.copy(gaid = it) }
                        )
                        IdentifierTextField(
                            label = stringResource(R.string.unique_field_gsf_id),
                            value = config.gsfId,
                            revision = revision,
                            onRandom = {
                                config = config.copy(gsfId = UniqueIdentifierPrefs.randomGsfId())
                                revision++
                            },
                            onValueChange = { config = config.copy(gsfId = it) }
                        )
                        IdentifierTextField(
                            label = stringResource(R.string.unique_field_widevine_drm_id),
                            value = config.widevineDrmId,
                            revision = revision,
                            onRandom = {
                                config = config.copy(widevineDrmId = UniqueIdentifierPrefs.randomWidevineDrmId())
                                revision++
                            },
                            onValueChange = { config = config.copy(widevineDrmId = it.filter(Char::isLetterOrDigit)) }
                        )
                        IdentifierTextField(
                            label = stringResource(R.string.unique_field_app_set_id),
                            value = config.appSetId,
                            revision = revision,
                            onRandom = {
                                config = config.copy(appSetId = UniqueIdentifierPrefs.randomAppSetId())
                                revision++
                            },
                            onValueChange = { config = config.copy(appSetId = it) }
                        )
                        IdentifierTextField(
                            label = stringResource(R.string.unique_field_serial),
                            value = config.serial,
                            revision = revision,
                            onRandom = {
                                config = config.copy(serial = UniqueIdentifierPrefs.randomSerial())
                                revision++
                            },
                            onValueChange = { config = config.copy(serial = it) }
                        )
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.unique_section_imei)) {
                        Text(
                            text = stringResource(R.string.unique_section_imei_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                    }
                }

                items(imeiRows, key = { it.first }) { row ->
                    val index = imeiRows.indexOfFirst { it.first == row.first }
                    ImeiRow(
                        slot = row.first,
                        imei = row.second,
                        revision = revision,
                        canRemove = imeiRows.size > 1,
                        onSlotChange = { slot ->
                            if (index >= 0) {
                                imeiRows[index] = slot to imeiRows[index].second
                                updateImeis()
                            }
                        },
                        onImeiChange = { imei ->
                            if (index >= 0) {
                                imeiRows[index] = imeiRows[index].first to imei
                                updateImeis()
                            }
                        },
                        onRemove = {
                            if (index >= 0 && imeiRows.size > 1) {
                                imeiRows.removeAt(index)
                                updateImeis()
                            }
                        }
                    )
                }
            }
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
    private fun IdentifierTextField(
        label: String,
        value: String,
        revision: Long,
        singleLine: Boolean = true,
        keyboardType: KeyboardType = KeyboardType.Text,
        onRandom: (() -> Unit)? = null,
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
            trailingIcon = {
                if (onRandom != null) {
                    IconButton(onClick = onRandom) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_randomize))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Composable
    private fun ImeiRow(
        slot: Int,
        imei: String,
        revision: Long,
        canRemove: Boolean,
        onSlotChange: (Int) -> Unit,
        onImeiChange: (String) -> Unit,
        onRemove: () -> Unit
    ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.unique_imei_slot_title, slot),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onRemove, enabled = canRemove) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_sim_slot))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdentifierTextField(
                        label = stringResource(R.string.sim_field_slot_index),
                        value = slot.toString(),
                        revision = revision,
                        modifier = Modifier.weight(0.35f),
                        keyboardType = KeyboardType.Number,
                        onValueChange = { onSlotChange(it.toIntOrNull() ?: slot) }
                    )
                    IdentifierTextField(
                        label = stringResource(R.string.sim_field_imei),
                        value = imei,
                        revision = revision,
                        modifier = Modifier.weight(0.65f),
                        keyboardType = KeyboardType.Number,
                        onRandom = { onImeiChange(UniqueIdentifierPrefs.randomImeiForSlot(slot)) },
                        onValueChange = { onImeiChange(it.filter(Char::isDigit)) }
                    )
                }
            }
        }
    }

    @Composable
    private fun IdentifierTextField(
        label: String,
        value: String,
        revision: Long,
        modifier: Modifier,
        keyboardType: KeyboardType,
        onRandom: (() -> Unit)? = null,
        onValueChange: (String) -> Unit
    ) {
        var localValue by remember(revision, label, value) { mutableStateOf(value) }
        OutlinedTextField(
            value = localValue,
            onValueChange = {
                localValue = it
                onValueChange(it)
            },
            label = { Text(label) },
            trailingIcon = {
                if (onRandom != null) {
                    IconButton(onClick = onRandom) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_randomize))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            modifier = modifier
        )
    }

    private fun importCurrentImeis(): Map<Int, String> {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
            return emptyMap()
        }

        val telephonyManager = getSystemService(TelephonyManager::class.java)
        val count = runCatching { telephonyManager.phoneCount }.getOrDefault(1).coerceAtLeast(1)
        val result = (0 until count).mapNotNull { slot ->
            runCatching { telephonyManager.getImei(slot) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { slot to it }
        }.toMap()

        if (result.isEmpty()) {
            Toast.makeText(this, getString(R.string.unique_import_imei_empty), Toast.LENGTH_SHORT).show()
        }
        return result
    }
}
