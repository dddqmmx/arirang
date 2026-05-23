package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.ui.component.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme
import kotlin.math.roundToInt

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
                        Toast.makeText(this, getString(R.string.save_success_reboot_required), Toast.LENGTH_LONG).show()
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
        var savedConfig by remember { mutableStateOf(initialConfig) }
        var showUnsavedDialog by remember { mutableStateOf(false) }
        var revision by remember { mutableLongStateOf(0L) }
        val imeiRows = remember {
            mutableStateListOf<ImeiRowState>().apply {
                addAll(
                    initialConfig.imeiList()
                        .ifEmpty { listOf(0 to UniqueIdentifierPrefs.defaultImeiForSlot(0)) }
                        .map { (slot, imei) ->
                            ImeiRowState(
                                slot = slot,
                                imei = imei,
                                tac = initialConfig.tacForSlot(slot, imei)
                            )
                        }
                )
            }
        }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        val lazyListState = rememberLazyListState()
        var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
        var draggingOffset by remember { mutableFloatStateOf(0f) }

        fun currentConfig(): UniqueIdentifierPrefs.Config {
            return config.copy(
                imeiBySlot = imeiRows.mapIndexed { index, row -> index to row.imei }.toMap().toSortedMap(),
                tacBySlot = imeiRows.mapIndexed { index, row -> index to row.tac }.toMap().toSortedMap()
            )
        }

        fun updateImeis() {
            config = currentConfig()
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
                    title = { Text(stringResource(R.string.unique_identifier_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val imported = onImportImeis()
                            if (imported.isNotEmpty()) {
                                imeiRows.clear()
                                imeiRows.addAll(
                                    imported.toSortedMap().map { (slot, imei) ->
                                        ImeiRowState(slot = slot, imei = imei, tac = imei.take(8))
                                    }
                                )
                                updateImeis()
                                revision++
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.unique_import_imei))
                        }
                        SaveConfigIconButton(hasChanges = hasChanges, onClick = { saveCurrent() })
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        val nextSlot = ((imeiRows.maxOfOrNull { it.slot } ?: -1) + 1).coerceAtLeast(0)
                        val tac = UniqueIdentifierPrefs.randomTac()
                        imeiRows.add(
                            ImeiRowState(
                                slot = nextSlot,
                                imei = UniqueIdentifierPrefs.randomImeiForSlot(nextSlot, tac),
                                tac = tac
                            )
                        )
                        updateImeis()
                        revision++
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.unique_add_imei_slot)) }
                )
            }
        ) { padding ->
            LazyColumn(
                state = lazyListState,
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
                                onCheckedChange = null
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
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.unique_section_slot_info),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.unique_section_imei_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                itemsIndexed(imeiRows, key = { _, row -> row.slot }) { index, row ->
                    val isDragging = draggedItemIndex == index
                    ImeiRow(
                        index = index,
                        imei = row.imei,
                        tac = row.tac,
                        revision = revision,
                        canRemove = imeiRows.size > 1,
                        onImeiChange = { imei ->
                            if (index >= 0) {
                                imeiRows[index] = imeiRows[index].copy(imei = imei)
                                updateImeis()
                            }
                        },
                        onTacChange = { tac ->
                            if (index >= 0) {
                                imeiRows[index] = imeiRows[index].copy(tac = tac)
                                updateImeis()
                            }
                        },
                        onRemove = {
                            if (index >= 0 && imeiRows.size > 1) {
                                imeiRows.removeAt(index)
                                updateImeis()
                            }
                        },
                        modifier = Modifier
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .offset {
                                if (isDragging) {
                                    IntOffset(x = 0, y = draggingOffset.roundToInt())
                                } else {
                                    IntOffset.Zero
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = index
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingOffset += dragAmount.y

                                        val currentDraggedIndex = draggedItemIndex ?: return@detectDragGesturesAfterLongPress
                                        val layoutInfo = lazyListState.layoutInfo
                                        // Header items count:
                                        // Hook(1) + Device IDs(1) + Slot info intro(1) = 3 items
                                        // We have:
                                        // 0: Hook
                                        // 1: Device IDs
                                        // 2: Slot info intro
                                        val absoluteIndex = currentDraggedIndex + 3
                                        val draggedItemInfo = layoutInfo.visibleItemsInfo.find { it.index == absoluteIndex }

                                        if (draggedItemInfo != null) {
                                            if (draggingOffset > draggedItemInfo.size / 2 && currentDraggedIndex < imeiRows.size - 1) {
                                                imeiRows.add(currentDraggedIndex + 1, imeiRows.removeAt(currentDraggedIndex))
                                                draggedItemIndex = currentDraggedIndex + 1
                                                draggingOffset -= draggedItemInfo.size
                                                updateImeis()
                                                revision++
                                            } else if (draggingOffset < -draggedItemInfo.size / 2 && currentDraggedIndex > 0) {
                                                imeiRows.add(currentDraggedIndex - 1, imeiRows.removeAt(currentDraggedIndex))
                                                draggedItemIndex = currentDraggedIndex - 1
                                                draggingOffset += draggedItemInfo.size
                                                updateImeis()
                                                revision++
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggedItemIndex = null
                                        draggingOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggedItemIndex = null
                                        draggingOffset = 0f
                                    }
                                )
                            }
                    )
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
    private fun IdentifierTextField(
        label: String,
        value: String,
        revision: Long,
        modifier: Modifier = Modifier,
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
            modifier = modifier.fillMaxWidth()
        )
    }

    @Composable
    private fun ImeiRow(
        index: Int,
        imei: String,
        tac: String,
        revision: Long,
        canRemove: Boolean,
        onImeiChange: (String) -> Unit,
        onTacChange: (String) -> Unit,
        onRemove: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.unique_imei_slot_title, index + 1),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = onRemove, enabled = canRemove) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_sim_slot))
                    }
                }
                IdentifierTextField(
                    label = stringResource(R.string.sim_field_imei),
                    value = imei,
                    revision = revision,
                    keyboardType = KeyboardType.Number,
                    onRandom = { onImeiChange(UniqueIdentifierPrefs.randomImeiForSlot(index, tac)) },
                    onValueChange = { onImeiChange(it.filter(Char::isDigit)) }
                )
                IdentifierTextField(
                    label = stringResource(R.string.unique_field_type_allocation_code),
                    value = tac,
                    revision = revision,
                    keyboardType = KeyboardType.Number,
                    onRandom = {
                        val nextTac = UniqueIdentifierPrefs.randomTac()
                        onTacChange(nextTac)
                        onImeiChange(UniqueIdentifierPrefs.randomImeiForSlot(index, nextTac))
                    },
                    onValueChange = { onTacChange(it.filter(Char::isDigit).take(8)) }
                )
            }
        }
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

    private data class ImeiRowState(
        val slot: Int,
        val imei: String,
        val tac: String
    )
}
