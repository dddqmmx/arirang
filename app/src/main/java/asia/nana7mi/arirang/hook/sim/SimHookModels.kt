package asia.nana7mi.arirang.hook.sim

internal object SimHookDefaults {
    const val DISPLAY_NAME_SOURCE = 0
    const val SUBSCRIPTION_TYPE_LOCAL_SIM = 0
    const val PROFILE_CLASS_UNSET = -1

    val PROFILES_BY_SLOT = mapOf(
        0 to SimProfile(
            slotIndex = 0,
            subId = 1,
            iccId = "898500000000000001",
            countryIso = "kp",
            mcc = "467",
            mnc = "05",
            alphaLong = "Koryolink",
            phoneNumber = "+8501912345678",
            imei = "356938031000004",
            cardId = 0,
            portIndex = 0
        ),
        1 to SimProfile(
            slotIndex = 1,
            subId = 2,
            iccId = "8970101000000000001",
            countryIso = "ru",
            mcc = "250",
            mnc = "01",
            alphaLong = "MTS RUS",
            phoneNumber = "+79161234567",
            imei = "356938031000012",
            cardId = 1,
            portIndex = 1
        )
    )
}

internal data class SimProfile(
    val slotIndex: Int,
    val subId: Int,
    val iccId: String,
    val countryIso: String,
    val mcc: String,
    val mnc: String,
    val alphaLong: String,
    val phoneNumber: String,
    val imei: String,
    val alphaShort: String = alphaLong,
    val carrierId: Int = -1,
    val cardId: Int = slotIndex,
    val cardString: String = iccId,
    val displayNameSource: Int = SimHookDefaults.DISPLAY_NAME_SOURCE,
    val iconTint: Int = 0,
    val roaming: Int = 0,
    val isEmbedded: Boolean = false,
    val isOpportunistic: Boolean = false,
    val isGroupDisabled: Boolean = false,
    val profileClass: Int = SimHookDefaults.PROFILE_CLASS_UNSET,
    val subType: Int = SimHookDefaults.SUBSCRIPTION_TYPE_LOCAL_SIM,
    val groupOwner: String = "",
    val areUiccApplicationsEnabled: Boolean = true,
    val portIndex: Int = 0,
    val usageSetting: Int = 0
) {
    val operatorNumeric: String = mcc + mnc
    val typeAllocationCode: String = imei.take(8)
}

internal data class SimHookConfig(
    val enabled: Boolean,
    val hideSim: Boolean,
    val profilesBySlot: Map<Int, SimProfile>,
    val uniqueIdentifiers: UniqueIdentifierHookConfig
) {
    val visibleProfilesBySlot: Map<Int, SimProfile> =
        if (hideSim) emptyMap() else profilesBySlot.toSortedMap()
    val visibleProfiles: List<SimProfile> = visibleProfilesBySlot.values.toList()
    val primaryProfile: SimProfile = visibleProfiles.firstOrNull()
        ?: SimHookDefaults.PROFILES_BY_SLOT.values.first()
    private val visibleSlotRange: IntRange = 0..(visibleProfilesBySlot.keys.maxOrNull() ?: -1)
    val countryIsoList: List<String> = visibleSlotRange.map { visibleProfilesBySlot[it]?.countryIso ?: "" }
    val operatorNumericList: List<String> = visibleSlotRange.map { visibleProfilesBySlot[it]?.operatorNumeric ?: "" }
    val alphaList: List<String> = visibleSlotRange.map { visibleProfilesBySlot[it]?.alphaLong ?: "" }
    val countryIsoPropertyValue: String = countryIsoList.joinToString(",")
    val operatorNumericPropertyValue: String = operatorNumericList.joinToString(",")
    val alphaPropertyValue: String = alphaList.joinToString(",")
}

internal data class UniqueIdentifierHookConfig(
    val enabled: Boolean = false,
    val imeiBySlot: Map<Int, String> = SimHookDefaults.PROFILES_BY_SLOT.mapValues { it.value.imei },
    val tacBySlot: Map<Int, String> = SimHookDefaults.PROFILES_BY_SLOT.mapValues { it.value.typeAllocationCode }
) {
    fun imeiForSlot(slotIndex: Int?, fallback: String?): String? {
        if (!enabled) return fallback
        if (slotIndex != null) {
            imeiBySlot[slotIndex]?.let { return it }
        }
        return imeiBySlot.toSortedMap().values.firstOrNull() ?: fallback
    }

    fun typeAllocationCodeForSlot(slotIndex: Int?, fallback: String?): String? {
        if (!enabled) return fallback
        if (slotIndex != null) {
            tacBySlot[slotIndex]?.let { return it }
            imeiBySlot[slotIndex]?.take(8)?.let { return it }
        }
        return tacBySlot.toSortedMap().values.firstOrNull()
            ?: imeiBySlot.toSortedMap().values.firstOrNull()?.take(8)
            ?: fallback
    }
}
