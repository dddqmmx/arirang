package asia.nana7mi.arirang.ui.component.dialog

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.model.SimInfo

@Composable
fun HookLogDialog(
    modules: List<HookLogSettings.Module>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.log_settings_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                modules.forEach { module ->
                    var enabled by remember(module.key) {
                        mutableStateOf(HookLogSettings.isEnabled(context, module.key))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !enabled
                                if (HookLogSettings.setEnabled(context, module.key, next)) {
                                    enabled = next
                                } else {
                                    Toast.makeText(context, R.string.log_settings_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(module.labelRes),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = module.key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                if (HookLogSettings.setEnabled(context, module.key, checked)) {
                                    enabled = checked
                                } else {
                                    val toastMsg = context.getString(R.string.log_settings_save_failed)
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    )
}

class SimInfoSlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        inflate(context, R.layout.item_sim_info_slot, this)
        orientation = VERTICAL
    }

    // 通用扩展函数
    private fun EditText.getTextString() = text?.toString()?.trim()
    private fun EditText.getIntOrNull() = getTextString()?.toIntOrNull()
    private fun EditText.getBooleanOrNull() = getTextString()?.toBooleanStrictOrNull()
    private fun EditText.setTextString(value: String?) { setText(value ?: "") }
    private fun EditText.setTextInt(value: Int?) { setText(value?.toString() ?: "") }
    private fun EditText.setTextBoolean(value: Boolean?) { setText(value?.toString() ?: "") }

    fun setTitle(index: Int){
        findViewById<TextView>(R.id.slot_title).text = resources.getString(R.string.slot_title_text,index+1)
    }

    fun setOnRemoveClickListener(listener: () -> Unit) {
        findViewById<ImageView>(R.id.removeSimSlotIcon).setOnClickListener {
            listener()
        }
    }

    fun setSimInfo(simInfo: SimInfo) {
        findViewById<EditText>(R.id.edit_id).setTextInt(simInfo.id)
        findViewById<EditText>(R.id.edit_iccId).setTextString(simInfo.iccId)
        findViewById<EditText>(R.id.edit_simSlotIndex).setTextInt(simInfo.simSlotIndex)
        findViewById<EditText>(R.id.edit_displayName).setTextString(simInfo.displayName?.toString())
        findViewById<EditText>(R.id.edit_carrierName).setTextString(simInfo.carrierName?.toString())
        findViewById<EditText>(R.id.edit_nameSource).setTextInt(simInfo.nameSource)
        findViewById<EditText>(R.id.edit_iconTint).setTextInt(simInfo.iconTint)
        findViewById<EditText>(R.id.edit_number).setTextString(simInfo.number)
        findViewById<EditText>(R.id.edit_roaming).setTextInt(simInfo.roaming)
        findViewById<EditText>(R.id.edit_mcc).setTextString(simInfo.mcc)
        findViewById<EditText>(R.id.edit_mnc).setTextString(simInfo.mnc)
        findViewById<EditText>(R.id.edit_countryIso).setTextString(simInfo.countryIso)
        findViewById<EditText>(R.id.edit_isEmbedded).setTextBoolean(simInfo.isEmbedded)
        findViewById<EditText>(R.id.edit_cardString).setTextString(simInfo.cardString)
        findViewById<EditText>(R.id.edit_cardId).setTextInt(simInfo.cardId)
        findViewById<EditText>(R.id.edit_isOpportunistic).setTextBoolean(simInfo.isOpportunistic)
        findViewById<EditText>(R.id.edit_groupUuid).setTextString(simInfo.groupUuid)
        findViewById<EditText>(R.id.edit_isGroupDisabled).setTextBoolean(simInfo.isGroupDisabled)
        findViewById<EditText>(R.id.edit_carrierId).setTextInt(simInfo.carrierId)
        findViewById<EditText>(R.id.edit_profileClass).setTextInt(simInfo.profileClass)
        findViewById<EditText>(R.id.edit_subType).setTextInt(simInfo.subType)
        findViewById<EditText>(R.id.edit_groupOwner).setTextString(simInfo.groupOwner)
        findViewById<EditText>(R.id.edit_areUiccApplicationsEnabled).setTextBoolean(simInfo.areUiccApplicationsEnabled)
        findViewById<EditText>(R.id.edit_portIndex).setTextInt(simInfo.portIndex)
        findViewById<EditText>(R.id.edit_usageSetting).setTextInt(simInfo.usageSetting)
    }

    fun getSimInfo(): SimInfo {
        return SimInfo(
            id = findViewById<EditText>(R.id.edit_id).getIntOrNull(),
            iccId = findViewById<EditText>(R.id.edit_iccId).getTextString(),
            simSlotIndex = findViewById<EditText>(R.id.edit_simSlotIndex).getIntOrNull(),
            displayName = findViewById<EditText>(R.id.edit_displayName).getTextString(),
            carrierName = findViewById<EditText>(R.id.edit_carrierName).getTextString(),
            nameSource = findViewById<EditText>(R.id.edit_nameSource).getIntOrNull(),
            iconTint = findViewById<EditText>(R.id.edit_iconTint).getIntOrNull(),
            number = findViewById<EditText>(R.id.edit_number).getTextString(),
            roaming = findViewById<EditText>(R.id.edit_roaming).getIntOrNull(),
            icon = null,
            mcc = findViewById<EditText>(R.id.edit_mcc).getTextString(),
            mnc = findViewById<EditText>(R.id.edit_mnc).getTextString(),
            countryIso = findViewById<EditText>(R.id.edit_countryIso).getTextString(),
            isEmbedded = findViewById<EditText>(R.id.edit_isEmbedded).getBooleanOrNull(),
            nativeAccessRules = null,
            cardString = findViewById<EditText>(R.id.edit_cardString).getTextString(),
            cardId = findViewById<EditText>(R.id.edit_cardId).getIntOrNull(),
            isOpportunistic = findViewById<EditText>(R.id.edit_isOpportunistic).getBooleanOrNull(),
            groupUuid = findViewById<EditText>(R.id.edit_groupUuid).getTextString(),
            isGroupDisabled = findViewById<EditText>(R.id.edit_isGroupDisabled).getBooleanOrNull(),
            carrierId = findViewById<EditText>(R.id.edit_carrierId).getIntOrNull(),
            profileClass = findViewById<EditText>(R.id.edit_profileClass).getIntOrNull(),
            subType = findViewById<EditText>(R.id.edit_subType).getIntOrNull(),
            groupOwner = findViewById<EditText>(R.id.edit_groupOwner).getTextString(),
            carrierConfigAccessRules = null,
            areUiccApplicationsEnabled = findViewById<EditText>(R.id.edit_areUiccApplicationsEnabled).getBooleanOrNull(),
            portIndex = findViewById<EditText>(R.id.edit_portIndex).getIntOrNull(),
            usageSetting = findViewById<EditText>(R.id.edit_usageSetting).getIntOrNull()
        )
    }
}