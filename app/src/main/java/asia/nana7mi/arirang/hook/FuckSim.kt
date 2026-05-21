package asia.nana7mi.arirang.hook

import android.os.Binder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Array as ReflectArray

/**
 * System-side feasibility proof for SIM country and phone-number spoofing.
 *
 * This rewrites the telephony-facing subscription and phone-number read paths, but does not touch
 * the low-level radio stack or subscription database writes.
 */
// Telephony identifiers are rewritten from the phone process and system_server only.
// Do not broaden this module to arbitrary app packages; callers should observe the
// rewritten values through framework services.
class FuckSim : BaseHookModule(targetPackages = setOf("com.android.phone", "android")) {

    private companion object {
        private const val PREFS_NAME = "sim_config_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HIDE_SIM = "hide_sim"
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_SIM_INFO_MAP = "sim_info_map"
        private const val KEY_SIM_INFO_LIST = "sim_info_list"
        private const val UNIQUE_PREFS_NAME = "unique_identifier_prefs"
        private const val KEY_UNIQUE_ENABLED = "enabled"
        private const val KEY_IMEI_BY_SLOT = "imei_by_slot"
        private const val KEY_TAC_BY_SLOT = "tac_by_slot"

        private const val DEFAULT_DISPLAY_NAME_SOURCE = 0
        private const val DEFAULT_SUBSCRIPTION_TYPE_LOCAL_SIM = 0
        private const val DEFAULT_PROFILE_CLASS_UNSET = -1
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L

        private val SYSTEM_PROPERTY_KEYS = setOf(
            "gsm.sim.operator.iso-country",
            "gsm.sim.operator.numeric",
            "gsm.sim.operator.alpha",
            "gsm.operator.iso-country",
            "gsm.operator.numeric",
            "gsm.operator.alpha",
            "gsm.operator.isroaming",
            "gsm.network.type",
            "persist.radio.multisim.config",
            "ro.telephony.default_network"
        )

        private val DEFAULT_PROFILES_BY_SLOT = mapOf(
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

    private data class SimProfile(
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
        val displayNameSource: Int = DEFAULT_DISPLAY_NAME_SOURCE,
        val iconTint: Int = 0,
        val roaming: Int = 0,
        val isEmbedded: Boolean = false,
        val isOpportunistic: Boolean = false,
        val isGroupDisabled: Boolean = false,
        val profileClass: Int = DEFAULT_PROFILE_CLASS_UNSET,
        val subType: Int = DEFAULT_SUBSCRIPTION_TYPE_LOCAL_SIM,
        val groupOwner: String = "",
        val areUiccApplicationsEnabled: Boolean = true,
        val portIndex: Int = 0,
        val usageSetting: Int = 0
    ) {
        val operatorNumeric: String = mcc + mnc
        val typeAllocationCode: String = imei.take(8)
    }

    private data class HookConfig(
        val enabled: Boolean,
        val hideSim: Boolean,
        val profilesBySlot: Map<Int, SimProfile>,
        val uniqueIdentifiers: UniqueIdentifierConfig
    ) {
        val visibleProfilesBySlot: Map<Int, SimProfile> =
            if (hideSim) emptyMap() else profilesBySlot.toSortedMap()
        val visibleProfiles: List<SimProfile> = visibleProfilesBySlot.values.toList()
        val primaryProfile: SimProfile = visibleProfiles.firstOrNull()
            ?: DEFAULT_PROFILES_BY_SLOT.values.first()
        private val visibleSlotRange: IntRange = 0..(visibleProfilesBySlot.keys.maxOrNull() ?: -1)
        val countryIsoList: List<String> = visibleSlotRange.map { visibleProfilesBySlot[it]?.countryIso ?: "" }
        val operatorNumericList: List<String> = visibleSlotRange.map { visibleProfilesBySlot[it]?.operatorNumeric ?: "" }
        val alphaList: List<String> = visibleSlotRange.map { visibleProfilesBySlot[it]?.alphaLong ?: "" }
        val countryIsoPropertyValue: String = countryIsoList.joinToString(",")
        val operatorNumericPropertyValue: String = operatorNumericList.joinToString(",")
        val alphaPropertyValue: String = alphaList.joinToString(",")
    }

    private data class UniqueIdentifierConfig(
        val enabled: Boolean = false,
        val imeiBySlot: Map<Int, String> = DEFAULT_PROFILES_BY_SLOT.mapValues { it.value.imei },
        val tacBySlot: Map<Int, String> = DEFAULT_PROFILES_BY_SLOT.mapValues { it.value.typeAllocationCode }
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

    @Volatile
    private var cachedHookConfig: HookConfig? = null

    @Volatile
    private var lastHookConfigRefreshAt = 0L

    @Volatile
    private var preferHookNotifyConfig = false

    @Volatile
    private var phoneConfigBindHookInstalled = false

    private val hookConfig: HookConfig
        get() = currentHookConfig()

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            preferHookNotifyConfig = lpparam.packageName == "android" || lpparam.packageName == "com.android.phone"
            val config = hookConfig
            when (lpparam.packageName) {
                "android" -> {
                    hookSystemServerSurfaces(lpparam.classLoader)
                    XposedBridge.log("Arirang: SIM proof system_server hook installed enabled=${config.enabled}")
                }
                "com.android.phone" -> {
                    hookPhoneProcessSurfaces(lpparam.classLoader)
                    XposedBridge.log("Arirang: SIM proof phone process hook installed enabled=${config.enabled}")
                }
            }
        }.onFailure {
            XposedBridge.log("Arirang: SIM country proof hook failed: ${Log.getStackTraceString(it)}")
        }
    }

    private fun hookSystemServerSurfaces(classLoader: ClassLoader) {
        hookTelephonyManagerUniqueIdentifierReaders(classLoader)
        hookServiceStateSurfaces(classLoader)
        hookSubscriptionServiceSurfaces(classLoader, externalClientsOnly = false)
        hookSystemPropertyReaders(classLoader)
        hookGeneratedTelephonyProperties(classLoader)
        writeProofTelephonyProperties()
    }

    private fun hookPhoneProcessSurfaces(classLoader: ClassLoader) {
        hookPhoneConfigServiceBind(classLoader)
        hookServiceStateSurfaces(classLoader)
        hookSubscriptionServiceSurfaces(classLoader, externalClientsOnly = false)
        hookPhoneInterfaceManager(classLoader)
        hookPhoneSubInfoController(classLoader)
        hookTelephonyManagerUniqueIdentifierReaders(classLoader)
        hookTelephonyPropertyWriters(classLoader)
        hookSystemPropertyWriters(classLoader)
        hookSystemPropertyReaders(classLoader)
        hookGeneratedTelephonyProperties(classLoader)
    }

    private fun hookPhoneConfigServiceBind(classLoader: ClassLoader) {
        if (phoneConfigBindHookInstalled) return
        phoneConfigBindHookInstalled = true

        val applicationClass = XposedHelpers.findClassIfExists("android.app.Application", classLoader) ?: return
        XposedBridge.hookAllMethods(applicationClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? android.app.Application ?: return
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    HookNotifyClient.autoBindCurrentUser(app)
                    currentHookConfig(force = true)
                    writeProofTelephonyProperties()
                }, 5_000L)
            }
        })
    }

    private fun hookServiceStateSurfaces(classLoader: ClassLoader) {
        val serviceStateClass = XposedHelpers.findClassIfExists(
            "android.telephony.ServiceState",
            classLoader
        ) ?: return

        hookCellIdentitySurfaces(classLoader)
        hookCellInfoSurfaces(classLoader)
        hookOperatorInfoSurfaces(classLoader)
        hookNetworkRegistrationInfoSurfaces(classLoader)
        hookTelephonyObjectReaders(classLoader)
        hookServiceStateAccessors(serviceStateClass)

        listOf(
            "com.android.phone.PhoneInterfaceManager",
            "com.android.server.TelephonyRegistry"
        ).forEach { className ->
            val targetClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

            targetClass.declaredMethods
                .filter { it.returnType == serviceStateClass }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = hookConfig
                            if (!config.enabled) return
                            rewriteServiceState(param.result, config.primaryProfile)
                        }
                    })
                }

            targetClass.declaredMethods
                .filter { method -> method.parameterTypes.any { it == serviceStateClass } }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = hookConfig
                            if (!config.enabled) return
                            param.args.forEach { rewriteServiceState(it, config.primaryProfile) }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = hookConfig
                            if (!config.enabled) return
                            rewriteServiceState(param.result, config.primaryProfile)
                        }
                    })
                }
        }
    }

    private fun hookPhoneInterfaceManager(classLoader: ClassLoader) {
        val phoneInterfaceManagerClass = XposedHelpers.findClassIfExists(
            "com.android.phone.PhoneInterfaceManager",
            classLoader
        ) ?: run {
            XposedBridge.log("Arirang: PhoneInterfaceManager not found for SIM country proof")
            return
        }

        mapOf<String, (SimProfile) -> Any?>(
            "getSimCountryIso" to { it.countryIso },
            "getSimOperator" to { it.operatorNumeric },
            "getSimOperatorNumeric" to { it.operatorNumeric },
            "getSimOperatorName" to { it.alphaLong },
            "getNetworkCountryIso" to { it.countryIso },
            "getNetworkOperator" to { it.operatorNumeric },
            "getNetworkOperatorName" to { it.alphaLong },
            "getSimCountryIsoForPhone" to { it.countryIso },
            "getSimOperatorForPhone" to { it.operatorNumeric },
            "getSimOperatorNumericForPhone" to { it.operatorNumeric },
            "getSimOperatorNameForPhone" to { it.alphaLong },
            "getNetworkCountryIsoForPhone" to { it.countryIso },
            "getNetworkOperatorForPhone" to { it.operatorNumeric },
            "getNetworkOperatorName" to { it.alphaLong }
        ).forEach { (methodName, valueProvider) ->
            hookProfileMethod(phoneInterfaceManagerClass, methodName) { param, _ ->
                valueProvider(
                    profileForSlot(param.args.firstIntOrNull(), allowFallback = false)
                        ?: return@hookProfileMethod ""
                )
            }
        }

        mapOf<String, (SimProfile) -> Any?>(
            "getSimCarrierId" to { it.carrierId },
            "getCarrierIdFromSimMccMnc" to { it.carrierId },
            "getSimSpecificCarrierId" to { it.carrierId }
        ).forEach { (methodName, valueProvider) ->
            hookProfileMethod(phoneInterfaceManagerClass, methodName) { param, method ->
                val profile = when {
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType ->
                        profileForSubId(param.args.firstIntOrNull(), allowFallback = false)
                            ?: profileForSlot(param.args.firstIntOrNull(), allowFallback = false)
                    else -> hookConfig.primaryProfile
                }
                valueProvider(profile ?: return@hookProfileMethod -1)
            }
        }

        mapOf<String, (SimProfile) -> Any?>(
            "getSimCarrierIdName" to { it.alphaLong },
            "getSimSpecificCarrierIdName" to { it.alphaLong }
        ).forEach { (methodName, valueProvider) ->
            hookProfileMethod(phoneInterfaceManagerClass, methodName) { _, _ ->
                valueProvider(hookConfig.primaryProfile)
            }
        }

        mapOf<String, (SimProfile) -> Any?>(
            "getForbiddenPlmns" to { emptyArray<String>() },
            "getEquivalentHomePlmns" to { emptyList<String>() },
            "getTypeAllocationCode" to { it.typeAllocationCode },
            "getImei" to { it.imei },
            "getImeiForSlot" to { it.imei },
            "getMeid" to { null },
            "getDeviceId" to { it.imei },
            "getDeviceIdForPhone" to { it.imei },
            "getDeviceIdWithFeature" to { it.imei }
        ).forEach { (methodName, valueProvider) ->
            hookProfileMethod(
                phoneInterfaceManagerClass,
                methodName,
                beforeOriginal = true,
                shouldHandle = { it.enabled || it.uniqueIdentifiers.enabled }
            ) { param, method ->
                val profile = when {
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType ->
                        profileForSlot(param.args.firstIntOrNull(), allowFallback = false)
                    else -> profileForSlot(null, allowFallback = true)
                }
                if (methodName == "getTypeAllocationCode") {
                    val slot = param.args.firstIntOrNull() ?: profile?.slotIndex
                    hookConfig.uniqueIdentifiers.typeAllocationCodeForSlot(slot, profile?.typeAllocationCode)
                } else if (methodName.contains("Imei", ignoreCase = true) || methodName.contains("DeviceId", ignoreCase = true)) {
                    val slot = param.args.firstIntOrNull() ?: profile?.slotIndex
                    hookConfig.uniqueIdentifiers.imeiForSlot(slot, profile?.imei)
                } else {
                    valueProvider(profile ?: return@hookProfileMethod null)
                }
            }
        }

        hookPhoneInterfaceManagerObjectReaders(phoneInterfaceManagerClass)

        hookAllExistingStringMethods(
            targetClass = phoneInterfaceManagerClass,
            methodNames = listOf("getLine1NumberForDisplay")
        ) { param, method ->
            phoneNumberForCall(param, method)
        }
    }

    private fun hookPhoneInterfaceManagerObjectReaders(phoneInterfaceManagerClass: Class<*>) {
        listOf(
            "getAllCellInfo",
            "getCellLocation",
            "getServiceState",
            "getEmergencyNumberList"
        ).forEach { methodName ->
            phoneInterfaceManagerClass.declaredMethods
                .filter { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = hookConfig
                            if (!config.enabled) return
                            rewriteNestedTelephonyObject(param.result, config.primaryProfile)
                        }
                    })
                }
        }
    }

    private fun hookPhoneSubInfoController(classLoader: ClassLoader) {
        hookAllExistingStringMethods(
            classLoader = classLoader,
            className = "com.android.internal.telephony.PhoneSubInfoController",
            methodNames = listOf(
                "getLine1Number",
                "getLine1NumberForSubscriber",
                "getMsisdn",
                "getMsisdnForSubscriber"
            )
        ) { param, method ->
            phoneNumberForCall(param, method)
        }

        hookAllExistingStringMethods(
            classLoader = classLoader,
            className = "com.android.internal.telephony.PhoneSubInfoController",
            methodNames = listOf(
                "getDeviceId",
                "getDeviceIdForPhone",
                "getDeviceIdWithFeature",
                "getImei",
                "getImeiForSubscriber"
            ),
            beforeOriginal = true,
            shouldHandle = { it.enabled || it.uniqueIdentifiers.enabled }
        ) { param, method ->
            imeiForCall(param, method)
        }
    }

    private fun hookTelephonyManagerUniqueIdentifierReaders(classLoader: ClassLoader) {
        val telephonyManagerClass = XposedHelpers.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        hookAllExistingStringMethods(
            targetClass = telephonyManagerClass,
            methodNames = listOf("getTypeAllocationCode"),
            beforeOriginal = true,
            shouldHandle = { it.enabled || it.uniqueIdentifiers.enabled }
        ) { param, method ->
            typeAllocationCodeForCall(param, method)
        }
    }

    private fun hookServiceStateAccessors(serviceStateClass: Class<*>) {
        mapOf<String, (SimProfile) -> Any?>(
            "getOperatorAlphaLong" to { it.alphaLong },
            "getOperatorAlphaShort" to { it.alphaShort },
            "getOperatorNumeric" to { it.operatorNumeric },
            "getVoiceOperatorAlphaLong" to { it.alphaLong },
            "getVoiceOperatorAlphaShort" to { it.alphaShort },
            "getVoiceOperatorNumeric" to { it.operatorNumeric },
            "getDataOperatorAlphaLong" to { it.alphaLong },
            "getDataOperatorAlphaShort" to { it.alphaShort },
            "getDataOperatorNumeric" to { it.operatorNumeric },
            "getManualNetworkSelectionPlmn" to { it.operatorNumeric }
        ).forEach { (methodName, valueProvider) ->
            hookTelephonyObjectAccessor(serviceStateClass, methodName, valueProvider)
        }

        hookTelephonyToString(serviceStateClass) { instance, profile ->
            rewriteServiceState(instance, profile)
        }
    }

    private fun hookTelephonyObjectReaders(classLoader: ClassLoader) {
        val telephonyManagerClass = XposedHelpers.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        listOf(
            "getAllCellInfo",
            "getCellLocation",
            "getServiceState",
            "getEmergencyNumberList"
        ).forEach { methodName ->
            telephonyManagerClass.declaredMethods
                .filter { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = hookConfig
                            if (!config.enabled) return
                            rewriteNestedTelephonyObject(param.result, config.primaryProfile)
                        }
                    })
                }
        }
    }

    private fun hookOperatorInfoSurfaces(classLoader: ClassLoader) {
        val operatorInfoClass = XposedHelpers.findClassIfExists(
            "android.telephony.OperatorInfo",
            classLoader
        ) ?: return

        mapOf<String, (SimProfile) -> Any?>(
            "getOperatorAlphaLong" to { it.alphaLong },
            "getOperatorAlphaShort" to { it.alphaShort },
            "getOperatorNumeric" to { it.operatorNumeric }
        ).forEach { (methodName, valueProvider) ->
            hookTelephonyObjectAccessor(operatorInfoClass, methodName, valueProvider)
        }

        hookTelephonyToString(operatorInfoClass) { instance, profile ->
            rewriteCommonOperatorFields(instance, profile)
        }
    }

    private fun hookNetworkRegistrationInfoSurfaces(classLoader: ClassLoader) {
        val networkRegistrationInfoClass = XposedHelpers.findClassIfExists(
            "android.telephony.NetworkRegistrationInfo",
            classLoader
        ) ?: return

        mapOf<String, (SimProfile) -> Any?>(
            "getRegisteredPlmn" to { it.operatorNumeric },
            "getMcc" to { it.mcc.toIntOrNull() ?: 0 },
            "getMnc" to { it.mnc.toIntOrNull() ?: 0 },
            "getMccString" to { it.mcc },
            "getMncString" to { it.mnc }
        ).forEach { (methodName, valueProvider) ->
            hookTelephonyObjectAccessor(networkRegistrationInfoClass, methodName, valueProvider)
        }

        networkRegistrationInfoClass.declaredMethods
            .filter { method -> !Modifier.isAbstract(method.modifiers) }
            .filter { method ->
                method.name == "getCellIdentity" ||
                    method.returnType.name.startsWith("android.telephony.CellIdentity")
            }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = hookConfig
                        if (!config.enabled) return
                        rewriteNestedTelephonyObject(param.result, config.primaryProfile)
                    }
                })
            }

        hookTelephonyToString(networkRegistrationInfoClass) { instance, profile ->
            rewriteNestedTelephonyObject(instance, profile)
        }
    }

    private fun hookTelephonyObjectAccessor(
        targetClass: Class<*>,
        methodName: String,
        valueProvider: (SimProfile) -> Any?
    ) {
        targetClass.declaredMethods
            .filter { it.name == methodName && it.parameterTypes.isEmpty() && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = hookConfig
                        if (!config.enabled) return
                        rewriteNestedTelephonyObject(param.thisObject, config.primaryProfile)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = hookConfig
                        if (!config.enabled) return
                        param.result = coerceHookResult(valueProvider(config.primaryProfile), param.result)
                    }
                })
            }
    }

    private fun hookTelephonyToString(
        targetClass: Class<*>,
        rewriter: (Any, SimProfile) -> Unit
    ) {
        targetClass.declaredMethods
            .filter { it.name == "toString" && it.parameterTypes.isEmpty() && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = hookConfig
                        if (!config.enabled) return
                        rewriter(param.thisObject, config.primaryProfile)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = hookConfig
                        if (!config.enabled || param.result !is String) return
                        param.result = rewriteTelephonyDebugString(param.result as String, config.primaryProfile)
                    }
                })
            }
    }

    private fun hookSubscriptionServiceSurfaces(classLoader: ClassLoader, externalClientsOnly: Boolean) {
        val serviceClasses = listOf(
            "com.android.server.telephony.subscription.SubscriptionManagerService",
            "com.android.server.telephony.SubscriptionController",
            "com.android.internal.telephony.subscription.SubscriptionManagerService",
            "com.android.internal.telephony.SubscriptionController"
        ).mapNotNull { XposedHelpers.findClassIfExists(it, classLoader) }

        if (serviceClasses.isEmpty()) {
            XposedBridge.log("Arirang: Subscription service not found for SIM proof")
            return
        }

        serviceClasses.forEach { serviceClass ->
            hookSubscriptionInfoReaders(serviceClass, externalClientsOnly)
            hookSubscriptionPhoneNumberReaders(serviceClass, externalClientsOnly)
            hookSubscriptionCounts(serviceClass, externalClientsOnly)
            hookSubscriptionIdReaders(serviceClass, externalClientsOnly)
            XposedBridge.log(
                "Arirang: hooked SIM subscription service ${serviceClass.name} externalOnly=$externalClientsOnly"
            )
        }
    }

    private fun hookSubscriptionInfoReaders(serviceClass: Class<*>, externalClientsOnly: Boolean = false) {
        listOf(
            "getActiveSubscriptionInfoList",
            "getAvailableSubscriptionInfoList",
            "getAccessibleSubscriptionInfoList",
            "getAllSubInfoList",
            "getAllSubInfoListForIccId",
            "getCompleteActiveSubscriptionInfoList",
            "getAllSubscriptionInfoList",
            "getSubscriptionInfo",
            "getActiveSubscriptionInfo",
            "getActiveSubscriptionInfoForSimSlotIndex",
            "getActiveSubscriptionInfoForIcc",
            "getActiveSubscriptionInfoForIccId"
        ).forEach { methodName ->
            hookSubscriptionInfoResult(serviceClass, methodName, externalClientsOnly)
        }
    }

    private fun hookSubscriptionPhoneNumberReaders(serviceClass: Class<*>, externalClientsOnly: Boolean = false) {
        hookAllExistingStringMethods(
            targetClass = serviceClass,
            methodNames = listOf("getPhoneNumber", "getPhoneNumberFromFirstAvailableSource"),
            externalClientsOnly = externalClientsOnly
        ) { param, _ ->
            profileForSubId(param.args.firstIntOrNull(), allowFallback = false)?.phoneNumber
        }
    }

    private fun hookTelephonyPropertyWriters(classLoader: ClassLoader) {
        val telephonyManagerClass = XposedHelpers.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(telephonyManagerClass, "setTelephonyProperty", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hookConfig.enabled) return
                val profile = profileForSlot(
                    param.args.getOrNull(0).asIntOrNull(),
                    allowFallback = false
                ) ?: return
                val propertyName = param.args.getOrNull(1)?.toString() ?: return
                when {
                    propertyName.endsWith("icc_operator_iso_country") -> param.args[2] = profile.countryIso
                    propertyName.endsWith("sim.operator.iso-country") -> param.args[2] = profile.countryIso
                    propertyName.endsWith("operator.iso-country") -> param.args[2] = profile.countryIso
                    propertyName.endsWith("icc_operator_numeric") -> param.args[2] = profile.operatorNumeric
                    propertyName.endsWith("sim.operator.numeric") -> param.args[2] = profile.operatorNumeric
                    propertyName.endsWith("operator.numeric") -> param.args[2] = profile.operatorNumeric
                    propertyName.endsWith("icc_operator_alpha") -> param.args[2] = profile.alphaLong
                    propertyName.endsWith("sim.operator.alpha") -> param.args[2] = profile.alphaLong
                    propertyName.endsWith("operator.alpha") -> param.args[2] = profile.alphaLong
                }
            }
        })

        hookSetterValue(telephonyManagerClass, "setSimCountryIso") { param ->
            profileForTelephonyManager(param)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setSimCountryIsoForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), allowFallback = false)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorNumeric") { param ->
            profileForTelephonyManager(param)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorNumericForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), allowFallback = false)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorName") { param ->
            profileForTelephonyManager(param)?.alphaLong
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorNameForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), allowFallback = false)?.alphaLong
        }
        hookSetterValue(telephonyManagerClass, "setNetworkCountryIso") { param ->
            profileForTelephonyManager(param)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setNetworkCountryIsoForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), allowFallback = false)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNumeric") { param ->
            profileForTelephonyManager(param)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNumericForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), allowFallback = false)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorName") { param ->
            profileForTelephonyManager(param)?.alphaLong
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNameForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), allowFallback = false)?.alphaLong
        }
    }

    private fun hookSystemPropertyWriters(classLoader: ClassLoader) {
        val systemPropertiesClass = XposedHelpers.findClassIfExists("android.os.SystemProperties", null)
            ?: XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader)
            ?: return

        systemPropertiesClass.declaredMethods
            .filter {
                it.name == "set" &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val override = systemPropertyOverride(param.args.firstOrNull()) ?: return
                        param.args[1] = override
                    }
                })
            }
    }

    private fun hookGeneratedTelephonyProperties(classLoader: ClassLoader) {
        val telephonyPropertiesClass = XposedHelpers.findClassIfExists(
            "android.sysprop.TelephonyProperties",
            classLoader
        ) ?: run {
            XposedBridge.log("Arirang: android.sysprop.TelephonyProperties not found")
            return
        }

        mapOf<String, (HookConfig) -> List<String>>(
            "icc_operator_iso_country" to { it.countryIsoList },
            "icc_operator_numeric" to { it.operatorNumericList },
            "icc_operator_alpha" to { it.alphaList },
            "sim_operator_iso_country" to { it.countryIsoList },
            "sim_operator_numeric" to { it.operatorNumericList },
            "sim_operator_alpha" to { it.alphaList },
            "operator_iso_country" to { it.countryIsoList },
            "operator_numeric" to { it.operatorNumericList },
            "operator_alpha" to { it.alphaList }
        ).forEach { (methodName, value) ->
            XposedBridge.hookAllMethods(telephonyPropertiesClass, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size == 1 && param.args[0] is List<*>) {
                        val config = hookConfig
                        if (!config.enabled) return
                        param.args[0] = value(config)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty() && param.result is List<*>) {
                        val config = hookConfig
                        if (!config.enabled) return
                        param.result = value(config)
                    }
                }
            })
        }
    }

    private fun hookSystemPropertyReaders(classLoader: ClassLoader) {
        val systemPropertiesClass = XposedHelpers.findClassIfExists("android.os.SystemProperties", null)
            ?: XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader)
            ?: return

        systemPropertiesClass.declaredMethods
            .filter {
                it.name == "get" &&
                    it.returnType == String::class.java &&
                    it.parameterTypes.firstOrNull() == String::class.java
            }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val override = systemPropertyOverride(param.args.firstOrNull()) ?: return
                        param.result = override
                    }
                })
            }
    }

    private fun hookSetterValue(
        telephonyManagerClass: Class<*>,
        methodName: String,
        valueProvider: (XC_MethodHook.MethodHookParam) -> String?
    ) {
        if (telephonyManagerClass.declaredMethods.none { it.name == methodName }) return

        XposedBridge.hookAllMethods(telephonyManagerClass, methodName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hookConfig.enabled) return
                val value = valueProvider(param) ?: return
                val valueIndex = param.args.indexOfLast { it is String }
                if (valueIndex >= 0) param.args[valueIndex] = value
            }
        })
    }

    private fun writeProofTelephonyProperties() {
        runCatching {
            val config = hookConfig
            if (!config.enabled) return
            val systemPropertiesClass = XposedHelpers.findClass("android.os.SystemProperties", null)
            listOf(
                "gsm.sim.operator.iso-country" to config.countryIsoPropertyValue,
                "gsm.sim.operator.numeric" to config.operatorNumericPropertyValue,
                "gsm.sim.operator.alpha" to config.alphaPropertyValue,
                "gsm.operator.iso-country" to config.countryIsoPropertyValue,
                "gsm.operator.numeric" to config.operatorNumericPropertyValue,
                "gsm.operator.alpha" to config.alphaPropertyValue
            ).forEach { (key, value) ->
                XposedHelpers.callStaticMethod(systemPropertiesClass, "set", key, value)
            }
        }.onFailure {
            XposedBridge.log("Arirang: failed to write proof telephony properties: ${it.message}")
        }
    }

    private fun systemPropertyOverride(key: Any?): String? {
        val propertyName = key?.toString() ?: return null
        val baseKey = propertyName.substringBeforeLast(".")
        if (propertyName !in SYSTEM_PROPERTY_KEYS && baseKey !in SYSTEM_PROPERTY_KEYS) return null

        val config = currentHookConfig(force = true)
        if (!config.enabled) return null

        val slotSuffix = propertyName.substringAfterLast(".", "")
        val slotIndex = slotSuffix.toIntOrNull()
        val profile = if (slotIndex != null) {
            config.profilesBySlot[slotIndex] ?: config.primaryProfile
        } else {
            config.primaryProfile
        }

        return when (baseKey) {
            "gsm.sim.operator.iso-country", "gsm.operator.iso-country" ->
                if (slotIndex == null) config.countryIsoPropertyValue else profile.countryIso
            "gsm.sim.operator.numeric", "gsm.operator.numeric" ->
                if (slotIndex == null) config.operatorNumericPropertyValue else profile.operatorNumeric
            "gsm.sim.operator.alpha", "gsm.operator.alpha" ->
                if (slotIndex == null) config.alphaPropertyValue else profile.alphaLong
            else -> null
        }
    }

    private fun rewriteSubscriptionResult(
        result: Any?,
        methodName: String,
        classLoader: ClassLoader?,
        param: XC_MethodHook.MethodHookParam
    ): Any? {
        return when (result) {
            is Iterable<*> -> buildSubscriptionInfoList(result.filterNotNull(), classLoader)
            is Array<*> -> buildSubscriptionArray(result, classLoader)
            else -> {
                val profile = profileForSubscriptionQuery(methodName, param.args)
                    ?: profileForSubscriptionInfo(result)
                if (result == null && profile == null) {
                    null
                } else {
                    copyOrRewriteSubscriptionInfo(result, classLoader, profile ?: hookConfig.primaryProfile)
                }
            }
        }
    }

    private fun buildSubscriptionArray(original: Array<*>, classLoader: ClassLoader?): Any {
        val rewritten = buildSubscriptionInfoList(original.filterNotNull(), classLoader)
        val componentType = original.javaClass.componentType ?: Any::class.java
        val array = ReflectArray.newInstance(componentType, rewritten.size)
        rewritten.forEachIndexed { index, item -> ReflectArray.set(array, index, item) }
        return array
    }

    private fun buildSubscriptionInfoList(original: List<Any>, classLoader: ClassLoader?): List<Any> {
        if (hookConfig.visibleProfiles.isEmpty()) return emptyList()

        val bySlot = original.associateBy { getIntFieldIfExists(it, "mSimSlotIndex") }
        val bySubId = original.associateBy { getIntFieldIfExists(it, "mId") }
        val template = original.firstOrNull()

        return hookConfig.visibleProfiles.mapNotNull { profile ->
            val source = bySlot[profile.slotIndex] ?: bySubId[profile.subId] ?: template
            copyOrRewriteSubscriptionInfo(source, classLoader, profile)
                ?: createSubscriptionInfo(classLoader, null, profile)
        }
    }

    private fun copyOrRewriteSubscriptionInfo(
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

    private fun createSubscriptionInfo(
        classLoader: ClassLoader?,
        template: Any?,
        profile: SimProfile
    ): Any? {
        return runCatching {
            val subscriptionInfoClass = template?.javaClass
                ?: XposedHelpers.findClassIfExists("android.telephony.SubscriptionInfo", classLoader)
                ?: return@runCatching null
            val builderClass = XposedHelpers.findClassIfExists(
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
            XposedBridge.log("Arirang: failed to create virtual SubscriptionInfo: ${it.message}")
        }.getOrNull()
    }

    private fun applySubscriptionBuilderProfile(builder: Any, profile: SimProfile) {
        builder.callOneArg("setId", profile.subId)
        builder.callOneArg("setIccId", profile.iccId)
        builder.callOneArg("setSimSlotIndex", profile.slotIndex)
        builder.callOneArg("setDisplayName", profile.alphaLong)
        builder.callOneArg("setCarrierName", profile.alphaLong)
        builder.callOneArg("setDisplayNameSource", profile.displayNameSource)
        builder.callOneArg("setIconTint", profile.iconTint)
        builder.callOneArg("setNumber", profile.phoneNumber)
        builder.callOneArg("setDataRoaming", profile.roaming)
        builder.callOneArg("setMcc", profile.mcc)
        builder.callOneArg("setMnc", profile.mnc)
        builder.callOneArg("setMcc", profile.mcc.toIntOrNull() ?: 0)
        builder.callOneArg("setMnc", profile.mnc.toIntOrNull() ?: 0)
        builder.callOneArg("setCountryIso", profile.countryIso)
        builder.callOneArg("setEmbedded", profile.isEmbedded)
        builder.callOneArg("setCardString", profile.cardString)
        builder.callOneArg("setCardId", profile.cardId)
        builder.callOneArg("setOpportunistic", profile.isOpportunistic)
        builder.callOneArg("setGroupDisabled", profile.isGroupDisabled)
        builder.callOneArg("setCarrierId", profile.carrierId)
        builder.callOneArg("setProfileClass", profile.profileClass)
        builder.callOneArg("setType", profile.subType)
        builder.callOneArg("setGroupOwner", profile.groupOwner)
        builder.callOneArg("setUiccApplicationsEnabled", profile.areUiccApplicationsEnabled)
        builder.callOneArg("setPortIndex", profile.portIndex)
        builder.callOneArg("setUsageSetting", profile.usageSetting)
    }

    private fun rewriteSubscriptionInfo(subscriptionInfo: Any?, profile: SimProfile) {
        if (subscriptionInfo == null) return

        setFieldIfExists(subscriptionInfo, "mId", profile.subId)
        setFieldIfExists(subscriptionInfo, "mIccId", profile.iccId)
        setFieldIfExists(subscriptionInfo, "mSimSlotIndex", profile.slotIndex)
        setFieldIfExists(subscriptionInfo, "mNumber", profile.phoneNumber)
        setFieldIfExists(subscriptionInfo, "mCountryIso", profile.countryIso)
        setFieldIfExists(subscriptionInfo, "mMcc", profile.mcc)
        setFieldIfExists(subscriptionInfo, "mMnc", profile.mnc)
        setFieldIfExists(subscriptionInfo, "mDisplayName", profile.alphaLong)
        setFieldIfExists(subscriptionInfo, "mCarrierName", profile.alphaLong)
        setFieldIfExists(subscriptionInfo, "mDisplayNameSource", profile.displayNameSource)
        setFieldIfExists(subscriptionInfo, "mIconTint", profile.iconTint)
        setFieldIfExists(subscriptionInfo, "mDataRoaming", profile.roaming)
        setFieldIfExists(subscriptionInfo, "mCardString", profile.cardString)
        setFieldIfExists(subscriptionInfo, "mCardId", profile.cardId)
        setFieldIfExists(subscriptionInfo, "mIsEmbedded", profile.isEmbedded)
        setFieldIfExists(subscriptionInfo, "mIsOpportunistic", profile.isOpportunistic)
        setFieldIfExists(subscriptionInfo, "mIsGroupDisabled", profile.isGroupDisabled)
        setFieldIfExists(subscriptionInfo, "mCarrierId", profile.carrierId)
        setFieldIfExists(subscriptionInfo, "mProfileClass", profile.profileClass)
        setFieldIfExists(subscriptionInfo, "mType", profile.subType)
        setFieldIfExists(subscriptionInfo, "mGroupOwner", profile.groupOwner)
        setFieldIfExists(subscriptionInfo, "mAreUiccApplicationsEnabled", profile.areUiccApplicationsEnabled)
        setFieldIfExists(subscriptionInfo, "mPortIndex", profile.portIndex)
        setFieldIfExists(subscriptionInfo, "mUsageSetting", profile.usageSetting)
    }

    private fun rewriteServiceState(serviceState: Any?, profile: SimProfile) {
        if (serviceState == null) return

        setFieldIfExists(serviceState, "mOperatorAlphaLong", profile.alphaLong)
        setFieldIfExists(serviceState, "mOperatorAlphaShort", profile.alphaShort)
        setFieldIfExists(serviceState, "mOperatorAlphaLongRaw", profile.alphaLong)
        setFieldIfExists(serviceState, "mOperatorAlphaShortRaw", profile.alphaShort)
        setFieldIfExists(serviceState, "mOperatorNumeric", profile.operatorNumeric)
        setFieldIfExists(serviceState, "mManualNetworkSelectionPlmn", profile.operatorNumeric)
        setFieldIfExists(serviceState, "mVoiceOperatorAlphaLong", profile.alphaLong)
        setFieldIfExists(serviceState, "mVoiceOperatorAlphaShort", profile.alphaShort)
        setFieldIfExists(serviceState, "mVoiceOperatorNumeric", profile.operatorNumeric)
        setFieldIfExists(serviceState, "mDataOperatorAlphaLong", profile.alphaLong)
        setFieldIfExists(serviceState, "mDataOperatorAlphaShort", profile.alphaShort)
        setFieldIfExists(serviceState, "mDataOperatorNumeric", profile.operatorNumeric)
        setFieldIfExists(serviceState, "mIsManualNetworkSelection", false)
        setFieldIfExists(serviceState, "mIsDataRoamingFromRegistration", false)
        setFieldIfExists(serviceState, "mIsEmergencyOnly", false)

        // Android 15+ might use a different internal structure or additional fields
        runCatching {
            val networkRegistrationInfos = getFieldIfExists(serviceState, "mNetworkRegistrationInfos") as? List<*>
            networkRegistrationInfos?.forEach { rewriteNestedTelephonyObject(it, profile) }
        }

        rewriteServiceStateNestedLists(serviceState, profile)

        runCatching {
            XposedHelpers.callMethod(
                serviceState,
                "setOperatorName",
                profile.alphaLong,
                profile.alphaShort,
                profile.operatorNumeric
            )
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
            val value = getFieldIfExists(serviceState, fieldName)
            rewriteNestedTelephonyObject(value, profile)
        }
    }

    private fun rewriteNestedTelephonyObject(value: Any?, profile: SimProfile) {
        when (value) {
            null -> return
            is Iterable<*> -> value.forEach { rewriteNestedTelephonyObject(it, profile) }
            is Array<*> -> value.forEach { rewriteNestedTelephonyObject(it, profile) }
            is Map<*, *> -> value.values.forEach { rewriteNestedTelephonyObject(it, profile) }
            else -> {
                val className = value.javaClass.name
                if (className.startsWith("android.telephony.ServiceState")) {
                    rewriteServiceState(value, profile)
                    return
                }

                if (
                    className.startsWith("android.telephony.NetworkRegistrationInfo") ||
                    className.startsWith("android.telephony.CellInfo") ||
                    className.startsWith("android.telephony.CellIdentity") ||
                    className.startsWith("android.telephony.OperatorInfo") ||
                    className.startsWith("android.telephony.emergency.EmergencyNumber")
                ) {
                    rewriteCommonOperatorFields(value, profile)
                    
                    // Specific handling for EmergencyNumber
                    if (className.contains("EmergencyNumber")) {
                        setFieldIfExists(value, "mAddress", if (profile.countryIso == "kp") "112" else "911")
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
            }
        }
    }

    private fun rewriteCommonOperatorFields(instance: Any, profile: SimProfile) {
        setFieldIfExists(instance, "mMcc", profile.mcc.toIntOrNull() ?: 0)
        setFieldIfExists(instance, "mMnc", profile.mnc.toIntOrNull() ?: 0)
        setFieldIfExists(instance, "mMcc", profile.mcc)
        setFieldIfExists(instance, "mMnc", profile.mnc)
        setFieldIfExists(instance, "mMccStr", profile.mcc)
        setFieldIfExists(instance, "mMncStr", profile.mnc)
        setFieldIfExists(instance, "mMccString", profile.mcc)
        setFieldIfExists(instance, "mMncString", profile.mnc)
        setFieldIfExists(instance, "mAlphaLong", profile.alphaLong)
        setFieldIfExists(instance, "mAlphaShort", profile.alphaShort)
        setFieldIfExists(instance, "mOperatorAlphaLong", profile.alphaLong)
        setFieldIfExists(instance, "mOperatorAlphaShort", profile.alphaShort)
        setFieldIfExists(instance, "mOperatorNumeric", profile.operatorNumeric)
        setFieldIfExists(instance, "mOperatorNumericLong", profile.operatorNumeric)
        setFieldIfExists(instance, "mPlmn", profile.operatorNumeric)
        setFieldIfExists(instance, "mRegisteredPlmn", profile.operatorNumeric)
        setFieldIfExists(instance, "mRplmn", profile.operatorNumeric)
        setFieldIfExists(instance, "rRplmn", profile.operatorNumeric)
        setFieldIfExists(instance, "mCountryIso", profile.countryIso)
        setFieldIfExists(instance, "countryIso", profile.countryIso)
        setFieldIfExists(instance, "mCountryCode", profile.countryIso)
    }

    private fun rewriteTelephonyDebugString(value: String, profile: SimProfile): String {
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

    private fun replaceDebugField(value: String, fieldName: String, replacement: String): String {
        val pattern = Regex("(${Regex.escape(fieldName)}\\s*=\\s*)([^,}\\]]*)")
        return pattern.replace(value) { match ->
            match.groupValues[1] + replacement
        }
    }

    private fun hookCellInfoSurfaces(classLoader: ClassLoader) {
        listOf(
            "android.telephony.CellInfo",
            "android.telephony.CellInfoGsm",
            "android.telephony.CellInfoWcdma",
            "android.telephony.CellInfoTdscdma",
            "android.telephony.CellInfoLte",
            "android.telephony.CellInfoNr"
        ).mapNotNull { XposedHelpers.findClassIfExists(it, classLoader) }
            .forEach { cellInfoClass ->
                cellInfoClass.declaredMethods
                    .filter { method -> !Modifier.isAbstract(method.modifiers) }
                    .filter { method ->
                        method.name == "getCellIdentity" ||
                            method.returnType.name.startsWith("android.telephony.CellIdentity")
                    }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = hookConfig
                                if (!config.enabled) return
                                rewriteNestedTelephonyObject(param.result, config.primaryProfile)
                            }
                        })
                    }

                hookTelephonyToString(cellInfoClass) { instance, profile ->
                    rewriteNestedTelephonyObject(instance, profile)
                }
            }
    }

    private fun hookCellIdentitySurfaces(classLoader: ClassLoader) {
        listOf(
            "android.telephony.CellIdentity",
            "android.telephony.CellIdentityGsm",
            "android.telephony.CellIdentityWcdma",
            "android.telephony.CellIdentityTdscdma",
            "android.telephony.CellIdentityLte",
            "android.telephony.CellIdentityNr"
        ).mapNotNull { XposedHelpers.findClassIfExists(it, classLoader) }
            .forEach { cellIdentityClass ->
                cellIdentityClass.declaredMethods
                    .filter { method -> !Modifier.isAbstract(method.modifiers) }
                    .filter { method ->
                        method.returnType == CharSequence::class.java ||
                            method.returnType == String::class.java ||
                            method.returnType == Int::class.javaPrimitiveType
                    }
                    .filter { method ->
                        method.name in setOf(
                            "getOperatorAlphaLong",
                            "getOperatorAlphaShort",
                            "getMcc",
                            "getMnc",
                            "getMccString",
                            "getMncString"
                        )
                    }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val config = hookConfig
                                if (!config.enabled) return
                                rewriteCommonOperatorFields(param.thisObject, config.primaryProfile)
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = hookConfig
                                if (!config.enabled) return
                                val profile = config.primaryProfile
                                param.result = when (method.name) {
                                    "getOperatorAlphaLong" -> profile.alphaLong
                                    "getOperatorAlphaShort" -> profile.alphaShort
                                    "getMcc" -> profile.mcc.toIntOrNull() ?: 0
                                    "getMnc" -> profile.mnc.toIntOrNull() ?: 0
                                    "getMccString" -> profile.mcc
                                    "getMncString" -> profile.mnc
                                    else -> param.result
                                }
                            }
                        })
                    }

                cellIdentityClass.declaredMethods
                    .filter { it.name == "toString" && it.parameterTypes.isEmpty() }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val config = hookConfig
                                if (!config.enabled) return
                                rewriteCommonOperatorFields(param.thisObject, config.primaryProfile)
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = hookConfig
                                if (!config.enabled || param.result !is String) return
                                param.result = rewriteTelephonyDebugString(
                                    param.result as String,
                                    config.primaryProfile
                                )
                            }
                        })
                    }
            }
    }

    private fun hookSubscriptionCounts(targetClass: Class<*>, externalClientsOnly: Boolean = false) {
        listOf(
            "getActiveSubscriptionInfoCount",
            "getActiveSubscriptionInfoCountMax",
            "getActiveSubInfoCount",
            "getActiveSubInfoCountMax"
        ).forEach { methodName ->
            hookProfileMethod(targetClass, methodName, externalClientsOnly) { _, _ ->
                hookConfig.visibleProfiles.size
            }
        }
    }

    private fun hookSubscriptionIdReaders(targetClass: Class<*>, externalClientsOnly: Boolean = false) {
        listOf(
            "getActiveSubscriptionIdList",
            "getCompleteActiveSubscriptionIdList",
            "getActiveSubIdList"
        ).forEach { methodName ->
            if (targetClass.declaredMethods.none { it.name == methodName }) return@forEach

            XposedBridge.hookAllMethods(targetClass, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = hookConfig
                    if (!config.enabled || !shouldRewriteForCaller(externalClientsOnly)) return
                    param.result = config.visibleProfiles.map { it.subId }.toIntArray()
                }
            })
        }
    }

    private fun hookSubscriptionInfoResult(
        targetClass: Class<*>,
        methodName: String,
        externalClientsOnly: Boolean = false
    ) {
        val methods = targetClass.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return

        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!hookConfig.enabled || !shouldRewriteForCaller(externalClientsOnly)) return
                    param.result = rewriteSubscriptionResult(
                        result = param.result,
                        methodName = method.name,
                        classLoader = targetClass.classLoader,
                        param = param
                    )
                }
            })
        }
    }

    private fun hookProfileMethod(
        targetClass: Class<*>,
        methodName: String,
        externalClientsOnly: Boolean = false,
        beforeOriginal: Boolean = false,
        shouldHandle: (HookConfig) -> Boolean = { it.enabled },
        valueProvider: (XC_MethodHook.MethodHookParam, Method) -> Any?
    ) {
        val methods = targetClass.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return

        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!beforeOriginal) return
                    val config = hookConfig
                    if (!shouldHandle(config) || !shouldRewriteForCaller(externalClientsOnly)) return
                    param.result = coerceHookResult(valueProvider(param, method), null)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (beforeOriginal) return
                    val config = hookConfig
                    if (!shouldHandle(config) || !shouldRewriteForCaller(externalClientsOnly)) return
                    param.result = coerceHookResult(valueProvider(param, method), param.result)
                }
            })
        }
    }

    private fun coerceHookResult(value: Any?, originalResult: Any?): Any? {
        return when (originalResult) {
            null -> value
            is Int -> value.toString().toIntOrNull() ?: originalResult
            is Boolean -> value.toString().toBooleanStrictOrNull() ?: originalResult
            is CharSequence -> value?.toString()
            else -> value
        }
    }

    private fun shouldRewriteForCaller(externalClientsOnly: Boolean): Boolean {
        if (!externalClientsOnly) return true

        val callingUid = Binder.getCallingUid()
        return callingUid >= Process.FIRST_APPLICATION_UID && callingUid != Process.myUid()
    }

    private fun hookAllExistingStringMethods(
        classLoader: ClassLoader,
        className: String,
        methodNames: Collection<String>,
        externalClientsOnly: Boolean = false,
        beforeOriginal: Boolean = false,
        shouldHandle: (HookConfig) -> Boolean = { it.enabled },
        resultProvider: (XC_MethodHook.MethodHookParam, Method) -> String?
    ) {
        val targetClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return
        hookAllExistingStringMethods(targetClass, methodNames, externalClientsOnly, beforeOriginal, shouldHandle, resultProvider)
    }

    private fun hookAllExistingStringMethods(
        targetClass: Class<*>,
        methodNames: Collection<String>,
        externalClientsOnly: Boolean = false,
        beforeOriginal: Boolean = false,
        shouldHandle: (HookConfig) -> Boolean = { it.enabled },
        resultProvider: (XC_MethodHook.MethodHookParam, Method) -> String?
    ) {
        methodNames.forEach { methodName ->
            targetClass.declaredMethods
                .filter { it.name == methodName }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!beforeOriginal) return
                            val config = hookConfig
                            if (!shouldHandle(config) || !shouldRewriteForCaller(externalClientsOnly)) return
                            param.result = resultProvider(param, method)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (beforeOriginal) return
                            val config = hookConfig
                            if (!shouldHandle(config) || !shouldRewriteForCaller(externalClientsOnly)) return
                            if (param.result == null || param.result is String) {
                                param.result = resultProvider(param, method)
                            }
                        }
                    })
                }
        }
    }

    private fun phoneNumberForCall(param: XC_MethodHook.MethodHookParam, method: Method): String? {
        val firstInt = param.args.firstIntOrNull()
        val profile = when {
            method.name.contains("ForSubscriber", ignoreCase = true) ->
                profileForSubId(firstInt, allowFallback = firstInt == null)
            method.name.contains("ForDisplay", ignoreCase = true) ->
                profileForSubId(firstInt, allowFallback = firstInt == null)
            method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType ->
                profileForSubId(firstInt, allowFallback = false)
            else -> profileForTelephonyManager(param)
        }
        return profile?.phoneNumber
    }

    private fun imeiForCall(param: XC_MethodHook.MethodHookParam, method: Method): String? {
        val firstInt = param.args.firstIntOrNull()
        val profile = when {
            method.name.contains("ForSubscriber", ignoreCase = true) ->
                profileForSubId(firstInt, allowFallback = firstInt == null)
            method.name.contains("ForPhone", ignoreCase = true) ->
                profileForSlot(firstInt, allowFallback = false)
            method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType ->
                profileForSlot(firstInt, allowFallback = false)
            else -> profileForTelephonyManager(param)
        }
        val slotIndex = when {
            method.name.contains("ForSubscriber", ignoreCase = true) -> profile?.slotIndex
            method.name.contains("ForPhone", ignoreCase = true) -> firstInt
            method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType -> firstInt
            else -> profile?.slotIndex
        }
        return hookConfig.uniqueIdentifiers.imeiForSlot(slotIndex, profile?.imei)
    }

    private fun typeAllocationCodeForCall(param: XC_MethodHook.MethodHookParam, method: Method): String? {
        val firstInt = param.args.firstIntOrNull()
        val profile = when {
            method.name.contains("ForSubscriber", ignoreCase = true) ->
                profileForSubId(firstInt, allowFallback = firstInt == null)
            method.name.contains("ForPhone", ignoreCase = true) ->
                profileForSlot(firstInt, allowFallback = false)
            method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType ->
                profileForSlot(firstInt, allowFallback = false)
            else -> profileForTelephonyManager(param)
        }
        val slotIndex = when {
            method.name.contains("ForSubscriber", ignoreCase = true) -> profile?.slotIndex
            method.name.contains("ForPhone", ignoreCase = true) -> firstInt
            method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType -> firstInt
            else -> profile?.slotIndex
        }
        return hookConfig.uniqueIdentifiers.typeAllocationCodeForSlot(slotIndex, profile?.typeAllocationCode)
    }

    private fun profileForTelephonyManager(param: XC_MethodHook.MethodHookParam): SimProfile? {
        val subId = getIntFieldIfExists(param.thisObject, "mSubId")
        if (subId != null && subId > 0) {
            return profileForSubId(subId, allowFallback = false)
        }
        val phoneId = getIntFieldIfExists(param.thisObject, "mPhoneId")
        if (phoneId != null && phoneId >= 0) {
            return profileForSlot(phoneId, allowFallback = false)
        }
        return hookConfig.visibleProfiles.firstOrNull()
    }

    private fun profileForSlot(slotIndex: Int?, allowFallback: Boolean = true): SimProfile? {
        if (hookConfig.visibleProfilesBySlot.isEmpty()) return null
        if (slotIndex != null) {
            hookConfig.visibleProfilesBySlot[slotIndex]?.let { return it }
            if (!allowFallback) return null
        }
        return hookConfig.visibleProfiles.firstOrNull()
    }

    private fun profileForSubId(subId: Int?, allowFallback: Boolean = true): SimProfile? {
        if (hookConfig.visibleProfiles.isEmpty()) return null
        if (subId != null) {
            hookConfig.visibleProfiles.firstOrNull { it.subId == subId }?.let { return it }
            if (!allowFallback) return null
        }
        return hookConfig.visibleProfiles.firstOrNull()
    }

    private fun profileForSubscriptionInfo(subscriptionInfo: Any?): SimProfile? {
        if (subscriptionInfo == null || hookConfig.visibleProfiles.isEmpty()) return null
        
        val slotIndex = getIntFieldIfExists(subscriptionInfo, "mSimSlotIndex")
        if (slotIndex != null && slotIndex >= 0) {
            hookConfig.visibleProfilesBySlot[slotIndex]?.let { return it }
        }
        
        val subId = getIntFieldIfExists(subscriptionInfo, "mId")
        if (subId != null && subId > 0) {
            hookConfig.visibleProfiles.firstOrNull { it.subId == subId }?.let { return it }
        }
        
        val iccId = getFieldIfExists(subscriptionInfo, "mIccId")?.toString()
        if (!iccId.isNullOrBlank()) {
            hookConfig.visibleProfiles.firstOrNull { it.iccId == iccId }?.let { return it }
        }

        return hookConfig.primaryProfile
    }

    private fun profileForSubscriptionQuery(methodName: String, args: Array<Any?>): SimProfile? {
        val firstInt = args.firstIntOrNull()
        return when {
            methodName.contains("SimSlotIndex", ignoreCase = true) ->
                profileForSlot(firstInt, allowFallback = false)
            methodName.contains("Icc", ignoreCase = true) -> {
                val iccId = args.firstOrNull { it is String } as? String
                hookConfig.visibleProfiles.firstOrNull { it.iccId == iccId }
            }
            methodName.contains("SubscriptionInfo", ignoreCase = true) ||
                methodName == "getSubscriptionInfo" -> profileForSubId(firstInt, allowFallback = false)
            else -> null
        }
    }

    private fun getFieldIfExists(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        return runCatching {
            val field = findField(instance.javaClass, fieldName) ?: return@runCatching null
            field.isAccessible = true
            field.get(instance)
        }.getOrNull()
    }

    private fun getIntFieldIfExists(instance: Any?, fieldName: String): Int? {
        return getFieldIfExists(instance, fieldName).asIntOrNull()
    }

    private fun setFieldIfExists(instance: Any, fieldName: String, value: Any) {
        runCatching {
            val field = findField(instance.javaClass, fieldName) ?: return@runCatching
            
            val normalizedValue = when (field.type) {
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toString().toIntOrNull() ?: 0
                Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> value.toString().toBooleanStrictOrNull() ?: false
                CharSequence::class.java, String::class.java -> value.toString()
                else -> value
            }
            
            XposedHelpers.setObjectField(instance, fieldName, normalizedValue)
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                return current.getDeclaredField(fieldName)
            }
            current = current.superclass
        }
        return null
    }

    private fun Any.callOneArg(methodName: String, value: Any?) {
        val method = javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isCompatibleWith(value)
        } ?: return
        runCatching { method.invoke(this, value) }
    }

    private fun Class<*>.isCompatibleWith(value: Any?): Boolean {
        if (value == null) return !isPrimitive
        val boxedType = when (this) {
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> this
        }
        return boxedType.isAssignableFrom(value.javaClass) ||
            (boxedType == CharSequence::class.java && value is String)
    }

    private fun Array<Any?>.firstIntOrNull(): Int? {
        return firstOrNull { it is Int } as? Int
    }

    private fun Any?.asIntOrNull(): Int? {
        return when (this) {
            is Int -> this
            is Number -> toInt()
            is String -> toIntOrNull()
            else -> null
        }
    }

    private fun currentHookConfig(force: Boolean = false): HookConfig {
        val now = SystemClock.uptimeMillis()
        cachedHookConfig?.let { cached ->
            if (!force && now - lastHookConfigRefreshAt < CONFIG_REFRESH_INTERVAL_MS) {
                return cached
            }
        }

        return synchronized(this) {
            val checkedAt = SystemClock.uptimeMillis()
            cachedHookConfig?.let { cached ->
                if (!force && checkedAt - lastHookConfigRefreshAt < CONFIG_REFRESH_INTERVAL_MS) {
                    return@synchronized cached
                }
            }

            val previous = cachedHookConfig
            val updated = loadHookConfig(force)
            if (previous != updated) {
                logHookConfig(updated)
            }
            cachedHookConfig = updated
            lastHookConfigRefreshAt = checkedAt
            updated
        }
    }

    private fun loadHookConfig(force: Boolean = false): HookConfig {
        val values = readConfigValues(force)
        val enabled = values?.get(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false
        val hideSim = values?.get(KEY_HIDE_SIM)?.toBooleanStrictOrNull() ?: false
        val profilesBySlot = values?.get(KEY_SIM_INFO_MAP)
            ?.let(::parseProfileMap)
            ?.takeIf { it.isNotEmpty() }
            ?: values?.get(KEY_SIM_INFO_LIST)
                ?.let(::parseProfileList)
                ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PROFILES_BY_SLOT
        val normalized = normalizeProfiles(profilesBySlot)
        val uniqueIdentifiers = readUniqueIdentifierConfig(force)
        return HookConfig(
            enabled = enabled,
            hideSim = hideSim,
            profilesBySlot = normalized,
            uniqueIdentifiers = uniqueIdentifiers
        )
    }

    private fun readConfigValues(force: Boolean): Map<String, String>? {
        if (!preferHookNotifyConfig) return readSharedPrefsValues()

        val hookNotifyValues = readHookNotifyValues(force)
        val sharedPrefsValues = if (force) readSharedPrefsValues() else null

        return freshestConfigValues(hookNotifyValues, sharedPrefsValues)
            ?: hookNotifyValues
            ?: readSharedPrefsValues()
    }

    private fun freshestConfigValues(
        first: Map<String, String>?,
        second: Map<String, String>?
    ): Map<String, String>? {
        if (first == null) return second
        if (second == null) return first

        val firstVersion = first[KEY_LAST_MODIFIED]?.toLongOrNull() ?: Long.MIN_VALUE
        val secondVersion = second[KEY_LAST_MODIFIED]?.toLongOrNull() ?: Long.MIN_VALUE
        return if (secondVersion > firstVersion) second else first
    }

    private fun logHookConfig(config: HookConfig) {
        XposedBridge.log(
            "Arirang: SIM proof config loaded enabled=${config.enabled} hideSim=${config.hideSim} " +
                "uniqueIds=${config.uniqueIdentifiers.enabled} slots=${config.visibleProfiles.joinToString { "${it.slotIndex}:${it.countryIso}/${it.operatorNumeric}/${it.alphaLong}" }}"
        )
    }

    private fun readHookNotifyValues(force: Boolean = false): Map<String, String>? {
        if (!preferHookNotifyConfig) return null
        return HookNotifyClient.readSimConfigSnapshot(force = force)
            ?.let(::parseConfigSnapshot)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseConfigSnapshot(json: String): Map<String, String> {
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!root.isNull(key)) {
                        put(key, root.optString(key))
                    }
                }
            }
        }.onFailure {
            XposedBridge.log("Arirang: failed to parse SIM config snapshot: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun readSharedPrefsValues(): Map<String, String>? {
        return readPrefsValues(PREFS_NAME)
    }

    private fun readUniqueIdentifierConfig(force: Boolean = false): UniqueIdentifierConfig {
        val hookNotifyValues = HookNotifyClient.readUniqueIdentifierConfigSnapshot(force = force)
            ?.let(::parseConfigSnapshot)
            ?.takeIf { it.isNotEmpty() }
        val sharedPrefsValues = if (hookNotifyValues == null || force) {
            readPrefsValues(UNIQUE_PREFS_NAME)
        } else {
            null
        }
        val values = freshestConfigValues(hookNotifyValues, sharedPrefsValues)
            ?: hookNotifyValues
            ?: sharedPrefsValues
            ?: return UniqueIdentifierConfig()
        val enabled = values[KEY_UNIQUE_ENABLED]?.toBooleanStrictOrNull() ?: false
        val imeiBySlot = values[KEY_IMEI_BY_SLOT]
            ?.let(::parseImeiMap)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PROFILES_BY_SLOT.mapValues { it.value.imei }
        val tacBySlot = values[KEY_TAC_BY_SLOT]
            ?.let(::parseImeiMap)
            ?.takeIf { it.isNotEmpty() }
            ?: imeiBySlot.mapValues { it.value.take(8) }
        return UniqueIdentifierConfig(enabled = enabled, imeiBySlot = imeiBySlot, tacBySlot = tacBySlot)
    }

    private fun parseImeiMap(json: String): Map<Int, String> {
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val slot = key.toIntOrNull() ?: continue
                    val imei = root.optString(key).takeIf { it.isNotBlank() } ?: continue
                    put(slot, imei)
                }
            }.toSortedMap()
        }.onFailure {
            XposedBridge.log("Arirang: failed to parse IMEI map: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun readPrefsValues(prefsName: String): Map<String, String>? {
        val candidates = listOf(
            File("/data/user/0/${BuildConfig.APPLICATION_ID}/shared_prefs/$prefsName.xml"),
            File("/data/data/${BuildConfig.APPLICATION_ID}/shared_prefs/$prefsName.xml")
        )
        val prefsFile = candidates.firstOrNull { it.isFile && it.canRead() } ?: return null
        return runCatching {
            val values = linkedMapOf<String, String>()
            FileInputStream(prefsFile).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, Charsets.UTF_8.name())

                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        val key = parser.getAttributeValue(null, "name")
                        if (!key.isNullOrBlank()) {
                            when (tagName) {
                                "boolean", "int", "long", "float" -> {
                                    parser.getAttributeValue(null, "value")?.let { values[key] = it }
                                }
                                "string" -> values[key] = parser.nextText()
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            values
        }.onFailure {
            XposedBridge.log("Arirang: failed to read $prefsName config: ${it.message}")
        }.getOrNull()
    }

    private fun parseProfileMap(json: String): Map<Int, SimProfile> {
        return runCatching {
            val root = JSONObject(json)
            val result = linkedMapOf<Int, SimProfile>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val slot = key.toIntOrNull()
                val item = root.optJSONObject(key) ?: continue
                val slotIndex = slot ?: item.optIntOrNull("simSlotIndex") ?: result.size
                result[slotIndex] = profileFromJson(item, slotIndex)
            }
            result
        }.onFailure {
            XposedBridge.log("Arirang: failed to parse SIM profile map: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun parseProfileList(json: String): Map<Int, SimProfile> {
        return runCatching {
            val root = JSONArray(json)
            buildMap {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val slotIndex = item.optIntOrNull("simSlotIndex") ?: index
                    put(slotIndex, profileFromJson(item, slotIndex))
                }
            }
        }.onFailure {
            XposedBridge.log("Arirang: failed to parse legacy SIM profile list: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun profileFromJson(item: JSONObject, slotIndex: Int): SimProfile {
        val fallback = DEFAULT_PROFILES_BY_SLOT[slotIndex]
            ?: DEFAULT_PROFILES_BY_SLOT.values.first().copy(
                slotIndex = slotIndex,
                subId = slotIndex + 1,
                cardId = slotIndex,
                portIndex = slotIndex
            )
        val mcc = item.optCleanString("mcc") ?: fallback.mcc
        val mnc = item.optCleanString("mnc") ?: fallback.mnc
        val carrierName = item.optCleanString("carrierName")
            ?: item.optCleanString("displayName")
            ?: fallback.alphaLong
        val iccId = item.optCleanString("iccId") ?: fallback.iccId

        return fallback.copy(
            slotIndex = slotIndex,
            subId = item.optIntOrNull("id")?.takeIf { it > 0 } ?: fallback.subId,
            iccId = iccId,
            countryIso = item.optCleanString("countryIso") ?: fallback.countryIso,
            mcc = mcc,
            mnc = mnc,
            alphaLong = carrierName,
            alphaShort = carrierName,
            phoneNumber = item.optCleanString("number") ?: fallback.phoneNumber,
            imei = item.optCleanString("imei") ?: fallback.imei,
            carrierId = item.optIntOrNull("carrierId") ?: fallback.carrierId,
            cardId = item.optIntOrNull("cardId") ?: fallback.cardId,
            cardString = item.optCleanString("cardString") ?: iccId,
            displayNameSource = item.optIntOrNull("nameSource") ?: fallback.displayNameSource,
            iconTint = item.optIntOrNull("iconTint") ?: fallback.iconTint,
            roaming = item.optIntOrNull("roaming") ?: fallback.roaming,
            isEmbedded = item.optBooleanOrNull("isEmbedded") ?: fallback.isEmbedded,
            isOpportunistic = item.optBooleanOrNull("isOpportunistic") ?: fallback.isOpportunistic,
            isGroupDisabled = item.optBooleanOrNull("isGroupDisabled") ?: fallback.isGroupDisabled,
            profileClass = item.optIntOrNull("profileClass") ?: fallback.profileClass,
            subType = item.optIntOrNull("subType") ?: fallback.subType,
            groupOwner = item.optCleanString("groupOwner") ?: fallback.groupOwner,
            areUiccApplicationsEnabled = item.optBooleanOrNull("areUiccApplicationsEnabled")
                ?: fallback.areUiccApplicationsEnabled,
            portIndex = item.optIntOrNull("portIndex") ?: fallback.portIndex,
            usageSetting = item.optIntOrNull("usageSetting") ?: fallback.usageSetting
        )
    }

    private fun normalizeProfiles(profilesBySlot: Map<Int, SimProfile>): Map<Int, SimProfile> {
        return profilesBySlot.toSortedMap().mapValues { (slot, profile) ->
            profile.copy(
                slotIndex = slot,
                subId = profile.subId.takeIf { it > 0 } ?: slot + 1,
                cardId = profile.cardId.takeIf { it >= 0 } ?: slot,
                portIndex = profile.portIndex.takeIf { it >= 0 } ?: slot
            )
        }
    }

    private fun JSONObject.optCleanString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrNull()
            ?: optString(name).toIntOrNull()
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getBoolean(name) }.getOrNull()
            ?: optString(name).toBooleanStrictOrNull()
    }
}
