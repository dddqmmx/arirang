package asia.nana7mi.arirang.hook.gms

import asia.nana7mi.arirang.hook.core.HookBridge

import android.os.Parcel
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class GmsAdvertisingIdHooks(
    private val currentConfig: () -> GmsIdentifierConfig
) {
    private val hookedServiceClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    fun hookService(ownerClass: Class<*>) {
        val transactClass = ownerClass.findOnTransactClass() ?: return
        if (!hookedServiceClasses.add(transactClass)) return

        logClassHierarchyMethods("Advertising ID service", ownerClass)
        hookStringGetters(ownerClass)
        HookBridge.hookAllMethods(transactClass, "onTransact", beforeHookedMethod {
            spoofAdvertisingIdIfMatched(this)
        })
        HookLog.i(HookLog.Module.GMS, "hooked GMS Advertising ID service ${ownerClass.name}/${transactClass.name}")
    }

    private fun hookStringGetters(ownerClass: Class<*>) {
        val methods = ownerClass.declaredMethods.filter { method ->
            method.name in ADS_IDENTIFIER_GETTER_NAMES &&
                method.returnType == String::class.java &&
                method.parameterTypes.isEmpty()
        }

        if (methods.isEmpty()) {
            HookLog.i(HookLog.Module.GMS, "Advertising ID String getter not found on ${ownerClass.name}")
            return
        }

        methods.forEach { method ->
            method.isAccessible = true
            HookBridge.hookMethod(method, afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val gaid = currentConfig().gaid.takeIf { it.isNotBlank() } ?: return@afterHookedMethod
                result = gaid
                HookLog.i(HookLog.Module.GMS, "GAID spoofed from ${ownerClass.name}.${method.name}()")
            })
            HookLog.i(HookLog.Module.GMS, "hooked GMS Advertising ID getter ${ownerClass.name}.${method.name}()")
        }
    }

    private fun spoofAdvertisingIdIfMatched(param: XC_MethodHook.MethodHookParam) {
        val code = param.args.getOrNull(0) as? Int ?: return
        if (code != ADS_IDENTIFIER_GET_ID_TRANSACTION) return

        val data = param.args.getOrNull(1) as? Parcel ?: return
        if (data.interfaceTokenOrNull() != ADS_IDENTIFIER_DESCRIPTOR) return

        val gaid = currentConfig().gaid.takeIf { it.isNotBlank() } ?: return
        val reply = param.args.getOrNull(2) as? Parcel ?: return
        reply.replaceWithStringResult(gaid)
        param.result = true
        HookLog.i(HookLog.Module.GMS, "GAID spoofed from GMS binder")
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

    private fun afterHookedMethod(
        block: XC_MethodHook.MethodHookParam.() -> Unit
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.block()
            }
        }
    }

    companion object {
        const val ADS_IDENTIFIER_DESCRIPTOR =
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"

        private const val ADS_IDENTIFIER_GET_ID_TRANSACTION = 1
        private val ADS_IDENTIFIER_GETTER_NAMES = setOf("getId", "getAdvertisingId")
    }
}
