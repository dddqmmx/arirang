package asia.nana7mi.arirang.ui.screen.sim

import asia.nana7mi.arirang.ui.component.sim.*
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.SimInfo
import asia.nana7mi.arirang.ui.component.dialog.InfoDialog
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.sim.SimSlotItem
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimConfigScreen(
    onBack: () -> Unit,
    onSave: (Boolean, Boolean, List<SimInfo>) -> Unit,
    initialEnabled: Boolean,
    initialHideSim: Boolean,
    initialSimList: List<SimInfo>,
    slotLimit: Int,
    onImportSystemSims: () -> List<SimInfo>,
    onRequestPhoneStatePermission: () -> Unit,
    onCreateDefaultSim: (Int) -> SimInfo
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var hideSim by remember { mutableStateOf(initialHideSim) }
    val maxSlots = slotLimit.coerceAtLeast(1)
    val simList = remember { mutableStateListOf<SimInfo>().apply { addAll(initialSimList.take(maxSlots)) } }
    var savedEnabled by remember { mutableStateOf(initialEnabled) }
    var savedHideSim by remember { mutableStateOf(initialHideSim) }
    var savedSimList by remember { mutableStateOf(initialSimList.take(maxSlots)) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSlotLimitDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    val noActiveSimMessage = stringResource(R.string.sim_import_no_active)
    val hasChanges = enabled != savedEnabled || hideSim != savedHideSim || simList.toList() != savedSimList

    fun saveCurrent() {
        val currentList = simList.toList()
        onSave(enabled, hideSim, currentList)
        savedEnabled = enabled
        savedHideSim = hideSim
        savedSimList = currentList
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
                title = { Text(stringResource(R.string.sim_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (!hideSim) {
                        IconButton(onClick = {
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                                val systemSims = onImportSystemSims()
                                if (systemSims.isNotEmpty()) {
                                    simList.clear()
                                    simList.addAll(systemSims.take(maxSlots))
                                } else {
                                    Toast.makeText(context, noActiveSimMessage, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                onRequestPhoneStatePermission()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sim_import_desc))
                        }
                    }
                    SaveConfigIconButton(hasChanges = hasChanges, onClick = { saveCurrent() })
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            if (!hideSim) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (simList.size >= maxSlots) {
                            showSlotLimitDialog = true
                            return@ExtendedFloatingActionButton
                        }
                        val newSlotIndex = simList.size
                        simList.add(onCreateDefaultSim(newSlotIndex))
                    },
                    expanded = simList.size < maxSlots,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_new_slot)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { padding ->
        if (hideSim) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                ConfigHeader(
                    enabled = enabled,
                    onEnabledChange = { enabled = it },
                    hideSim = hideSim,
                    onHideSimChange = { hideSim = it }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                item {
                    ConfigHeader(
                        enabled = enabled,
                        onEnabledChange = { enabled = it },
                        hideSim = hideSim,
                        onHideSimChange = { hideSim = it }
                    )
                }

                itemsIndexed(simList) { index, simInfo ->
                    SimSlotItem(
                        index = index,
                        simInfo = simInfo,
                        onSimInfoChange = { updated -> simList[index] = updated },
                        onRemove = { simList.removeAt(index) }
                    )
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
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

    if (showSlotLimitDialog) {
        InfoDialog(
            title = stringResource(R.string.sim_slot_limit_title),
            message = stringResource(R.string.sim_slot_limit_reached, maxSlots),
            onDismiss = { showSlotLimitDialog = false }
        )
    }
}
