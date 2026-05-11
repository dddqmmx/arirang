package asia.nana7mi.arirang.ui.item

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.SimInfo
import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
