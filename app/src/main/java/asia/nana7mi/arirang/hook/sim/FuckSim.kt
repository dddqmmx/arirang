package asia.nana7mi.arirang.hook.sim

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.asIntOrNull
import asia.nana7mi.arirang.hook.util.firstIntOrNull
import asia.nana7mi.arirang.hook.util.getFieldValue

import android.os.Binder
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
    private val configStore = SimHookConfigStore()

    private val hookConfig: SimHookConfig
        get() = configStore.current()

    private val propertyHooks = SimSystemPropertyHooks(
        configStore = configStore,
        currentConfig = { hookConfig },
        profileForTelephonyManager = ::profileForTelephonyManager,
        profileForSlot = ::profileForSlot
    )

    @Volatile
    private var phoneConfigBindHookInstalled = false

    override fun isEnabled(): Boolean = hookConfig.enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            configStore.preferHookNotifyConfig = lpparam.packageName == "android" || lpparam.packageName == "com.android.phone"
            val config = hookConfig
            when (lpparam.packageName) {
                "android" -> {
                    hookSystemServerSurfaces(lpparam.classLoader)
                    HookLog.i(HookLog.Module.SIM, "system_server hook installed enabled=${config.enabled}")
                }
                "com.android.phone" -> {
                    hookPhoneProcessSurfaces(lpparam.classLoader)
                    HookLog.i(HookLog.Module.SIM, "phone process hook installed enabled=${config.enabled}")
                }
            }
        }.onFailure {
            HookLog.e(HookLog.Module.SIM, "SIM country proof hook failed", it)
        }
    }

    private fun hookSystemServerSurfaces(classLoader: ClassLoader) {
        hookTelephonyManagerUniqueIdentifierReaders(classLoader)
        hookServiceStateSurfaces(classLoader)
        hookSubscriptionServiceSurfaces(classLoader, externalClientsOnly = false)
        propertyHooks.hookSystemPropertyReaders(classLoader)
        propertyHooks.hookGeneratedTelephonyProperties(classLoader)
        propertyHooks.writeProofTelephonyProperties()
    }

    private fun hookPhoneProcessSurfaces(classLoader: ClassLoader) {
        hookPhoneConfigServiceBind(classLoader)
        hookServiceStateSurfaces(classLoader)
        hookSubscriptionServiceSurfaces(classLoader, externalClientsOnly = false)
        hookPhoneInterfaceManager(classLoader)
        hookPhoneSubInfoController(classLoader)
        hookTelephonyManagerUniqueIdentifierReaders(classLoader)
        propertyHooks.hookTelephonyPropertyWriters(classLoader)
        propertyHooks.hookSystemPropertyWriters(classLoader)
        propertyHooks.hookSystemPropertyReaders(classLoader)
        propertyHooks.hookGeneratedTelephonyProperties(classLoader)
    }

    private fun hookPhoneConfigServiceBind(classLoader: ClassLoader) {
        if (phoneConfigBindHookInstalled) return
        phoneConfigBindHookInstalled = true

        val applicationClass = BaseHookModule.findClassIfExists("android.app.Application", classLoader) ?: return
        BaseHookModule.hookAllMethods(applicationClass, "onCreate", afterHookedMethod {
            val app = thisObject as? android.app.Application ?: return@afterHookedMethod
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ArirangClient.autoBindCurrentUser(app)
                configStore.current(force = true)
                propertyHooks.writeProofTelephonyProperties()
            }, 5_000L)
        })
    }

    private fun hookServiceStateSurfaces(classLoader: ClassLoader) {
        val serviceStateClass = BaseHookModule.findClassIfExists(
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
            val targetClass = BaseHookModule.findClassIfExists(className, classLoader) ?: return@forEach

            targetClass.declaredMethods
                .filter { it.returnType == serviceStateClass }
                .forEach { method ->
                    BaseHookModule.hookMethod(method, afterHookedMethod {
                        val config = hookConfig
                        if (!config.enabled) return@afterHookedMethod
                        rewriteServiceState(result, config.primaryProfile)
                    })
                }

            targetClass.declaredMethods
                .filter { method -> method.parameterTypes.any { it == serviceStateClass } }
                .forEach { method ->
                    BaseHookModule.hookMethod(method, hookedMethod(
                        before = {
                            val config = hookConfig
                            if (config.enabled) {
                                args.forEach { rewriteServiceState(it, config.primaryProfile) }
                            }
                        },
                        after = {
                            val config = hookConfig
                            if (config.enabled) {
                                rewriteServiceState(result, config.primaryProfile)
                            }
                        }
                    ))
                }
        }
    }

    private fun hookPhoneInterfaceManager(classLoader: ClassLoader) {
        val phoneInterfaceManagerClass = BaseHookModule.findClassIfExists(
            "com.android.phone.PhoneInterfaceManager",
            classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.SIM, "PhoneInterfaceManager not found for SIM country proof")
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
                if (methodName.contains("Imei", ignoreCase = true) || methodName.contains("DeviceId", ignoreCase = true)) {
                    val slot = param.args.firstIntOrNull() ?: profile?.slotIndex
                    hookConfig.uniqueIdentifiers.imeiForSlot(slot, profile?.imei)
                } else {
                    valueProvider(profile ?: return@hookProfileMethod null)
                }
            }
        }

        hookAllExistingStringMethods(
            targetClass = phoneInterfaceManagerClass,
            methodNames = listOf(
                "getTypeAllocationCode",
                "getTypeAllocationCodeForSlot",
                "getTypeAllocationCodeForPhone",
                "getTypeAllocationCodeForSubscriber"
            ),
            beforeOriginal = true,
            shouldHandle = { it.enabled || it.uniqueIdentifiers.enabled }
        ) { param, method ->
            typeAllocationCodeForCall(param, method)
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
                    BaseHookModule.hookMethod(method, afterHookedMethod {
                        val config = hookConfig
                        if (!config.enabled) return@afterHookedMethod
                        rewriteNestedTelephonyObject(result, config.primaryProfile)
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

        hookAllExistingStringMethods(
            classLoader = classLoader,
            className = "com.android.internal.telephony.PhoneSubInfoController",
            methodNames = listOf(
                "getTypeAllocationCode",
                "getTypeAllocationCodeForSlot",
                "getTypeAllocationCodeForPhone",
                "getTypeAllocationCodeForSubscriber"
            ),
            beforeOriginal = true,
            shouldHandle = { it.enabled || it.uniqueIdentifiers.enabled }
        ) { param, method ->
            typeAllocationCodeForCall(param, method)
        }
    }

    private fun hookTelephonyManagerUniqueIdentifierReaders(classLoader: ClassLoader) {
        val telephonyManagerClass = BaseHookModule.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        hookAllExistingStringMethods(
            targetClass = telephonyManagerClass,
            methodNames = listOf(
                "getTypeAllocationCode",
                "getTypeAllocationCodeForSlot",
                "getTypeAllocationCodeForPhone",
                "getTypeAllocationCodeForSubscriber"
            ),
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
        val telephonyManagerClass = BaseHookModule.findClassIfExists(
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
                    BaseHookModule.hookMethod(method, afterHookedMethod {
                        val config = hookConfig
                        if (!config.enabled) return@afterHookedMethod
                        rewriteNestedTelephonyObject(result, config.primaryProfile)
                    })
                }
        }
    }

    private fun hookOperatorInfoSurfaces(classLoader: ClassLoader) {
        val operatorInfoClass = BaseHookModule.findClassIfExists(
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
        val networkRegistrationInfoClass = BaseHookModule.findClassIfExists(
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
                BaseHookModule.hookMethod(method, afterHookedMethod {
                    val config = hookConfig
                    if (!config.enabled) return@afterHookedMethod
                    rewriteNestedTelephonyObject(result, config.primaryProfile)
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
                BaseHookModule.hookMethod(method, hookedMethod(
                    before = {
                        val config = hookConfig
                        if (config.enabled) {
                            rewriteNestedTelephonyObject(thisObject, config.primaryProfile)
                        }
                    },
                    after = {
                        val config = hookConfig
                        if (config.enabled) {
                            result = coerceHookResult(valueProvider(config.primaryProfile), result)
                        }
                    }
                ))
            }
    }

    private fun hookTelephonyToString(
        targetClass: Class<*>,
        rewriter: (Any, SimProfile) -> Unit
    ) {
        targetClass.declaredMethods
            .filter { it.name == "toString" && it.parameterTypes.isEmpty() && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                BaseHookModule.hookMethod(method, hookedMethod(
                    before = {
                        val config = hookConfig
                        if (config.enabled) {
                            rewriter(thisObject, config.primaryProfile)
                        }
                    },
                    after = {
                        val config = hookConfig
                        if (config.enabled && result is String) {
                            result = rewriteTelephonyDebugString(result as String, config.primaryProfile)
                        }
                    }
                ))
            }
    }

    private fun hookSubscriptionServiceSurfaces(classLoader: ClassLoader, externalClientsOnly: Boolean) {
        val serviceClasses = listOf(
            "com.android.server.telephony.subscription.SubscriptionManagerService",
            "com.android.server.telephony.SubscriptionController",
            "com.android.internal.telephony.subscription.SubscriptionManagerService",
            "com.android.internal.telephony.SubscriptionController"
        ).mapNotNull { BaseHookModule.findClassIfExists(it, classLoader) }

        if (serviceClasses.isEmpty()) {
            HookLog.w(HookLog.Module.SIM, "Subscription service not found for SIM proof")
            return
        }

        serviceClasses.forEach { serviceClass ->
            hookSubscriptionInfoReaders(serviceClass, externalClientsOnly)
            hookSubscriptionPhoneNumberReaders(serviceClass, externalClientsOnly)
            hookSubscriptionCounts(serviceClass, externalClientsOnly)
            hookSubscriptionIdReaders(serviceClass, externalClientsOnly)
            HookLog.i(HookLog.Module.SIM, "hooked subscription service ${serviceClass.name} externalOnly=$externalClientsOnly")
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

    private fun hookCellInfoSurfaces(classLoader: ClassLoader) {
        listOf(
            "android.telephony.CellInfo",
            "android.telephony.CellInfoGsm",
            "android.telephony.CellInfoWcdma",
            "android.telephony.CellInfoTdscdma",
            "android.telephony.CellInfoLte",
            "android.telephony.CellInfoNr"
        ).mapNotNull { BaseHookModule.findClassIfExists(it, classLoader) }
            .forEach { cellInfoClass ->
                cellInfoClass.declaredMethods
                    .filter { method -> !Modifier.isAbstract(method.modifiers) }
                    .filter { method ->
                        method.name == "getCellIdentity" ||
                            method.returnType.name.startsWith("android.telephony.CellIdentity")
                    }
                    .forEach { method ->
                        BaseHookModule.hookMethod(method, afterHookedMethod {
                            val config = hookConfig
                            if (!config.enabled) return@afterHookedMethod
                            rewriteNestedTelephonyObject(result, config.primaryProfile)
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
        ).mapNotNull { BaseHookModule.findClassIfExists(it, classLoader) }
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
                        BaseHookModule.hookMethod(method, hookedMethod(
                            before = {
                                val config = hookConfig
                                if (config.enabled) {
                                    rewriteCommonOperatorFields(thisObject, config.primaryProfile)
                                }
                            },
                            after = {
                                val config = hookConfig
                                if (config.enabled) {
                                    val profile = config.primaryProfile
                                    result = when (method.name) {
                                        "getOperatorAlphaLong" -> profile.alphaLong
                                        "getOperatorAlphaShort" -> profile.alphaShort
                                        "getMcc" -> profile.mcc.toIntOrNull() ?: 0
                                        "getMnc" -> profile.mnc.toIntOrNull() ?: 0
                                        "getMccString" -> profile.mcc
                                        "getMncString" -> profile.mnc
                                        else -> result
                                    }
                                }
                            }
                        ))
                    }

                cellIdentityClass.declaredMethods
                    .filter { it.name == "toString" && it.parameterTypes.isEmpty() }
                    .forEach { method ->
                        BaseHookModule.hookMethod(method, hookedMethod(
                            before = {
                                val config = hookConfig
                                if (config.enabled) {
                                    rewriteCommonOperatorFields(thisObject, config.primaryProfile)
                                }
                            },
                            after = {
                                val config = hookConfig
                                if (config.enabled && result is String) {
                                    result = rewriteTelephonyDebugString(
                                        result as String,
                                        config.primaryProfile
                                    )
                                }
                            }
                        ))
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

            BaseHookModule.hookAllMethods(targetClass, methodName, hookedMethod(
                before = {
                    val config = hookConfig
                    if (config.enabled && shouldRewriteForCaller(externalClientsOnly) && shouldShortCircuitInternalSubscriptionIdRead()) {
                        result = config.visibleProfiles.map { it.subId }.toIntArray()
                    }
                },
                after = {
                    val config = hookConfig
                    if (config.enabled && shouldRewriteForCaller(externalClientsOnly)) {
                        result = config.visibleProfiles.map { it.subId }.toIntArray()
                    }
                }
            ))
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
            BaseHookModule.hookMethod(method, afterHookedMethod {
                if (!hookConfig.enabled || !shouldRewriteForCaller(externalClientsOnly)) return@afterHookedMethod
                result = rewriteSubscriptionResult(
                    result = result,
                    methodName = method.name,
                    classLoader = targetClass.classLoader,
                    param = this
                )
            })
        }
    }

    private fun hookProfileMethod(
        targetClass: Class<*>,
        methodName: String,
        externalClientsOnly: Boolean = false,
        beforeOriginal: Boolean = false,
        shouldHandle: (SimHookConfig) -> Boolean = { it.enabled },
        valueProvider: (XC_MethodHook.MethodHookParam, Method) -> Any?
    ) {
        val methods = targetClass.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return

        methods.forEach { method ->
            BaseHookModule.hookMethod(method, hookedMethod(
                before = {
                    if (beforeOriginal) {
                        val config = hookConfig
                        if (shouldHandle(config) && shouldRewriteForCaller(externalClientsOnly)) {
                            result = coerceHookResult(valueProvider(this, method), null)
                        }
                    }
                },
                after = {
                    if (!beforeOriginal) {
                        val config = hookConfig
                        if (shouldHandle(config) && shouldRewriteForCaller(externalClientsOnly)) {
                            result = coerceHookResult(valueProvider(this, method), result)
                        }
                    }
                }
            ))
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

    private fun shouldShortCircuitInternalSubscriptionIdRead(): Boolean {
        val callingUid = Binder.getCallingUid()
        return callingUid == Process.SYSTEM_UID ||
            callingUid == Process.PHONE_UID ||
            callingUid == Process.myUid()
    }

    private fun hookAllExistingStringMethods(
        classLoader: ClassLoader,
        className: String,
        methodNames: Collection<String>,
        externalClientsOnly: Boolean = false,
        beforeOriginal: Boolean = false,
        shouldHandle: (SimHookConfig) -> Boolean = { it.enabled },
        resultProvider: (XC_MethodHook.MethodHookParam, Method) -> String?
    ) {
        val targetClass = BaseHookModule.findClassIfExists(className, classLoader) ?: return
        hookAllExistingStringMethods(targetClass, methodNames, externalClientsOnly, beforeOriginal, shouldHandle, resultProvider)
    }

    private fun hookAllExistingStringMethods(
        targetClass: Class<*>,
        methodNames: Collection<String>,
        externalClientsOnly: Boolean = false,
        beforeOriginal: Boolean = false,
        shouldHandle: (SimHookConfig) -> Boolean = { it.enabled },
        resultProvider: (XC_MethodHook.MethodHookParam, Method) -> String?
    ) {
        methodNames.forEach { methodName ->
            val methods = targetClass.declaredMethods
                .filter { it.name == methodName }
            if (methods.isEmpty() && methodName.contains("TypeAllocationCode", ignoreCase = true)) {
                HookLog.d(
                    HookLog.Module.UNIQUE_ID,
                    "method not found for TAC hook: ${targetClass.name}.$methodName"
                )
            }
            methods.forEach { method ->
                if (methodName.contains("TypeAllocationCode", ignoreCase = true)) {
                    HookLog.i(
                        HookLog.Module.UNIQUE_ID,
                        "install TAC hook ${targetClass.name}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }}) beforeOriginal=$beforeOriginal"
                    )
                }
                BaseHookModule.hookMethod(method, hookedMethod(
                    before = {
                        if (beforeOriginal) {
                            val config = hookConfig
                            if (shouldHandle(config) && shouldRewriteForCaller(externalClientsOnly)) {
                                result = resultProvider(this, method)
                            }
                        }
                    },
                    after = {
                        if (!beforeOriginal) {
                            val config = hookConfig
                            if (shouldHandle(config) && shouldRewriteForCaller(externalClientsOnly) && (result == null || result is String)) {
                                result = resultProvider(this, method)
                            }
                        }
                    }
                ))
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
        val config = hookConfig
        val result = config.uniqueIdentifiers.typeAllocationCodeForSlot(slotIndex, profile?.typeAllocationCode)
        HookLog.i(
            HookLog.Module.UNIQUE_ID,
            "TAC call ${method.declaringClass.simpleName}.${method.name} firstInt=$firstInt slot=$slotIndex " +
                "uniqueEnabled=${config.uniqueIdentifiers.enabled} profileSlot=${profile?.slotIndex} fallback=${profile?.typeAllocationCode} result=$result"
        )
        return result
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

        val iccId = getFieldValue(subscriptionInfo, "mIccId")?.toString()
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

    private fun getIntFieldIfExists(instance: Any?, fieldName: String): Int? {
        return getFieldValue(instance, fieldName).asIntOrNull()
    }
}
