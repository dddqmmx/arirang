package asia.nana7mi.arirang.hook

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import com.google.android.gms.appset.zzc as AppSetIdResult
import com.google.android.gms.common.api.Status
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class FuckGms : BaseHookModule(targetPackages = setOf(GMS_PACKAGE)) {

    private companion object {
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_GAID = "gaid"
        private const val KEY_APP_SET_ID = "app_set_id"
        private const val DEBUG_HARDCODE_IDS = false
        private const val DEBUG_GAID = "00000000-0000-4000-8000-000000000001"
        private const val DEBUG_APP_SET_ID = "00000000-0000-4000-8000-000000000002"

        private const val ADS_IDENTIFIER_DESCRIPTOR =
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"
        private const val ADS_IDENTIFIER_GET_ID_TRANSACTION = 1

        private const val APP_SET_CALLBACK_DESCRIPTOR =
            "com.google.android.gms.appset.internal.IAppSetIdCallback"
        private const val APP_SET_SERVICE_DESCRIPTOR =
            "com.google.android.gms.appset.internal.IAppSetService"
        private const val APP_SET_CALLBACK_TRANSACTION = 1
    }

    @Volatile
    private var cachedConfig = IdentifierConfig()

    @Volatile
    private var gmsContext: Context? = null

    private val hookedServiceClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val hookedAppSetServiceClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookApplicationContext(lpparam.classLoader)
        hookAdvertisingIdService()
        hookAppSetCallback(lpparam.classLoader)
        HookLog.i(HookLog.Module.GMS, "GMS identifier hook installed")
    }

    private fun hookApplicationContext(classLoader: ClassLoader) {
        val applicationClass = XposedHelpers.findClassIfExists("android.app.Application", classLoader)
            ?: Application::class.java
        XposedBridge.hookAllMethods(applicationClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                gmsContext = app.applicationContext
                HookNotifyClient.autoBindCurrentUser(app)
            }
        })
    }

    private fun hookAdvertisingIdService() {
        XposedHelpers.findAndHookMethod(
            Binder::class.java,
            "attachInterface",
            IInterface::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val descriptor = param.args.getOrNull(1) as? String ?: return
                    val owner = param.args.getOrNull(0) ?: return
                    when (descriptor) {
                        ADS_IDENTIFIER_DESCRIPTOR -> hookAdvertisingIdServiceTransact(owner.javaClass)
                        APP_SET_SERVICE_DESCRIPTOR -> logAppSetService(owner.javaClass)
                    }
                }
            }
        )
    }

    private fun hookAdvertisingIdServiceTransact(ownerClass: Class<*>) {
        val transactClass = ownerClass.findOnTransactClass() ?: return
        if (!hookedServiceClasses.add(transactClass)) return

        logAdvertisingIdServiceMethods(ownerClass)
        hookAdvertisingIdGetters(ownerClass)
        XposedBridge.hookAllMethods(transactClass, "onTransact", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                spoofAdvertisingIdIfMatched(param)
            }
        })
        HookLog.i(HookLog.Module.GMS, "hooked GMS Advertising ID service ${ownerClass.name}/${transactClass.name}")
    }

    private fun hookAdvertisingIdGetters(ownerClass: Class<*>) {
        val methods = ownerClass.declaredMethods.filter { method ->
            method.returnType == String::class.java && method.parameterTypes.isEmpty()
        }

        if (methods.isEmpty()) {
            HookLog.i(HookLog.Module.GMS, "Advertising ID String getter not found on ${ownerClass.name}")
            return
        }

        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val gaid = currentConfig().gaid.takeIf { it.isNotBlank() } ?: return
                    param.result = gaid
                    HookLog.i(HookLog.Module.GMS, "GAID spoofed from ${ownerClass.name}.${method.name}()")
                }
            })
            HookLog.i(HookLog.Module.GMS, "hooked GMS Advertising ID getter ${ownerClass.name}.${method.name}()")
        }
    }

    private fun logAdvertisingIdServiceMethods(ownerClass: Class<*>) {
        logClassHierarchyMethods("Advertising ID service", ownerClass)
    }

    private fun logAppSetService(ownerClass: Class<*>) {
        val transactClass = ownerClass.findOnTransactClass()
        logClassHierarchyMethods("App Set service", ownerClass)
        hookAppSetRequestMethod(ownerClass)
        HookLog.i(HookLog.Module.GMS, "found GMS App Set service ${ownerClass.name}/${transactClass?.name}")
    }

    private fun hookAppSetRequestMethod(ownerClass: Class<*>) {
        if (!hookedAppSetServiceClasses.add(ownerClass)) return

        val method = ownerClass.declaredMethods.firstOrNull { it.isAppSetRequestMethod() }

        if (method == null) {
            HookLog.i(HookLog.Module.GMS, "App Set request method not found on ${ownerClass.name}")
            return
        }

        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val request = param.args.getOrNull(0)
                val callback = param.args.getOrNull(1)
                val appSetClassLoader = ownerClass.classLoader ?: ClassLoader.getSystemClassLoader()
                if (spoofAppSetRequest(appSetClassLoader, callback)) {
                    param.result = null
                    return
                }
                HookLog.i(
                    HookLog.Module.GMS,
                    "App Set request entered request=${request?.javaClass?.name} callback=${callback?.javaClass?.name}"
                )
                request?.javaClass?.let { logClassHierarchyMethods("App Set request object", it) }
                callback?.javaClass?.let { logClassHierarchyMethods("App Set callback object", it) }
                method.parameterTypes.getOrNull(1)?.let { logClassHierarchyMethods("App Set callback type", it) }
            }
        })
        HookLog.i(HookLog.Module.GMS, "hooked GMS App Set request method ${ownerClass.name}.${method.name}()")
    }

    private fun spoofAppSetRequest(classLoader: ClassLoader, callback: Any?): Boolean {
        val appSetId = currentConfig().appSetId.takeIf { it.isNotBlank() } ?: return false
        if (callback == null) return false

        return runCatching {
            val infoClass = XposedHelpers.findClass("com.google.android.gms.appset.AppSetInfoParcel", classLoader)
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
                    if (spoofAppSetCallbackBinder(callback, status, info)) return true
                }
            } else {
                logClassHierarchyMethods("App Set callback object", callback.javaClass)
            }

            val statusClass = XposedHelpers.findClass("com.google.android.gms.common.api.Status", classLoader)
            spoofAppSetCallbackBinder(callback, statusClass.successStatus(), info)
        }.onFailure {
            HookLog.i(HookLog.Module.GMS, "failed to spoof App Set ID from GMS service callback: ${it.message}")
        }.getOrDefault(false)
    }

    private fun spoofAppSetCallbackBinder(callback: Any, status: Any, info: Any): Boolean {
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

    private fun hookAppSetCallback(classLoader: ClassLoader) {
        val binderProxyClass = XposedHelpers.findClassIfExists("android.os.BinderProxy", classLoader)
            ?: XposedHelpers.findClassIfExists("android.os.BinderProxy", ClassLoader.getSystemClassLoader())
            ?: return

        XposedHelpers.findAndHookMethod(
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
                        val status = data.readParcelableAfterInterfaceToken(Status.CREATOR) ?: Status.RESULT_SUCCESS
                        replacement.writeInterfaceToken(APP_SET_CALLBACK_DESCRIPTOR)
                        replacement.writeParcelableCompat(status, 0)
                        replacement.writeParcelableCompat(AppSetIdResult(appSetId, 1), 0)
                        param.args[1] = replacement
                        HookLog.i(HookLog.Module.GMS, "App Set ID spoofed from GMS callback")
                    } catch (t: Throwable) {
                        replacement.recycle()
                        HookLog.i(HookLog.Module.GMS, "failed to rewrite App Set ID callback: ${t.message}")
                    }
                }
            }
        )
    }

    private fun currentConfig(): IdentifierConfig {
        if (DEBUG_HARDCODE_IDS) {
            return IdentifierConfig(
                enabled = true,
                gaid = DEBUG_GAID,
                appSetId = DEBUG_APP_SET_ID
            )
        }
        return readHookNotifyConfig()?.also { cachedConfig = it } ?: cachedConfig
    }

    private fun readHookNotifyConfig(): IdentifierConfig? {
        return runCatching {
            val context = gmsContext
            val snapshot = HookNotifyClient.readUniqueIdentifierConfigSnapshot(
                allowBind = context != null,
                bindContext = context,
                bindCurrentUser = true
            ) ?: return cachedConfig
            val root = JSONObject(snapshot)
            if (!root.optString(KEY_ENABLED).toBooleanStrictOrNull().orFalse()) {
                return IdentifierConfig()
            }
            IdentifierConfig(
                enabled = true,
                gaid = root.optString(KEY_GAID),
                appSetId = root.optString(KEY_APP_SET_ID)
            )
        }.onFailure {
            HookLog.i(HookLog.Module.GMS, "failed to read GMS identifier config through IHookNotify: ${it.message}")
        }.getOrNull()
    }

    private fun Boolean?.orFalse(): Boolean {
        return this ?: false
    }

    private fun Parcel.interfaceTokenOrNull(): String? {
        val oldPosition = dataPosition()
        return runCatching {
            setDataPosition(0)
            readString()
        }.also {
            setDataPosition(oldPosition)
        }.getOrNull()
    }

    private fun Parcel.replaceWithStringResult(value: String) {
        setDataSize(0)
        setDataPosition(0)
        writeNoException()
        writeString(value)
        setDataPosition(0)
    }

    private fun <T> Parcel.readParcelableAfterInterfaceToken(creator: android.os.Parcelable.Creator<T>): T? {
        setDataPosition(0)
        readString()
        val present = readInt()
        return if (present != 0) creator.createFromParcel(this) else null
    }

    private fun Parcel.writeParcelableCompat(value: android.os.Parcelable?, flags: Int) {
        if (value == null) {
            writeInt(0)
        } else {
            writeInt(1)
            value.writeToParcel(this, flags)
        }
    }

    private fun Class<*>.findOnTransactClass(): Class<*>? {
        var current: Class<*>? = this
        while (current != null && current != Binder::class.java) {
            if (current.declaredMethods.any { it.name == "onTransact" }) {
                return current
            }
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.findDeclaredMethodInHierarchy(
        predicate: (java.lang.reflect.Method) -> Boolean
    ): java.lang.reflect.Method? {
        var current: Class<*>? = this
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull(predicate)?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun java.lang.reflect.Method.isAppSetRequestMethod(): Boolean {
        val params = parameterTypes
        return returnType == Void.TYPE &&
            params.size == 2 &&
            params[0].name == "com.google.android.gms.appset.AppSetIdRequestParams" &&
            params[1].declaredMethods.any { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].name == "com.google.android.gms.common.api.Status" &&
                    method.parameterTypes[1].name == "com.google.android.gms.appset.AppSetInfoParcel"
            }
    }

    private fun Class<*>.newAppSetInfoParcel(appSetId: String): Any {
        val constructor = declaredConstructors.firstOrNull { constructor ->
            val params = constructor.parameterTypes
            params.size == 2 && params[0] == String::class.java && params[1] == Int::class.javaPrimitiveType
        } ?: run {
            logConstructors("App Set info parcel", this)
            error("AppSetInfoParcel(String,int) not found on $name")
        }
        constructor.isAccessible = true
        return constructor.newInstance(appSetId, 1)
    }

    private fun Class<*>.successStatus(): Any {
        runCatching {
            val field = getField("RESULT_SUCCESS")
            return field.get(null) ?: error("Status.RESULT_SUCCESS is null on $name")
        }
        declaredConstructors.firstOrNull { constructor ->
            val params = constructor.parameterTypes
            params.size == 1 && params[0] == Int::class.javaPrimitiveType
        }?.let { constructor ->
            constructor.isAccessible = true
            return constructor.newInstance(0)
        }
        error("Status.RESULT_SUCCESS or Status(int) not found on $name")
    }

    private fun logClassHierarchyMethods(label: String, ownerClass: Class<*>) {
        var current: Class<*>? = ownerClass
        while (current != null && current != Any::class.java) {
            val signatures = current.declaredMethods
                .joinToString("; ") { method ->
                    val params = method.parameterTypes.joinToString(",") { it.name }
                    "${method.returnType.name} ${method.name}($params)"
                }
            HookLog.i(HookLog.Module.GMS, "$label class ${current.name} methods=[$signatures]")
            current = current.superclass
        }
    }

    private fun logConstructors(label: String, ownerClass: Class<*>) {
        val signatures = ownerClass.declaredConstructors.joinToString("; ") { constructor ->
            val params = constructor.parameterTypes.joinToString(",") { it.name }
            "${ownerClass.name}($params)"
        }
        HookLog.i(HookLog.Module.GMS, "$label class ${ownerClass.name} constructors=[$signatures]")
    }

    private data class IdentifierConfig(
        val enabled: Boolean = false,
        val gaid: String = "",
        val appSetId: String = ""
    )
}
