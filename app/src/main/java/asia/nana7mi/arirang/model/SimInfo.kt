package asia.nana7mi.arirang.model

import android.graphics.Bitmap

data class SimInfo(
    val id: Int?,
    val iccId: String?,
    val simSlotIndex: Int?,
    val displayName: String?,
    val carrierName: String?,
    val nameSource: Int?,
    val iconTint: Int?,
    val number: String?,
    val roaming: Int?,
    val icon: Bitmap?,
    val mcc: String?,
    val mnc: String?,
    val countryIso: String?,
    val isEmbedded: Boolean?,
    val nativeAccessRules: Object?,
    val cardString: String?,
    val cardId: Int?,
    val isOpportunistic: Boolean?,
    val groupUuid: String?,
    val isGroupDisabled: Boolean?,
    val carrierId: Int?,
    val profileClass: Int?,
    val subType: Int?,
    val groupOwner: String?,
    val carrierConfigAccessRules: Object?,
    val areUiccApplicationsEnabled: Boolean?,
    val portIndex: Int?,
    val usageSetting: Int?,
    val isExpanded: Boolean = false
)
