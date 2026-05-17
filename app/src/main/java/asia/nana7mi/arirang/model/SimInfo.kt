package asia.nana7mi.arirang.model

import android.graphics.Bitmap
import com.google.gson.annotations.SerializedName

data class SimInfo(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("iccId")
    val iccId: String?,
    @SerializedName("simSlotIndex")
    val simSlotIndex: Int?,
    @SerializedName("displayName")
    val displayName: String?,
    @SerializedName("carrierName")
    val carrierName: String?,
    @SerializedName("nameSource")
    val nameSource: Int?,
    @SerializedName("iconTint")
    val iconTint: Int?,
    @SerializedName("number")
    val number: String?,
    @SerializedName("imei")
    val imei: String? = null,
    @SerializedName("roaming")
    val roaming: Int?,
    @SerializedName("icon")
    val icon: Bitmap?,
    @SerializedName("mcc")
    val mcc: String?,
    @SerializedName("mnc")
    val mnc: String?,
    @SerializedName("countryIso")
    val countryIso: String?,
    @SerializedName("isEmbedded")
    val isEmbedded: Boolean?,
    @SerializedName("nativeAccessRules")
    val nativeAccessRules: Object?,
    @SerializedName("cardString")
    val cardString: String?,
    @SerializedName("cardId")
    val cardId: Int?,
    @SerializedName("isOpportunistic")
    val isOpportunistic: Boolean?,
    @SerializedName("groupUuid")
    val groupUuid: String?,
    @SerializedName("isGroupDisabled")
    val isGroupDisabled: Boolean?,
    @SerializedName("carrierId")
    val carrierId: Int?,
    @SerializedName("profileClass")
    val profileClass: Int?,
    @SerializedName("subType")
    val subType: Int?,
    @SerializedName("groupOwner")
    val groupOwner: String?,
    @SerializedName("carrierConfigAccessRules")
    val carrierConfigAccessRules: Object?,
    @SerializedName("areUiccApplicationsEnabled")
    val areUiccApplicationsEnabled: Boolean?,
    @SerializedName("portIndex")
    val portIndex: Int?,
    @SerializedName("usageSetting")
    val usageSetting: Int?,
    @SerializedName("isExpanded")
    val isExpanded: Boolean = false
)
