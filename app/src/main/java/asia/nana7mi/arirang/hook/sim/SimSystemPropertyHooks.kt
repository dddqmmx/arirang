package asia.nana7mi.arirang.hook.sim

import asia.nana7mi.arirang.hook.core.HookBridge

import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.asIntOrNull
import asia.nana7mi.arirang.hook.util.firstIntOrNull

import de.robv.android.xposed.XC_MethodHook

internal class SimSystemPropertyHooks(
    private val configStore: SimHookConfigStore,
    private val currentConfig: () -> SimHookConfig,
    private val profileForTelephonyManager: (XC_MethodHook.MethodHookParam) -> SimProfile?,
    private val profileForSlot: (slotIndex: Int?, allowFallback: Boolean) -> SimProfile?
) {
    fun hookTelephonyPropertyWriters(classLoader: ClassLoader) {
        val telephonyManagerClass = HookBridge.findClassIfExists(
            "android.telephony.TelephonyManager",
            classLoader
        ) ?: return

        HookBridge.hookAllMethods(telephonyManagerClass, "setTelephonyProperty", beforeHookedMethod {
            val config = currentConfig()
            if (!config.enabled) return@beforeHookedMethod
            val profile = profileForSlot(args.getOrNull(0).asIntOrNull(), false)
                ?: return@beforeHookedMethod
            val propertyName = args.getOrNull(1)?.toString() ?: return@beforeHookedMethod
            if (args.size <= 2) return@beforeHookedMethod
            args[2] = propertyValueFor(propertyName, profile) ?: return@beforeHookedMethod
        })

        hookSetterValue(telephonyManagerClass, "setSimCountryIso") { param ->
            profileForTelephonyManager(param)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setSimCountryIsoForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), false)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorNumeric") { param ->
            profileForTelephonyManager(param)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorNumericForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), false)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorName") { param ->
            profileForTelephonyManager(param)?.alphaLong
        }
        hookSetterValue(telephonyManagerClass, "setSimOperatorNameForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), false)?.alphaLong
        }
        hookSetterValue(telephonyManagerClass, "setNetworkCountryIso") { param ->
            profileForTelephonyManager(param)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setNetworkCountryIsoForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), false)?.countryIso
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNumeric") { param ->
            profileForTelephonyManager(param)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNumericForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), false)?.operatorNumeric
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorName") { param ->
            profileForTelephonyManager(param)?.alphaLong
        }
        hookSetterValue(telephonyManagerClass, "setNetworkOperatorNameForPhone") { param ->
            profileForSlot(param.args.firstIntOrNull(), false)?.alphaLong
        }
    }

    fun hookSystemPropertyWriters(classLoader: ClassLoader) {
        val systemPropertiesClass = systemPropertiesClass(classLoader) ?: return

        systemPropertiesClass.declaredMethods
            .filter {
                it.name == "set" &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            .forEach { method ->
                HookBridge.hookMethod(method, beforeHookedMethod {
                    val override = systemPropertyOverride(args.firstOrNull()) ?: return@beforeHookedMethod
                    args[1] = override
                })
            }
    }

    fun hookGeneratedTelephonyProperties(classLoader: ClassLoader) {
        val telephonyPropertiesClass = HookBridge.findClassIfExists(
            "android.sysprop.TelephonyProperties",
            classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.SIM, "android.sysprop.TelephonyProperties not found")
            return
        }

        mapOf<String, (SimHookConfig) -> List<String>>(
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
            HookBridge.hookAllMethods(telephonyPropertiesClass, methodName, hookedMethod(
                before = {
                    if (args.size == 1 && args[0] is List<*>) {
                        val config = currentConfig()
                        if (config.enabled) {
                            args[0] = value(config)
                        }
                    }
                },
                after = {
                    if (args.isEmpty() && result is List<*>) {
                        val config = currentConfig()
                        if (config.enabled) {
                            result = value(config)
                        }
                    }
                }
            ))
        }
    }

    fun hookSystemPropertyReaders(classLoader: ClassLoader) {
        val systemPropertiesClass = systemPropertiesClass(classLoader) ?: return

        systemPropertiesClass.declaredMethods
            .filter {
                it.name == "get" &&
                    it.returnType == String::class.java &&
                    it.parameterTypes.firstOrNull() == String::class.java
            }
            .forEach { method ->
                HookBridge.hookMethod(method, beforeHookedMethod {
                    val override = systemPropertyOverride(args.firstOrNull()) ?: return@beforeHookedMethod
                    result = override
                })
            }
    }

    fun writeProofTelephonyProperties() {
        runCatching {
            val config = currentConfig()
            if (!config.enabled) return
            val systemPropertiesClass = HookBridge.findClass("android.os.SystemProperties", null)
            listOf(
                "gsm.sim.operator.iso-country" to config.countryIsoPropertyValue,
                "gsm.sim.operator.numeric" to config.operatorNumericPropertyValue,
                "gsm.sim.operator.alpha" to config.alphaPropertyValue,
                "gsm.operator.iso-country" to config.countryIsoPropertyValue,
                "gsm.operator.numeric" to config.operatorNumericPropertyValue,
                "gsm.operator.alpha" to config.alphaPropertyValue
            ).forEach { (key, value) ->
                HookBridge.callStaticMethod(systemPropertiesClass, "set", key, value)
            }
        }.onFailure {
            HookLog.w(HookLog.Module.SIM, "failed to write proof telephony properties: ${it.message}")
        }
    }

    private fun hookSetterValue(
        telephonyManagerClass: Class<*>,
        methodName: String,
        valueProvider: (XC_MethodHook.MethodHookParam) -> String?
    ) {
        if (telephonyManagerClass.declaredMethods.none { it.name == methodName }) return

        HookBridge.hookAllMethods(telephonyManagerClass, methodName, beforeHookedMethod {
            if (!currentConfig().enabled) return@beforeHookedMethod
            val value = valueProvider(this) ?: return@beforeHookedMethod
            val valueIndex = args.indexOfLast { it is String }
            if (valueIndex >= 0) args[valueIndex] = value
        })
    }

    private fun systemPropertyOverride(key: Any?): String? {
        val propertyName = key?.toString() ?: return null
        val directKey = propertyName.takeIf { it in SYSTEM_PROPERTY_KEYS }
        val suffixedKey = propertyName
            .substringBeforeLast(".", missingDelimiterValue = "")
            .takeIf { it in SYSTEM_PROPERTY_KEYS }
        val propertyKey = directKey ?: suffixedKey ?: return null

        val config = configStore.current(force = true)
        if (!config.enabled) return null

        val slotIndex = if (directKey == null) {
            propertyName.substringAfterLast(".", "").toIntOrNull()
        } else {
            null
        }
        val profile = if (slotIndex != null) {
            config.profilesBySlot[slotIndex] ?: config.primaryProfile
        } else {
            config.primaryProfile
        }

        return when (propertyKey) {
            "gsm.sim.operator.iso-country", "gsm.operator.iso-country" ->
                if (slotIndex == null) config.countryIsoPropertyValue else profile.countryIso
            "gsm.sim.operator.numeric", "gsm.operator.numeric" ->
                if (slotIndex == null) config.operatorNumericPropertyValue else profile.operatorNumeric
            "gsm.sim.operator.alpha", "gsm.operator.alpha" ->
                if (slotIndex == null) config.alphaPropertyValue else profile.alphaLong
            else -> null
        }
    }

    private fun propertyValueFor(propertyName: String, profile: SimProfile): String? {
        return when {
            propertyName.endsWith("icc_operator_iso_country") -> profile.countryIso
            propertyName.endsWith("sim.operator.iso-country") -> profile.countryIso
            propertyName.endsWith("operator.iso-country") -> profile.countryIso
            propertyName.endsWith("icc_operator_numeric") -> profile.operatorNumeric
            propertyName.endsWith("sim.operator.numeric") -> profile.operatorNumeric
            propertyName.endsWith("operator.numeric") -> profile.operatorNumeric
            propertyName.endsWith("icc_operator_alpha") -> profile.alphaLong
            propertyName.endsWith("sim.operator.alpha") -> profile.alphaLong
            propertyName.endsWith("operator.alpha") -> profile.alphaLong
            else -> null
        }
    }

    private fun systemPropertiesClass(classLoader: ClassLoader): Class<*>? {
        return HookBridge.findClassIfExists("android.os.SystemProperties", null)
            ?: HookBridge.findClassIfExists("android.os.SystemProperties", classLoader)
    }

    private fun beforeHookedMethod(
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    private fun hookedMethod(
        before: (XC_MethodHook.MethodHookParam.() -> Unit)? = null,
        after: (XC_MethodHook.MethodHookParam.() -> Unit)? = null
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                before?.invoke(param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                after?.invoke(param)
            }
        }
    }

    private companion object {
        private val SYSTEM_PROPERTY_KEYS = setOf(
            "gsm.sim.operator.iso-country",
            "gsm.sim.operator.numeric",
            "gsm.sim.operator.alpha",
            "gsm.operator.iso-country",
            "gsm.operator.numeric",
            "gsm.operator.alpha"
        )
    }
}
