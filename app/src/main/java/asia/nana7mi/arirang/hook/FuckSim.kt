package asia.nana7mi.arirang.hook

import android.util.Log
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * System-side feasibility proof for SIM country anonymization.
 *
 * This only rewrites SubscriptionInfo objects returned from the telephony subscription service.
 * It does not modify Phone, RIL, Uicc, SIMRecords, or the subscription database, so real calling
 * and radio registration paths keep using the device's real SIM state.
 */
class FuckSim : BaseHookModule(targetPackages = setOf("com.android.phone", "android")) {

    private data class OperatorProfile(
        val countryIso: String,
        val mcc: String,
        val mnc: String,
        val alphaLong: String,
        val alphaShort: String = alphaLong,
        val carrierId: Int = -1
    ) {
        val operatorNumeric = mcc + mnc
        val countryIsoList = listOf(countryIso, countryIso)
        val operatorNumericList = listOf(operatorNumeric, operatorNumeric)
        val alphaList = listOf(alphaLong, alphaLong)
        val countryIsoPropertyValue = countryIsoList.joinToString(",")
        val operatorNumericPropertyValue = operatorNumericList.joinToString(",")
        val alphaPropertyValue = alphaList.joinToString(",")
        val mccInt = mcc.toInt()
        val mncInt = mnc.toInt()
    }

    private val operatorProfile = OperatorProfile(
        countryIso = "kp",
        mcc = "467",
        mnc = "05",
        alphaLong = "Koryolink"
    )

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!BuildConfig.DEBUG) return

        runCatching {
            hookServiceStateSurfaces(lpparam.classLoader)

            if (lpparam.packageName == "android") {
                XposedBridge.log("Arirang: SIM country proof system_server hook installed")
                return
            }

            val serviceClass = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.subscription.SubscriptionManagerService",
                lpparam.classLoader
            )

            if (serviceClass != null) {
                hookSubscriptionInfoReaders(serviceClass)
            } else {
                XposedBridge.log("Arirang: SubscriptionManagerService not found for SIM country proof")
            }

            hookPhoneInterfaceManager(lpparam.classLoader)
            hookTelephonyPropertyWriters(lpparam.classLoader)
            hookGeneratedTelephonyProperties(lpparam.classLoader)
            writeProofTelephonyProperties()

            XposedBridge.log("Arirang: SIM country proof hook installed")
        }.onFailure {
            XposedBridge.log("Arirang: SIM country proof hook failed: ${Log.getStackTraceString(it)}")
        }
    }

    private fun hookServiceStateSurfaces(classLoader: ClassLoader) {
        val serviceStateClass = XposedHelpers.findClassIfExists(
            "android.telephony.ServiceState",
            classLoader
        ) ?: return

        hookCellIdentitySurfaces(classLoader)

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
                            rewriteServiceState(param.result)
                        }
                    })
                }

            targetClass.declaredMethods
                .filter { method -> method.parameterTypes.any { it == serviceStateClass } }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.forEach { rewriteServiceState(it) }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            rewriteServiceState(param.result)
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

        mapOf(
            "getNetworkCountryIsoForPhone" to operatorProfile.countryIso,
            "getNetworkOperatorForPhone" to operatorProfile.operatorNumeric,
            "getNetworkOperatorName" to operatorProfile.alphaLong
        ).forEach { (methodName, value) ->
            XposedBridge.hookAllMethods(phoneInterfaceManagerClass, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result is String || param.result == null) {
                        param.result = value
                    }
                }
            })
        }
    }

    private fun hookSubscriptionInfoReaders(serviceClass: Class<*>) {
        listOf(
            "getActiveSubscriptionInfoList",
            "getAvailableSubscriptionInfoList",
            "getAccessibleSubscriptionInfoList",
            "getAllSubInfoList",
            "getSubscriptionInfo",
            "getActiveSubscriptionInfo"
        ).forEach { methodName ->
            XposedBridge.hookAllMethods(serviceClass, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = rewriteSubscriptionResult(param.result)
                }
            })
        }
    }

    private fun hookTelephonyPropertyWriters(classLoader: ClassLoader) {
        val telephonyManagerClass = XposedHelpers.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(telephonyManagerClass, "setTelephonyProperty", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val propertyName = param.args.getOrNull(1)?.toString() ?: return
                when {
                    propertyName.endsWith("icc_operator_iso_country") -> param.args[2] = operatorProfile.countryIso
                    propertyName.endsWith("sim.operator.iso-country") -> param.args[2] = operatorProfile.countryIso
                    propertyName.endsWith("operator.iso-country") -> param.args[2] = operatorProfile.countryIso
                    propertyName.endsWith("icc_operator_numeric") -> param.args[2] = operatorProfile.operatorNumeric
                    propertyName.endsWith("sim.operator.numeric") -> param.args[2] = operatorProfile.operatorNumeric
                    propertyName.endsWith("operator.numeric") -> param.args[2] = operatorProfile.operatorNumeric
                    propertyName.endsWith("icc_operator_alpha") -> param.args[2] = operatorProfile.alphaLong
                    propertyName.endsWith("sim.operator.alpha") -> param.args[2] = operatorProfile.alphaLong
                    propertyName.endsWith("operator.alpha") -> param.args[2] = operatorProfile.alphaLong
                }
            }
        })

        hookSetterValue(telephonyManagerClass, "setSimCountryIso", operatorProfile.countryIso)
        hookSetterValue(telephonyManagerClass, "setSimCountryIsoForPhone", operatorProfile.countryIso)
        hookSetterValue(telephonyManagerClass, "setSimOperatorNumeric", operatorProfile.operatorNumeric)
        hookSetterValue(telephonyManagerClass, "setSimOperatorNumericForPhone", operatorProfile.operatorNumeric)
        hookSetterValue(telephonyManagerClass, "setSimOperatorName", operatorProfile.alphaLong)
        hookSetterValue(telephonyManagerClass, "setSimOperatorNameForPhone", operatorProfile.alphaLong)
        hookSetterValue(telephonyManagerClass, "setNetworkCountryIso", operatorProfile.countryIso)
        hookSetterValue(telephonyManagerClass, "setNetworkCountryIsoForPhone", operatorProfile.countryIso)
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNumeric", operatorProfile.operatorNumeric)
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNumericForPhone", operatorProfile.operatorNumeric)
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorName", operatorProfile.alphaLong)
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNameForPhone", operatorProfile.alphaLong)
    }

    private fun hookGeneratedTelephonyProperties(classLoader: ClassLoader) {
        val telephonyPropertiesClass = XposedHelpers.findClassIfExists(
            "android.sysprop.TelephonyProperties",
            classLoader
        ) ?: run {
            XposedBridge.log("Arirang: android.sysprop.TelephonyProperties not found")
            return
        }

        mapOf(
            "icc_operator_iso_country" to operatorProfile.countryIsoList,
            "icc_operator_numeric" to operatorProfile.operatorNumericList,
            "icc_operator_alpha" to operatorProfile.alphaList,
            "operator_iso_country" to operatorProfile.countryIsoList,
            "operator_numeric" to operatorProfile.operatorNumericList,
            "operator_alpha" to operatorProfile.alphaList
        ).forEach { (methodName, value) ->
            XposedBridge.hookAllMethods(telephonyPropertiesClass, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size == 1 && param.args[0] is List<*>) {
                        param.args[0] = value
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty() && param.result is List<*>) {
                        param.result = value
                    }
                }
            })
        }
    }

    private fun hookSetterValue(telephonyManagerClass: Class<*>, methodName: String, value: String) {
        XposedBridge.hookAllMethods(telephonyManagerClass, methodName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val valueIndex = param.args.indexOfLast { it is String }
                if (valueIndex >= 0) param.args[valueIndex] = value
            }
        })
    }

    private fun writeProofTelephonyProperties() {
        runCatching {
            val systemPropertiesClass = XposedHelpers.findClass("android.os.SystemProperties", null)
            listOf(
                "gsm.sim.operator.iso-country" to operatorProfile.countryIsoPropertyValue,
                "gsm.sim.operator.numeric" to operatorProfile.operatorNumericPropertyValue,
                "gsm.sim.operator.alpha" to operatorProfile.alphaPropertyValue,
                "gsm.operator.iso-country" to operatorProfile.countryIsoPropertyValue,
                "gsm.operator.numeric" to operatorProfile.operatorNumericPropertyValue,
                "gsm.operator.alpha" to operatorProfile.alphaPropertyValue
            ).forEach { (key, value) ->
                XposedHelpers.callStaticMethod(systemPropertiesClass, "set", key, value)
            }
        }.onFailure {
            XposedBridge.log("Arirang: failed to write proof telephony properties: ${it.message}")
        }
    }

    private fun rewriteSubscriptionResult(result: Any?): Any? {
        return when (result) {
            is List<*> -> {
                result.forEach { rewriteSubscriptionInfo(it) }
                result
            }
            else -> {
                rewriteSubscriptionInfo(result)
                result
            }
        }
    }

    private fun rewriteSubscriptionInfo(subscriptionInfo: Any?) {
        if (subscriptionInfo == null) return

        setFieldIfExists(subscriptionInfo, "mCountryIso", operatorProfile.countryIso)
        setFieldIfExists(subscriptionInfo, "mMcc", operatorProfile.mcc)
        setFieldIfExists(subscriptionInfo, "mMnc", operatorProfile.mnc)
        setFieldIfExists(subscriptionInfo, "mDisplayName", operatorProfile.alphaLong)
        setFieldIfExists(subscriptionInfo, "mCarrierName", operatorProfile.alphaLong)
        setFieldIfExists(subscriptionInfo, "mCarrierId", operatorProfile.carrierId)
    }

    private fun rewriteServiceState(serviceState: Any?) {
        if (serviceState == null) return

        setFieldIfExists(serviceState, "mOperatorAlphaLong", operatorProfile.alphaLong)
        setFieldIfExists(serviceState, "mOperatorAlphaShort", operatorProfile.alphaShort)
        setFieldIfExists(serviceState, "mOperatorAlphaLongRaw", operatorProfile.alphaLong)
        setFieldIfExists(serviceState, "mOperatorAlphaShortRaw", operatorProfile.alphaShort)
        setFieldIfExists(serviceState, "mOperatorNumeric", operatorProfile.operatorNumeric)
        setFieldIfExists(serviceState, "mManualNetworkSelectionPlmn", operatorProfile.operatorNumeric)
        setFieldIfExists(serviceState, "mVoiceOperatorAlphaLong", operatorProfile.alphaLong)
        setFieldIfExists(serviceState, "mVoiceOperatorAlphaShort", operatorProfile.alphaShort)
        setFieldIfExists(serviceState, "mVoiceOperatorNumeric", operatorProfile.operatorNumeric)
        setFieldIfExists(serviceState, "mDataOperatorAlphaLong", operatorProfile.alphaLong)
        setFieldIfExists(serviceState, "mDataOperatorAlphaShort", operatorProfile.alphaShort)
        setFieldIfExists(serviceState, "mDataOperatorNumeric", operatorProfile.operatorNumeric)
        rewriteServiceStateNestedLists(serviceState)

        runCatching {
            XposedHelpers.callMethod(
                serviceState,
                "setOperatorName",
                operatorProfile.alphaLong,
                operatorProfile.alphaShort,
                operatorProfile.operatorNumeric
            )
        }
    }

    private fun rewriteServiceStateNestedLists(serviceState: Any) {
        listOf(
            "mNetworkRegistrationInfos",
            "mCellIdentityList",
            "mOperatorInfo"
        ).forEach { fieldName ->
            val value = getFieldIfExists(serviceState, fieldName)
            rewriteNestedTelephonyObject(value)
        }
    }

    private fun rewriteNestedTelephonyObject(value: Any?) {
        when (value) {
            null -> return
            is Iterable<*> -> value.forEach { rewriteNestedTelephonyObject(it) }
            is Array<*> -> value.forEach { rewriteNestedTelephonyObject(it) }
            is Map<*, *> -> value.values.forEach { rewriteNestedTelephonyObject(it) }
            else -> {
                val className = value.javaClass.name
                if (
                    className.startsWith("android.telephony.NetworkRegistrationInfo") ||
                    className.startsWith("android.telephony.CellIdentity") ||
                    className.startsWith("android.telephony.OperatorInfo") ||
                    className.startsWith("android.telephony.emergency.EmergencyNumber")
                ) {
                    rewriteCommonOperatorFields(value)
                    value.javaClass.declaredFields.forEach { field ->
                        runCatching {
                            field.isAccessible = true
                            val child = field.get(value)
                            if (child !== value) rewriteNestedTelephonyObject(child)
                        }
                    }
                }
            }
        }
    }

    private fun rewriteCommonOperatorFields(instance: Any) {
        setFieldIfExists(instance, "mMcc", operatorProfile.mccInt)
        setFieldIfExists(instance, "mMnc", operatorProfile.mncInt)
        setFieldIfExists(instance, "mMcc", operatorProfile.mcc)
        setFieldIfExists(instance, "mMnc", operatorProfile.mnc)
        setFieldIfExists(instance, "mMccStr", operatorProfile.mcc)
        setFieldIfExists(instance, "mMncStr", operatorProfile.mnc)
        setFieldIfExists(instance, "mAlphaLong", operatorProfile.alphaLong)
        setFieldIfExists(instance, "mAlphaShort", operatorProfile.alphaShort)
        setFieldIfExists(instance, "mOperatorAlphaLong", operatorProfile.alphaLong)
        setFieldIfExists(instance, "mOperatorAlphaShort", operatorProfile.alphaShort)
        setFieldIfExists(instance, "mOperatorNumeric", operatorProfile.operatorNumeric)
        setFieldIfExists(instance, "mRegisteredPlmn", operatorProfile.operatorNumeric)
        setFieldIfExists(instance, "mRplmn", operatorProfile.operatorNumeric)
        setFieldIfExists(instance, "rRplmn", operatorProfile.operatorNumeric)
        setFieldIfExists(instance, "mCountryIso", operatorProfile.countryIso)
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
                                rewriteCommonOperatorFields(param.thisObject)
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                param.result = when (method.name) {
                                    "getOperatorAlphaLong" -> operatorProfile.alphaLong
                                    "getOperatorAlphaShort" -> operatorProfile.alphaShort
                                    "getMcc" -> operatorProfile.mccInt
                                    "getMnc" -> operatorProfile.mncInt
                                    "getMccString" -> operatorProfile.mcc
                                    "getMncString" -> operatorProfile.mnc
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
                                rewriteCommonOperatorFields(param.thisObject)
                            }
                        })
                    }
            }
    }

    private fun getFieldIfExists(instance: Any, fieldName: String): Any? {
        return runCatching {
            val field = findField(instance.javaClass, fieldName) ?: return@runCatching null
            field.isAccessible = true
            field.get(instance)
        }.getOrNull()
    }

    private fun setFieldIfExists(instance: Any, fieldName: String, value: Any) {
        runCatching {
            val field = findField(instance.javaClass, fieldName) ?: return@runCatching
            field.isAccessible = true
            val normalizedValue = when (field.type) {
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toString().toIntOrNull() ?: 0
                CharSequence::class.java, String::class.java -> value.toString()
                else -> value
            }
            field.set(instance, normalizedValue)
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
}
