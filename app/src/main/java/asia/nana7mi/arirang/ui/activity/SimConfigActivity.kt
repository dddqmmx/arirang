package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.model.SimInfo
import asia.nana7mi.arirang.ui.component.dialog.InfoDialog
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.sim.SimSlotItem
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme
import java.security.SecureRandom

class SimConfigActivity : ComponentActivity() {
    private val iccidRandom = SecureRandom()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = SimConfigPrefs.loadConfig(this)
        val slotLimit = UniqueIdentifierPrefs.configuredSlotCount(this)

        setContent {
            ArirangTheme {
                SimConfigScreen(
                    onBack = { finish() },
                    onSave = { enabled, hideSim, list ->
                        saveSimInfoConfig(enabled, hideSim, list)
                    },
                    initialEnabled = initialConfig.enabled,
                    initialHideSim = initialConfig.hideSim,
                    initialSimList = initialConfig.simInfoList,
                    slotLimit = slotLimit
                )
            }
        }
    }

    private fun saveSimInfoConfig(enabled: Boolean, hideSim: Boolean, simInfoList: List<SimInfo>) {
        SimConfigPrefs.saveConfig(
            this,
            SimConfigPrefs.Config.fromList(
                enabled = enabled,
                hideSim = hideSim,
                simInfoList = simInfoList
            )
        )
        Toast.makeText(this, getString(R.string.save_success_reboot_required), Toast.LENGTH_LONG).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SimConfigScreen(
        onBack: () -> Unit,
        onSave: (Boolean, Boolean, List<SimInfo>) -> Unit,
        initialEnabled: Boolean,
        initialHideSim: Boolean,
        initialSimList: List<SimInfo>,
        slotLimit: Int
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
                                    val systemSims = getSystemSimInfoList()
                                    if (systemSims.isNotEmpty()) {
                                        simList.clear()
                                        simList.addAll(systemSims.take(maxSlots))
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.sim_import_no_active), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    ActivityCompat.requestPermissions(this@SimConfigActivity, arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
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
                            simList.add(createDefaultSimInfo(newSlotIndex))
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

    @Composable
    fun ConfigHeader(
        enabled: Boolean,
        onEnabledChange: (Boolean) -> Unit,
        hideSim: Boolean,
        onHideSimChange: (Boolean) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column {
                    SettingRow(
                        title = stringResource(R.string.sim_hook_enabled),
                        summary = stringResource(R.string.sim_hook_enabled_summary),
                        checked = enabled,
                        onCheckedChange = onEnabledChange
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    SettingRow(
                        title = stringResource(R.string.hide_sim),
                        summary = stringResource(
                            if (hideSim) R.string.hide_sim_enabled_summary else R.string.hide_sim_disabled_summary
                        ),
                        checked = hideSim,
                        onCheckedChange = onHideSimChange
                    )
                }
            }
        }
    }

    @Composable
    fun SettingRow(
        title: String,
        summary: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null // Handled by Row toggleable
            )
        }
    }

    private fun createDefaultSimInfo(index: Int): SimInfo {
        return SimInfo(
            id = index + 1,
            iccId = randomIccidForDefault(),
            simSlotIndex = index,
            displayName = getString(R.string.sim_carrier_default),
            carrierName = getString(R.string.sim_carrier_default),
            nameSource = null,
            iconTint = null,
            number = "+12025550147",
            roaming = 0,
            icon = null,
            mcc = getString(R.string.sim_mcc_default),
            mnc = getString(R.string.sim_mnc_default),
            countryIso = getString(R.string.sim_country_default),
            isEmbedded = false,
            nativeAccessRules = null,
            cardString = "",
            cardId = index,
            isOpportunistic = false,
            groupUuid = null,
            isGroupDisabled = false,
            carrierId = -1,
            profileClass = null,
            subType = null,
            groupOwner = "",
            carrierConfigAccessRules = null,
            areUiccApplicationsEnabled = true,
            portIndex = 0,
            usageSetting = 0,
            isExpanded = true
        )
    }

    private fun randomIccidForDefault(): String {
        val body = buildString(18) {
            append("89860")
            while (length < 18) {
                append(iccidRandom.nextInt(10))
            }
        }
        return body + luhnCheckDigit(body)
    }

    private fun luhnCheckDigit(body: String): Int {
        val sum = body.reversed().mapIndexed { index, char ->
            val digit = char.digitToIntOrNull() ?: 0
            if (index % 2 == 0) {
                val doubled = digit * 2
                if (doubled > 9) doubled - 9 else doubled
            } else {
                digit
            }
        }.sum()
        return (10 - (sum % 10)) % 10
    }

    private fun getSystemSimInfoList(): List<SimInfo> {
        val simInfoList = mutableListOf<SimInfo>()
        val subscriptionManager = getSystemService(SubscriptionManager::class.java)
        val telephonyManager = getSystemService(TelephonyManager::class.java)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val subscriptionList = subscriptionManager.activeSubscriptionInfoList
        if (subscriptionList != null) {
            for (sub in subscriptionList) {
                simInfoList.add(
                    SimInfo(
                        id = sub.subscriptionId,
                        iccId = sub.iccId,
                        simSlotIndex = sub.simSlotIndex,
                        displayName = sub.displayName.toString(),
                        carrierName = sub.carrierName.toString(),
                        iconTint = sub.iconTint,
                        number = sub.number,
                        roaming = sub.dataRoaming,
                        mcc = sub.mccString,
                        mnc = sub.mncString,
                        countryIso = sub.countryIso,
                        isEmbedded = sub.isEmbedded,
                        cardId = sub.cardId,
                        isOpportunistic = sub.isOpportunistic,
                        groupUuid = sub.groupUuid?.toString(),
                        carrierId = sub.carrierId,
                        subType = sub.subscriptionType,
                        portIndex = sub.portIndex,
                        usageSetting = sub.usageSetting,
                        nativeAccessRules = null,
                        cardString = null,
                        isGroupDisabled = null,
                        profileClass = null,
                        groupOwner = null,
                        carrierConfigAccessRules = null,
                        areUiccApplicationsEnabled = null,
                        nameSource = null,
                        icon = null,
                        isExpanded = false
                    )
                )
            }
        }
        return simInfoList
    }

}
