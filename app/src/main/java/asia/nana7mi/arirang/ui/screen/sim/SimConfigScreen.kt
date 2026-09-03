package asia.nana7mi.arirang.ui.screen.sim

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.SimInfo
import asia.nana7mi.arirang.model.SimPresetCatalog
import asia.nana7mi.arirang.ui.component.common.ConfigScreenScaffold
import asia.nana7mi.arirang.ui.component.common.RandomizeIconButton
import asia.nana7mi.arirang.ui.component.dialog.InfoDialog
import asia.nana7mi.arirang.ui.component.sim.ConfigHeader
import asia.nana7mi.arirang.ui.component.sim.SimSlotItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimConfigScreen(
    onBack: () -> Unit,
    onSave: (Boolean, Boolean, List<SimInfo>) -> Unit,
    initialEnabled: Boolean,
    initialHideSim: Boolean,
    initialSimList: List<SimInfo>,
    slotLimit: Int,
    onCreateDefaultSim: (Int) -> SimInfo = { index -> SimPresetCatalog.randomSimInfo(index) },
    onRandomSim: (Int) -> SimInfo = { index -> SimPresetCatalog.randomSimInfo(index) }
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var hideSim by remember { mutableStateOf(initialHideSim) }
    val maxSlots = slotLimit.coerceAtLeast(1)
    val simList = remember { mutableStateListOf<SimInfo>().apply { addAll(initialSimList.take(maxSlots)) } }
    var savedEnabled by remember { mutableStateOf(initialEnabled) }
    var savedHideSim by remember { mutableStateOf(initialHideSim) }
    var savedSimList by remember { mutableStateOf(initialSimList.take(maxSlots)) }
    var showSlotLimitDialog by remember { mutableStateOf(false) }
    val hasChanges = enabled != savedEnabled || hideSim != savedHideSim || simList.toList() != savedSimList

    fun saveCurrent(): Boolean {
        val currentList = simList.toList()
        onSave(enabled, hideSim, currentList)
        savedEnabled = enabled
        savedHideSim = hideSim
        savedSimList = currentList
        return true
    }

    ConfigScreenScaffold(
        title = stringResource(R.string.sim_config_title),
        hasChanges = hasChanges,
        onSave = { saveCurrent() },
        onBack = onBack,
        actions = {
            if (!hideSim) {
                RandomizeIconButton(
                    contentDescription = stringResource(R.string.unique_randomize_all),
                    onClick = {
                        if (simList.isEmpty()) {
                            simList.add(onRandomSim(0))
                        } else {
                            val randomized = simList.mapIndexed { index, _ ->
                                onRandomSim(index)
                            }
                            simList.clear()
                            simList.addAll(randomized)
                        }
                    }
                )
            }
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

    if (showSlotLimitDialog) {
        InfoDialog(
            title = stringResource(R.string.sim_slot_limit_title),
            message = stringResource(R.string.sim_slot_limit_reached, maxSlots),
            onDismiss = { showSlotLimitDialog = false }
        )
    }
}
