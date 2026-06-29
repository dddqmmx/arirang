package asia.nana7mi.arirang.hook.sim

import asia.nana7mi.arirang.hook.core.HookBridge

import asia.nana7mi.arirang.hook.util.getFieldValue
import asia.nana7mi.arirang.hook.util.setFieldValueIfExists


internal fun rewriteSubscriptionInfo(subscriptionInfo: Any?, profile: SimProfile) {
    if (subscriptionInfo == null) return

    setFieldValueIfExists(subscriptionInfo, "mId", profile.subId)
    setFieldValueIfExists(subscriptionInfo, "mIccId", profile.iccId)
    setFieldValueIfExists(subscriptionInfo, "mSimSlotIndex", profile.slotIndex)
    setFieldValueIfExists(subscriptionInfo, "mNumber", profile.phoneNumber)
    setFieldValueIfExists(subscriptionInfo, "mCountryIso", profile.countryIso)
    setFieldValueIfExists(subscriptionInfo, "mMcc", profile.mcc)
    setFieldValueIfExists(subscriptionInfo, "mMnc", profile.mnc)
    setFieldValueIfExists(subscriptionInfo, "mDisplayName", profile.alphaLong)
    setFieldValueIfExists(subscriptionInfo, "mCarrierName", profile.alphaLong)
    setFieldValueIfExists(subscriptionInfo, "mDisplayNameSource", profile.displayNameSource)
    setFieldValueIfExists(subscriptionInfo, "mIconTint", profile.iconTint)
    setFieldValueIfExists(subscriptionInfo, "mDataRoaming", profile.roaming)
    setFieldValueIfExists(subscriptionInfo, "mCardString", profile.cardString)
    setFieldValueIfExists(subscriptionInfo, "mCardId", profile.cardId)
    setFieldValueIfExists(subscriptionInfo, "mIsEmbedded", profile.isEmbedded)
    setFieldValueIfExists(subscriptionInfo, "mIsOpportunistic", profile.isOpportunistic)
    setFieldValueIfExists(subscriptionInfo, "mIsGroupDisabled", profile.isGroupDisabled)
    setFieldValueIfExists(subscriptionInfo, "mCarrierId", profile.carrierId)
    setFieldValueIfExists(subscriptionInfo, "mProfileClass", profile.profileClass)
    setFieldValueIfExists(subscriptionInfo, "mType", profile.subType)
    setFieldValueIfExists(subscriptionInfo, "mGroupOwner", profile.groupOwner)
    setFieldValueIfExists(subscriptionInfo, "mAreUiccApplicationsEnabled", profile.areUiccApplicationsEnabled)
    setFieldValueIfExists(subscriptionInfo, "mPortIndex", profile.portIndex)
    setFieldValueIfExists(subscriptionInfo, "mUsageSetting", profile.usageSetting)
}

internal fun rewriteServiceState(serviceState: Any?, profile: SimProfile) {
    if (serviceState == null) return

    setFieldValueIfExists(serviceState, "mOperatorAlphaLong", profile.alphaLong)
    setFieldValueIfExists(serviceState, "mOperatorAlphaShort", profile.alphaShort)
    setFieldValueIfExists(serviceState, "mOperatorAlphaLongRaw", profile.alphaLong)
    setFieldValueIfExists(serviceState, "mOperatorAlphaShortRaw", profile.alphaShort)
    setFieldValueIfExists(serviceState, "mOperatorNumeric", profile.operatorNumeric)
    setFieldValueIfExists(serviceState, "mManualNetworkSelectionPlmn", profile.operatorNumeric)
    setFieldValueIfExists(serviceState, "mVoiceOperatorAlphaLong", profile.alphaLong)
    setFieldValueIfExists(serviceState, "mVoiceOperatorAlphaShort", profile.alphaShort)
    setFieldValueIfExists(serviceState, "mVoiceOperatorNumeric", profile.operatorNumeric)
    setFieldValueIfExists(serviceState, "mDataOperatorAlphaLong", profile.alphaLong)
    setFieldValueIfExists(serviceState, "mDataOperatorAlphaShort", profile.alphaShort)
    setFieldValueIfExists(serviceState, "mDataOperatorNumeric", profile.operatorNumeric)
    setFieldValueIfExists(serviceState, "mIsManualNetworkSelection", false)
    setFieldValueIfExists(serviceState, "mIsDataRoamingFromRegistration", false)
    setFieldValueIfExists(serviceState, "mIsEmergencyOnly", false)

    runCatching {
        val networkRegistrationInfos = getFieldValue(serviceState, "mNetworkRegistrationInfos") as? List<*>
        networkRegistrationInfos?.forEach { rewriteNestedTelephonyObject(it, profile) }
    }

    rewriteServiceStateNestedLists(serviceState, profile)

    runCatching {
        HookBridge.callMethod(
            serviceState,
            "setOperatorName",
            profile.alphaLong,
            profile.alphaShort,
            profile.operatorNumeric
        )
    }
}

internal fun rewriteNestedTelephonyObject(value: Any?, profile: SimProfile) {
    when (value) {
        null -> return
        is Iterable<*> -> value.forEach { rewriteNestedTelephonyObject(it, profile) }
        is Array<*> -> value.forEach { rewriteNestedTelephonyObject(it, profile) }
        is Map<*, *> -> value.values.forEach { rewriteNestedTelephonyObject(it, profile) }
        else -> rewriteKnownTelephonyObject(value, profile)
    }
}

internal fun rewriteCommonOperatorFields(instance: Any, profile: SimProfile) {
    setFieldValueIfExists(instance, "mMcc", profile.mcc.toIntOrNull() ?: 0)
    setFieldValueIfExists(instance, "mMnc", profile.mnc.toIntOrNull() ?: 0)
    setFieldValueIfExists(instance, "mMcc", profile.mcc)
    setFieldValueIfExists(instance, "mMnc", profile.mnc)
    setFieldValueIfExists(instance, "mMccStr", profile.mcc)
    setFieldValueIfExists(instance, "mMncStr", profile.mnc)
    setFieldValueIfExists(instance, "mMccString", profile.mcc)
    setFieldValueIfExists(instance, "mMncString", profile.mnc)
    setFieldValueIfExists(instance, "mAlphaLong", profile.alphaLong)
    setFieldValueIfExists(instance, "mAlphaShort", profile.alphaShort)
    setFieldValueIfExists(instance, "mOperatorAlphaLong", profile.alphaLong)
    setFieldValueIfExists(instance, "mOperatorAlphaShort", profile.alphaShort)
    setFieldValueIfExists(instance, "mOperatorNumeric", profile.operatorNumeric)
    setFieldValueIfExists(instance, "mOperatorNumericLong", profile.operatorNumeric)
    setFieldValueIfExists(instance, "mPlmn", profile.operatorNumeric)
    setFieldValueIfExists(instance, "mRegisteredPlmn", profile.operatorNumeric)
    setFieldValueIfExists(instance, "mRplmn", profile.operatorNumeric)
    setFieldValueIfExists(instance, "rRplmn", profile.operatorNumeric)
    setFieldValueIfExists(instance, "mCountryIso", profile.countryIso)
    setFieldValueIfExists(instance, "countryIso", profile.countryIso)
    setFieldValueIfExists(instance, "mCountryCode", profile.countryIso)
}

internal fun rewriteTelephonyDebugString(value: String, profile: SimProfile): String {
    return listOf(
        "mAlphaLong" to profile.alphaLong,
        "mAlphaShort" to profile.alphaShort,
        "mOperatorAlphaLong" to profile.alphaLong,
        "mOperatorAlphaShort" to profile.alphaShort,
        "mOperatorAlphaLongRaw" to profile.alphaLong,
        "mOperatorAlphaShortRaw" to profile.alphaShort,
        "mVoiceOperatorAlphaLong" to profile.alphaLong,
        "mVoiceOperatorAlphaShort" to profile.alphaShort,
        "mDataOperatorAlphaLong" to profile.alphaLong,
        "mDataOperatorAlphaShort" to profile.alphaShort,
        "mMcc" to profile.mcc,
        "mMnc" to profile.mnc,
        "mMccStr" to profile.mcc,
        "mMncStr" to profile.mnc,
        "mMccString" to profile.mcc,
        "mMncString" to profile.mnc,
        "mOperatorNumeric" to profile.operatorNumeric,
        "mVoiceOperatorNumeric" to profile.operatorNumeric,
        "mDataOperatorNumeric" to profile.operatorNumeric,
        "mManualNetworkSelectionPlmn" to profile.operatorNumeric,
        "mRegisteredPlmn" to profile.operatorNumeric,
        "mRplmn" to profile.operatorNumeric,
        "rRplmn" to profile.operatorNumeric,
        "mCountryIso" to profile.countryIso,
        "countryIso" to profile.countryIso,
        "mAddress" to profile.phoneNumber
    ).fold(value) { current, (fieldName, replacement) ->
        replaceDebugField(current, fieldName, replacement)
    }
}

private fun rewriteServiceStateNestedLists(serviceState: Any, profile: SimProfile) {
    listOf(
        "mNetworkRegistrationInfos",
        "mCellIdentityList",
        "mCellIdentity",
        "mCellInfo",
        "mOperatorInfo"
    ).forEach { fieldName ->
        rewriteNestedTelephonyObject(getFieldValue(serviceState, fieldName), profile)
    }
}

private fun rewriteKnownTelephonyObject(value: Any, profile: SimProfile) {
    val className = value.javaClass.name
    if (className.startsWith("android.telephony.ServiceState")) {
        rewriteServiceState(value, profile)
        return
    }

    if (!className.startsWith("android.telephony.NetworkRegistrationInfo") &&
        !className.startsWith("android.telephony.CellInfo") &&
        !className.startsWith("android.telephony.CellIdentity") &&
        !className.startsWith("android.telephony.OperatorInfo") &&
        !className.startsWith("android.telephony.emergency.EmergencyNumber")
    ) {
        return
    }

    rewriteCommonOperatorFields(value, profile)

    if (className.contains("EmergencyNumber")) {
        setFieldValueIfExists(value, "mAddress", if (profile.countryIso == "kp") "112" else "911")
    }

    var currentClass: Class<*>? = value.javaClass
    while (currentClass != null && currentClass != Any::class.java) {
        currentClass.declaredFields.forEach { field ->
            runCatching {
                field.isAccessible = true
                val child = field.get(value)
                if (child != null && child !== value && !child.javaClass.isPrimitive) {
                    rewriteNestedTelephonyObject(child, profile)
                }
            }
        }
        currentClass = currentClass.superclass
    }
}

private fun replaceDebugField(value: String, fieldName: String, replacement: String): String {
    val pattern = Regex("(${Regex.escape(fieldName)}\\s*=\\s*)([^,}\\]]*)")
    return pattern.replace(value) { match ->
        match.groupValues[1] + replacement
    }
}
