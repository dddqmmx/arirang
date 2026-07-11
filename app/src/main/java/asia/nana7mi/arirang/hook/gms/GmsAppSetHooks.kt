package asia.nana7mi.arirang.hook.gms

import asia.nana7mi.arirang.hook.core.HookBridge

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import asia.nana7mi.arirang.hook.core.HookLog
import com.google.android.gms.appset.zzc as AppSetIdResult
import com.google.android.gms.common.api.Status
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class GmsAppSetHooks(
    private val currentConfig: () -> GmsIdentifierConfig
) {
    private val hookedServiceClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    fun hookCallbackBinderProxy(classLoader: ClassLoader) {
        val binderProxyClass = HookBridge.findClassIfExists("android.os.BinderProxy", classLoader)
            ?: HookBridge.findClassIfExists("android.os.BinderProxy", ClassLoader.getSystemClassLoader())
            ?: return

        HookBridge.findAndHookMethod(
            binderProxyClass,
            "transact",
            Int::class.javaPrimitiveType,
            Parcel::class.java,
            Parcel::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val code = param.args.getOrNull(0) as? Int ?: return
                    if (code != APP_SET_CALLBACK_TRANSACTION) return

                    val data = param.args.getOrNull(1) as? Parcel ?: return
                    if (data.interfaceTokenOrNull() != APP_SET_CALLBACK_DESCRIPTOR) return

                    val appSetId = currentConfig().appSetId.takeIf { it.isNotBlank() } ?: return
                    HookLog.i(HookLog.Module.GMS, "App Set ID callback matched in GMS")
                    val replacement = Parcel.obtain()
                    try {
                        val status = data.readParcelableAfterInterfaceToken(Status.CREATOR)
                            ?: Status.RESULT_SUCCESS
                        replacement.writeInterfaceToken(APP_SET_CALLBACK_DESCRIPTOR)
                        replacement.writeParcelableCompat(status, 0)
                        replacement.writeParcelableCompat(AppSetIdResult(appSetId, 1), 0)
                        param.args[1] = replacement
                        param.setObjectExtra(REPLACEMENT_PARCEL_EXTRA, replacement)
                        HookLog.i(HookLog.Module.GMS, "App Set ID spoofed from GMS callback")
                    } catch (t: Throwable) {
                        if (param.args.getOrNull(1) === replacement) {
                            param.args[1] = data
                        }
                        if (param.getObjectExtra(REPLACEMENT_PARCEL_EXTRA) === replacement) {
                            param.setObjectExtra(REPLACEMENT_PARCEL_EXTRA, null)
                        }
                        replacement.recycle()
                        HookLog.i(HookLog.Module.GMS, "failed to rewrite App Set ID callback: ${t.message}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val replacement = param.getObjectExtra(REPLACEMENT_PARCEL_EXTRA) as? Parcel ?: return
                    param.setObjectExtra(REPLACEMENT_PARCEL_EXTRA, null)
                    runCatching { replacement.recycle() }
                        .onFailure {
                            HookLog.i(
                                HookLog.Module.GMS,
                                "failed to recycle App Set callback parcel: ${it.message}"
                            )
                        }
                }
            }
        )
    }

    fun hookService(ownerClass: Class<*>) {
        val transactClass = ownerClass.findOnTransactClass()
        logClassHierarchyMethods("App Set service", ownerClass)
        hookRequestMethod(ownerClass)
        HookLog.i(HookLog.Module.GMS, "found GMS App Set service ${ownerClass.name}/${transactClass?.name}")
    }

    private fun hookRequestMethod(ownerClass: Class<*>) {
        if (!hookedServiceClasses.add(ownerClass)) return

        val method = ownerClass.declaredMethods.firstOrNull { it.isAppSetRequestMethod() }

        if (method == null) {
            HookLog.i(HookLog.Module.GMS, "App Set request method not found on ${ownerClass.name}")
            return
        }

        method.isAccessible = true
        HookBridge.hookMethod(method, beforeHookedMethod {
            val request = args.getOrNull(0)
            val callback = args.getOrNull(1)
            val appSetClassLoader = ownerClass.classLoader ?: ClassLoader.getSystemClassLoader()
            if (spoofRequest(appSetClassLoader, callback)) {
                result = null
                return@beforeHookedMethod
            }
            HookLog.i(
                HookLog.Module.GMS,
                "App Set request entered request=${request?.javaClass?.name} callback=${callback?.javaClass?.name}"
            )
            request?.javaClass?.let { logClassHierarchyMethods("App Set request object", it) }
            callback?.javaClass?.let { logClassHierarchyMethods("App Set callback object", it) }
            method.parameterTypes.getOrNull(1)?.let { logClassHierarchyMethods("App Set callback type", it) }
        })
        HookLog.i(HookLog.Module.GMS, "hooked GMS App Set request method ${ownerClass.name}.${method.name}()")
    }

    private fun spoofRequest(classLoader: ClassLoader, callback: Any?): Boolean {
        val appSetId = currentConfig().appSetId.takeIf { it.isNotBlank() } ?: return false
        if (callback == null) return false

        return runCatching {
            val infoClass = HookBridge.findClass("com.google.android.gms.appset.AppSetInfoParcel", classLoader)
            val info = infoClass.newAppSetInfoParcel(appSetId)

            val callbackMethod = callback.javaClass.findDeclaredMethodInHierarchy { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].name == "com.google.android.gms.common.api.Status" &&
                    method.parameterTypes[1] == infoClass
            }

            if (callbackMethod != null) {
                val status = callbackMethod.parameterTypes[0].successStatus()
                callbackMethod.isAccessible = true
                runCatching {
                    callbackMethod.invoke(callback, status, info)
                    HookLog.i(
                        HookLog.Module.GMS,
                        "App Set ID spoofed from GMS service callback ${callback.javaClass.name}.${callbackMethod.name}()"
                    )
                    return true
                }.onFailure {
                    HookLog.i(HookLog.Module.GMS, "failed to invoke App Set callback method: ${it.message}")
                    if (spoofCallbackBinder(callback, status, info)) return true
                }
            } else {
                logClassHierarchyMethods("App Set callback object", callback.javaClass)
            }

            val statusClass = HookBridge.findClass("com.google.android.gms.common.api.Status", classLoader)
            spoofCallbackBinder(callback, statusClass.successStatus(), info)
        }.onFailure {
            HookLog.i(HookLog.Module.GMS, "failed to spoof App Set ID from GMS service callback: ${it.message}")
        }.getOrDefault(false)
    }

    private fun spoofCallbackBinder(callback: Any, status: Any, info: Any): Boolean {
        val binder = (callback as? IInterface)?.asBinder() ?: return false
        val data = Parcel.obtain()
        return runCatching {
            data.writeInterfaceToken(APP_SET_CALLBACK_DESCRIPTOR)
            data.writeParcelableCompat(status as Parcelable, 0)
            data.writeParcelableCompat(info as Parcelable, 0)
            val handled = binder.transact(APP_SET_CALLBACK_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
            if (handled) {
                HookLog.i(HookLog.Module.GMS, "App Set ID spoofed from GMS callback binder")
            } else {
                HookLog.i(HookLog.Module.GMS, "App Set callback binder transact returned false")
            }
            handled
        }.onFailure {
            HookLog.i(HookLog.Module.GMS, "failed to spoof App Set ID from callback binder: ${it.message}")
        }.getOrDefault(false).also {
            data.recycle()
        }
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

    companion object {
        const val APP_SET_SERVICE_DESCRIPTOR =
            "com.google.android.gms.appset.internal.IAppSetService"

        private const val APP_SET_CALLBACK_DESCRIPTOR =
            "com.google.android.gms.appset.internal.IAppSetIdCallback"
        private const val APP_SET_CALLBACK_TRANSACTION = 1
        private const val REPLACEMENT_PARCEL_EXTRA =
            "asia.nana7mi.arirang.gms.app_set_replacement_parcel"
    }
}
