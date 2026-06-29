package asia.nana7mi.arirang.hook.sim

import asia.nana7mi.arirang.hook.core.BaseHookModule

import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.callOneArgIfCompatible


internal fun copyOrRewriteSubscriptionInfo(
    subscriptionInfo: Any?,
    classLoader: ClassLoader?,
    profile: SimProfile
): Any? {
    val copy = createSubscriptionInfo(classLoader, subscriptionInfo, profile)
    if (copy != null) return copy
    if (subscriptionInfo != null) {
        rewriteSubscriptionInfo(subscriptionInfo, profile)
        return subscriptionInfo
    }
    return null
}

internal fun createSubscriptionInfo(
    classLoader: ClassLoader?,
    template: Any?,
    profile: SimProfile
): Any? {
    return runCatching {
        val subscriptionInfoClass = template?.javaClass
            ?: BaseHookModule.findClassIfExists("android.telephony.SubscriptionInfo", classLoader)
            ?: return@runCatching null
        val builderClass = BaseHookModule.findClassIfExists(
            "android.telephony.SubscriptionInfo\$Builder",
            classLoader ?: subscriptionInfoClass.classLoader
        ) ?: return@runCatching null

        val builder = if (template != null) {
            builderClass.getDeclaredConstructor(subscriptionInfoClass).newInstance(template)
        } else {
            builderClass.getDeclaredConstructor().newInstance()
        }

        applySubscriptionBuilderProfile(builder, profile)
        builderClass.getDeclaredMethod("build").invoke(builder)
    }.onFailure {
        HookLog.w(HookLog.Module.SIM, "failed to create virtual SubscriptionInfo: ${it.message}")
    }.getOrNull()
}

private fun applySubscriptionBuilderProfile(builder: Any, profile: SimProfile) {
    builder.callOneArgIfCompatible("setId", profile.subId)
    builder.callOneArgIfCompatible("setIccId", profile.iccId)
    builder.callOneArgIfCompatible("setSimSlotIndex", profile.slotIndex)
    builder.callOneArgIfCompatible("setDisplayName", profile.alphaLong)
    builder.callOneArgIfCompatible("setCarrierName", profile.alphaLong)
    builder.callOneArgIfCompatible("setDisplayNameSource", profile.displayNameSource)
    builder.callOneArgIfCompatible("setIconTint", profile.iconTint)
    builder.callOneArgIfCompatible("setNumber", profile.phoneNumber)
    builder.callOneArgIfCompatible("setDataRoaming", profile.roaming)
    builder.callOneArgIfCompatible("setMcc", profile.mcc)
    builder.callOneArgIfCompatible("setMnc", profile.mnc)
    builder.callOneArgIfCompatible("setMcc", profile.mcc.toIntOrNull() ?: 0)
    builder.callOneArgIfCompatible("setMnc", profile.mnc.toIntOrNull() ?: 0)
    builder.callOneArgIfCompatible("setCountryIso", profile.countryIso)
    builder.callOneArgIfCompatible("setEmbedded", profile.isEmbedded)
    builder.callOneArgIfCompatible("setCardString", profile.cardString)
    builder.callOneArgIfCompatible("setCardId", profile.cardId)
    builder.callOneArgIfCompatible("setOpportunistic", profile.isOpportunistic)
    builder.callOneArgIfCompatible("setGroupDisabled", profile.isGroupDisabled)
    builder.callOneArgIfCompatible("setCarrierId", profile.carrierId)
    builder.callOneArgIfCompatible("setProfileClass", profile.profileClass)
    builder.callOneArgIfCompatible("setType", profile.subType)
    builder.callOneArgIfCompatible("setGroupOwner", profile.groupOwner)
    builder.callOneArgIfCompatible("setUiccApplicationsEnabled", profile.areUiccApplicationsEnabled)
    builder.callOneArgIfCompatible("setPortIndex", profile.portIndex)
    builder.callOneArgIfCompatible("setUsageSetting", profile.usageSetting)
}
