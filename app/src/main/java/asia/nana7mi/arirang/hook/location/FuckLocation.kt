package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.getFieldValue

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.SystemClock
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.config.ConfigIds
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class FuckLocation : BaseHookModule(
    targetPackages = setOf(
        "android",
        "com.android.location.fused",
        "com.google.android.gms"
    )
) {

    private companion object {
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
        private const val STATUS_DELIVERY_DEDUP_WINDOW_MS = 1_000L
        private const val PROACTIVE_UPDATES_DELAY_MS = 200L
        private const val PROACTIVE_UPDATES_DEDUP_WINDOW_MS = 2_000L
        private const val LOCATION_RECEIVER_TYPE_STATUS = 4

        private val GNSS_CLASSES = arrayOf(
            "com.android.server.location.gnss.hal.GnssNative",
            "com.android.server.location.gnss.GnssLocationProvider",
            "com.android.server.location.gnss.GnssLocationProviderImpl"
        )
    }

    // Zygote specialize / Xposed module load 时尚无 main looper，不能在字段初始化时 new Handler。
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val hookedClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    // receiverPackages —— 注册时（getCallingUid 仍指向真正请求方）把投递用的 callback/listener
    //   binder 绑定到包名；key 为 GMS 持有的 BinderProxy，使用 WeakHashMap，GMS 注销后自动回收。
    // activeDeliveryPackage —— 异步投递时按 binder 反查到的包名写入此 thread-local，供 resolveProfile() 消费。
    private val receiverPackages = Collections.synchronizedMap(WeakHashMap<IBinder, String>())
    private val activeDeliveryPackage = ThreadLocal<String?>()

    private val deliveredStatusCallbacks = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<IBinder, Boolean>())
    )

    // requestLocationUpdates 主动投递去重：同一 binder 在短窗口内只推一次，避免多 provider 重复刷。
    private val deliveredUpdateCallbacks = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<IBinder, Boolean>())
    )

    @Volatile
    private var gmsContext: Context? = null

    private val configFile = HookConfigFile(
        configName = ConfigIds.LOCATION,
        prefsName = LocationConfigPrefs.PREFS_NAME,
        defaultValue = LocationHookConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            val context = gmsContext
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.LOCATION,
                force = force,
                allowBind = true,
                bindContext = context,
                bindCurrentUser = context != null,
                logName = "location"
            )
        },
        parseRealtimeSnapshot = LocationHookConfigParser::parseSnapshot,
        readStoredConfig = LocationHookConfigParser::readStored
    )

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            when (lpparam.packageName) {
                "android" -> {
                    hookLocationManagerService(lpparam.classLoader)
                    hookProviderManager(lpparam.classLoader)
                    hookAndroidFusedProvider(lpparam.classLoader)
                    hookGnssReports(lpparam.classLoader)
                    hookFrameworkLocationResult(lpparam.classLoader)
                }
                "com.android.location.fused" -> {
                    hookAndroidFusedProvider(lpparam.classLoader)
                    hookFrameworkLocationResult(lpparam.classLoader)
                }
                "com.google.android.gms" -> {
                    hookApplicationContext(lpparam.classLoader)
                    hookGmsReceiverBinding(lpparam.classLoader)
                    hookLocationAccessors()
                    hookFrameworkLocationManagerClient(lpparam.classLoader)
                    hookFrameworkLocationResult(lpparam.classLoader)
                    hookGoogleFusedClient(lpparam.classLoader)
                    hookGoogleLocationResult(lpparam.classLoader)
                    hookGoogleTasks(lpparam.classLoader)
                    hookGoogleLocationCallbacks(lpparam.classLoader)
                    hookGmsInternalLocationService(lpparam.classLoader)
                }
            }
            HookLog.i(HookLog.Module.LOCATION, "location hook installed for ${lpparam.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.LOCATION, "location hook failed for ${lpparam.packageName}", it)
        }
    }

    private fun hookApplicationContext(classLoader: ClassLoader) {
        val applicationClass = HookBridge.findClassIfExists("android.app.Application", classLoader)
            ?: Application::class.java
        if (!hookedClasses.add(applicationClass)) return

        HookBridge.hookAllMethods(applicationClass, "onCreate", afterHookedMethod {
            val app = thisObject as? Application ?: return@afterHookedMethod
            gmsContext = app.applicationContext
            ArirangClient.autoBindCurrentUser(app)
        })
    }

    /**
     * GMS 融合定位路径的「按应用」修复（保持 system-level，不注入任何第三方进程）。
     *
     * 问题：第三方 App 通过 play-services 客户端库向 GMS 进程注册回调，GMS 用共享引擎算出位置后，
     * 在自己的线程上把结果异步 push 回各回调；投递时没有来自该 App 的 Binder 事务，
     * Binder.getCallingUid() 只会返回 GMS 自身，于是 perPackage 永远命不中、只能套默认档。
     *
     * 方案：在「注册时刻」（此时仍处于来自该 App 的 Binder 事务中，getCallingUid 有效）把投递用的
     * callback/listener binder 与请求方包名绑定；随后在投递代理上 hook，按 binder 反查包名并写入
     * thread-local，让既有的 LocationResult/Location 改写链路自动使用对应 App 的档案。
     */
    private fun hookGmsReceiverBinding(classLoader: ClassLoader) {
        // 稳定的 SDK 类名（不随 GMS 混淆/版本变化），注册时携带投递 binder 与请求方身份。
        listOf(
            "com.google.android.gms.location.internal.LocationReceiver",
            "com.google.android.gms.location.internal.LocationRequestUpdateData"
        ).forEach { className ->
            val clazz = HookBridge.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(clazz)) return@forEach
            HookBridge.hookAllConstructors(clazz, afterHookedMethod {
                captureReceiverBinding(thisObject)
            })
            HookLog.i(HookLog.Module.LOCATION, "hooked GMS receiver binding via ${clazz.simpleName}")
        }
    }

    private fun captureReceiverBinding(holder: Any?) {
        holder ?: return
        val pkg = LocationCallerResolver.packageNameFromObject(holder) ?: callingPackageForBinding()

        // 1) 绑定所有投递 binder（ILocationCallback / ILocationListener / IFusedLocationProviderCallback）。
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    when (val value = field.get(holder)) {
                        is IInterface -> {
                            value.asBinder()?.let { bindBinder(it, pkg) }
                            hookDeliveryProxyClass(value.javaClass)
                        }
                        is IBinder -> bindBinder(value, pkg)
                    }
                }
            }
            current = current.superclass
        }

        // 2) 现代 LocationReceiver 仅持有裸 IBinder，投递代理须经其 accessor 取得：
        //    扫描无参且返回 IInterface 的方法，惰性发现并 hook 投递代理类。
        holder.javaClass.declaredMethods.forEach { method ->
            if (method.parameterTypes.isEmpty() && IInterface::class.java.isAssignableFrom(method.returnType)) {
                runCatching {
                    method.isAccessible = true
                    val proxy = method.invoke(holder) as? IInterface
                    if (proxy != null) {
                        hookDeliveryProxyClass(proxy.javaClass)
                        scheduleProactiveFakeDelivery(holder, proxy)
                    }
                }
            }
        }
    }

    /**
     * getCurrentLocation 与 getLastLocation 共用 ILocationStatusCallback (LocationReceiver type 4)。
     * getLastLocation 在 gxoy.f() lambda 内同步调用 dfjy.a()，结果立即到达客户端；
     * getCurrentLocation 在 gxni.f() 内异步注册监听器，仅超时或真实定位到达时才回调，
     * 而 Self-Check 客户端的 Tasks.await 超时（2.5s）远短于 GMS 默认超时（~30s）。
     *
     * requestLocationUpdates 走 ILocationListener / ILocationCallback continuous 路径；
     * 若底层 network/gps 不产出新 fix（陈旧 last location 也会被 maxUpdateAge 丢掉），
     * 客户端会一直收不到 onLocationChanged，因此 continuous 也需要主动推一次假定位。
     *
     * 若同步路径（getLastLocation）已先完成交付，则 type-4 路径跳过，避免重复投递。
     */
    private fun scheduleProactiveFakeDelivery(holder: Any, callbackProxy: IInterface) {
        val receiverType = runCatching { HookBridge.getIntField(holder, "a") }.getOrNull()
        val profile = resolveProfile() ?: return
        val binder = callbackProxy.asBinder() ?: return
        val pkg = receiverPackages[binder]
        val cl = holder.javaClass.classLoader
        val proxyRef = WeakReference(callbackProxy)

        mainHandler.postDelayed({
            val proxy = proxyRef.get() ?: return@postDelayed
            if (receiverType == LOCATION_RECEIVER_TYPE_STATUS) {
                deliverProactiveStatusLocation(proxy, binder, profile, cl)
            } else {
                deliverProactiveUpdateLocation(
                    target = proxy,
                    binder = binder,
                    profile = profile,
                    provider = LocationManager.FUSED_PROVIDER,
                    packageName = pkg,
                    classLoader = cl
                )
            }
        }, PROACTIVE_UPDATES_DELAY_MS)
    }

    private fun deliverProactiveStatusLocation(
        callbackProxy: IInterface,
        binder: IBinder,
        profile: LocationProfile,
        classLoader: ClassLoader?
    ) {
        if (!markStatusDelivered(binder)) return
        runCatching {
            val statusClass = HookBridge.findClass("com.google.android.gms.common.api.Status", classLoader)
            val statusOk = HookBridge.getStaticObjectField(statusClass, "b")
            val fakeLoc = fakeLocation(profile)
            HookBridge.callMethod(callbackProxy, "a", statusOk, fakeLoc)
            HookLog.d(HookLog.Module.LOCATION, "proactive fake delivery for getCurrentLocation")
        }.onFailure {
            synchronized(deliveredStatusCallbacks) {
                deliveredStatusCallbacks.remove(binder)
            }
        }
    }

    private fun deliverProactiveUpdateLocation(
        target: Any,
        binder: IBinder?,
        profile: LocationProfile,
        provider: String,
        packageName: String?,
        classLoader: ClassLoader?
    ) {
        if (binder != null && !markUpdateDelivered(binder)) return

        val location = fakeLocation(profile, provider)
        if (packageName != null) {
            activeDeliveryPackage.set(packageName)
        }
        try {
            val delivered = deliverFakeLocationToTarget(target, location, classLoader)
            if (delivered) {
                HookLog.d(
                    HookLog.Module.LOCATION,
                    "proactive fake delivery for requestLocationUpdates provider=$provider caller=$packageName"
                )
            } else if (binder != null) {
                synchronized(deliveredUpdateCallbacks) {
                    deliveredUpdateCallbacks.remove(binder)
                }
            }
        } finally {
            activeDeliveryPackage.remove()
        }
    }

    private fun scheduleProactiveUpdateDelivery(
        target: Any,
        profile: LocationProfile,
        provider: String,
        packageName: String?
    ) {
        val binder = (target as? IInterface)?.asBinder()
        val targetRef = WeakReference(target)
        val cl = target.javaClass.classLoader
        mainHandler.postDelayed({
            val liveTarget = targetRef.get() ?: return@postDelayed
            deliverProactiveUpdateLocation(
                target = liveTarget,
                binder = binder,
                profile = profile,
                provider = provider,
                packageName = packageName,
                classLoader = cl
            )
        }, PROACTIVE_UPDATES_DELAY_MS)
    }

    private fun markStatusDelivered(binder: IBinder): Boolean {
        val added = synchronized(deliveredStatusCallbacks) {
            deliveredStatusCallbacks.add(binder)
        }
        if (added) {
            val binderRef = WeakReference(binder)
            mainHandler.postDelayed({
                val liveBinder = binderRef.get() ?: return@postDelayed
                synchronized(deliveredStatusCallbacks) {
                    deliveredStatusCallbacks.remove(liveBinder)
                }
            }, STATUS_DELIVERY_DEDUP_WINDOW_MS)
        }
        return added
    }

    private fun markUpdateDelivered(binder: IBinder): Boolean {
        val added = synchronized(deliveredUpdateCallbacks) {
            deliveredUpdateCallbacks.add(binder)
        }
        if (added) {
            val binderRef = WeakReference(binder)
            mainHandler.postDelayed({
                val liveBinder = binderRef.get() ?: return@postDelayed
                synchronized(deliveredUpdateCallbacks) {
                    deliveredUpdateCallbacks.remove(liveBinder)
                }
            }, PROACTIVE_UPDATES_DEDUP_WINDOW_MS)
        }
        return added
    }

    private fun deliverFakeLocationToTarget(
        target: Any,
        location: Location,
        classLoader: ClassLoader?
    ): Boolean {
        if (target.dispatchLocation(location)) return true

        val googleResult = createLocationResult(
            className = "com.google.android.gms.location.LocationResult",
            classLoader = classLoader ?: target.javaClass.classLoader,
            location = location
        )
        val frameworkResult = createLocationResult(
            className = "android.location.LocationResult",
            classLoader = classLoader ?: target.javaClass.classLoader,
            location = location
        )

        var delivered = false
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredMethods.forEach { method ->
                if (Modifier.isStatic(method.modifiers)) return@forEach
                if (method.parameterTypes.isEmpty()) return@forEach
                if (!method.parameterTypes.any {
                        it == Location::class.java || it.name.endsWith(".LocationResult")
                    }
                ) {
                    return@forEach
                }

                val args = Array(method.parameterTypes.size) { index ->
                    val type = method.parameterTypes[index]
                    when {
                        type == Location::class.java -> location
                        type.name == "com.google.android.gms.location.LocationResult" -> googleResult
                        type.name == "android.location.LocationResult" ||
                            type.name.endsWith(".LocationResult") -> frameworkResult ?: googleResult
                        type == Boolean::class.javaPrimitiveType -> false
                        type == Int::class.javaPrimitiveType -> 0
                        type == Long::class.javaPrimitiveType -> 0L
                        type == Float::class.javaPrimitiveType -> 0f
                        type == Double::class.javaPrimitiveType -> 0.0
                        type == String::class.java -> location.provider
                        List::class.java.isAssignableFrom(type) -> listOf(location)
                        type.isArray && type.componentType == Location::class.java -> arrayOf(location)
                        else -> null
                    }
                }

                val missingRequiredLocation = method.parameterTypes.indices.any { index ->
                    val type = method.parameterTypes[index]
                    args[index] == null && (
                        type == Location::class.java || type.name.endsWith(".LocationResult")
                    )
                }
                if (missingRequiredLocation) return@forEach

                val ok = runCatching {
                    method.isAccessible = true
                    method.invoke(target, *args)
                    true
                }.getOrDefault(false)
                if (ok) delivered = true
            }
            current = current.superclass
        }
        return delivered
    }

    private fun createLocationResult(
        className: String,
        classLoader: ClassLoader?,
        location: Location
    ): Any? {
        val resultClass = HookBridge.findClassIfExists(className, classLoader) ?: return null
        runCatching {
            return HookBridge.callStaticMethod(resultClass, "create", location)
        }
        runCatching {
            return HookBridge.callStaticMethod(resultClass, "create", listOf(location))
        }
        runCatching {
            return HookBridge.newInstance(resultClass, listOf(location))
        }
        return null
    }

    private fun isLocationDeliveryTarget(value: Any?): Boolean {
        value ?: return false
        if (value is Location || value is String || value is Number || value is Boolean) return false
        if (value is IInterface) return true
        if (value.javaClass.hasLocationCallbackMethod()) return true
        return value.javaClass.declaredMethods.any { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.any {
                    it == Location::class.java || it.name.endsWith(".LocationResult")
                }
        }
    }

    private fun bindBinder(binder: IBinder, pkg: String?) {
        pkg ?: return
        receiverPackages[binder] = pkg
        HookLog.d(HookLog.Module.LOCATION, "bound GMS delivery binder to caller profile")
    }

    /**
     * 注册发生在来自请求方 App 的 Binder 事务中时，getCallingUid() 即真正的请求方；
     * 若构造发生在 GMS 内部（无事务上下文），则回退为 GMS 自身，放弃归属（保持默认档）。
     */
    private fun callingPackageForBinding(): String? {
        val pkg = LocationCallerResolver.packageNameForUid(Binder.getCallingUid()) ?: return null
        return pkg.takeIf { it != "com.google.android.gms" && it != "android" }
    }

    private fun hookDeliveryProxyClass(proxyClass: Class<*>) {
        if (!IInterface::class.java.isAssignableFrom(proxyClass)) return
        if (!hookedClasses.add(proxyClass)) return

        var hooked = false
        proxyClass.declaredMethods.forEach { method ->
            val carriesLocation = method.parameterTypes.any { type ->
                type == Location::class.java ||
                    type.name == "com.google.android.gms.location.LocationResult" ||
                    type.name == "com.google.android.gms.location.internal.FusedLocationProviderResult"
            }
            if (!carriesLocation) return@forEach

            HookBridge.hookMethod(method, hookedMethod(
                before = {
                    // 按投递目标 binder 反查包名，写入 thread-local（供同线程内嵌的 writeToParcel 等改写点消费），
                    // 并就地改写入参，双保险。
                    val pkg = (thisObject as? IInterface)?.asBinder()?.let { receiverPackages[it] }
                    activeDeliveryPackage.set(pkg)
                    resolveProfile(pkg)?.let { profile ->
                        args.forEachIndexed { index, arg ->
                            if (arg == null && method.parameterTypes[index] == Location::class.java) {
                                args[index] = fakeLocation(profile)
                                (thisObject as? IInterface)?.asBinder()?.let(::markStatusDelivered)
                            } else {
                                rewriteLocationContainer(arg, profile)?.let { args[index] = it }
                                (thisObject as? IInterface)?.asBinder()?.let(::markStatusDelivered)
                            }
                        }
                    }
                },
                after = {
                    activeDeliveryPackage.remove()
                }
            ))
            hooked = true
        }
        if (hooked) {
            HookLog.i(HookLog.Module.LOCATION, "hooked GMS delivery proxy ${proxyClass.name}")
        }
    }

    private fun currentConfig(): LocationHookConfig {
        return configFile.current()
    }

    private fun globalRewriteProfile(): LocationProfile? {
        val config = currentConfig()
        if (!config.enabled || !config.defaultProfile.enabled) return null
        return config.defaultProfile
    }

    private fun resolveProfile(packageName: String? = null): LocationProfile? {
        val config = currentConfig()
        if (!config.enabled) return null

        val callingUid = Binder.getCallingUid()
        val pkg = packageName ?: activeDeliveryPackage.get() ?: LocationCallerResolver.packageNameForUid(callingUid)

        val profile = if (pkg != null && pkg != "android" && pkg != "com.google.android.gms") {
            config.perPackage[pkg] ?: config.defaultProfile
        } else {
            config.defaultProfile
        }

        if (profile.enabled) {
            val source = if (pkg != null && config.perPackage.containsKey(pkg)) "per-package" else "default"
            HookLog.d(HookLog.Module.LOCATION, "resolved $source location profile for uid=$callingUid")
            return profile
        }
        return null
    }

    private fun profileForPackage(packageName: String?): LocationProfile? {
        return resolveProfile(packageName)
    }

    private fun profileForArgs(args: Array<Any?>): LocationProfile? {
        return profileForPackage(
            LocationCallerResolver.callerFromArgs(args)
                ?: LocationCallerResolver.packageNameForUid(Binder.getCallingUid())
        )
    }

    private fun profileForReceiver(receiver: Any?): LocationProfile? {
        return profileForPackage(LocationCallerResolver.packageNameFromObject(receiver))
    }

    private fun hookLocationAccessors() {
        if (!hookedClasses.add(Location::class.java)) return

        HookBridge.hookAllMethods(Location::class.java, "getLatitude", beforeHookedMethod {
            resolveProfile()?.let { result = it.latitude }
        })
        HookBridge.hookAllMethods(Location::class.java, "getLongitude", beforeHookedMethod {
            resolveProfile()?.let { result = it.longitude }
        })
        HookBridge.hookAllMethods(Location::class.java, "getAltitude", beforeHookedMethod {
            resolveProfile()?.let { result = it.altitude }
        })
        HookBridge.hookAllMethods(Location::class.java, "getAccuracy", beforeHookedMethod {
            resolveProfile()?.let { result = it.accuracy }
        })
        HookBridge.hookAllMethods(Location::class.java, "getSpeed", beforeHookedMethod {
            resolveProfile()?.let { result = it.speed }
        })
        HookBridge.hookAllMethods(Location::class.java, "getBearing", beforeHookedMethod {
            resolveProfile()?.let { result = it.bearing }
        })
        HookBridge.hookAllMethods(Location::class.java, "getProvider", beforeHookedMethod {
            if (resolveProfile() != null) result = LocationManager.GPS_PROVIDER
        })
        HookBridge.hookAllMethods(Location::class.java, "getTime", beforeHookedMethod {
            if (resolveProfile() != null) result = System.currentTimeMillis()
        })
        HookBridge.hookAllMethods(Location::class.java, "getElapsedRealtimeNanos", beforeHookedMethod {
            if (resolveProfile() != null) result = SystemClock.elapsedRealtimeNanos()
        })

        runCatching {
            HookBridge.hookAllMethods(Location::class.java, "isFromMockProvider", beforeHookedMethod {
                if (resolveProfile() != null) result = false
            })
        }
        runCatching {
            HookBridge.hookAllMethods(Location::class.java, "isMock", beforeHookedMethod {
                if (resolveProfile() != null) result = false
            })
        }

        // 关键修复：Hook writeToParcel 确保跨进程传输前数据已被修改
        HookBridge.hookAllMethods(Location::class.java, "writeToParcel", beforeHookedMethod {
            val profile = resolveProfile() ?: return@beforeHookedMethod
            (thisObject as Location).spoofInPlace(profile)
        })

        runCatching {
            val creator = HookBridge.getStaticObjectField(Location::class.java, "CREATOR")
            if (creator != null && hookedClasses.add(creator.javaClass)) {
                HookBridge.hookAllMethods(creator.javaClass, "createFromParcel", afterHookedMethod {
                    val profile = resolveProfile() ?: return@afterHookedMethod
                    (result as? Location)?.spoofInPlace(profile)
                })
            }
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked Location accessors and CREATOR")
    }

    private fun hookLocationManagerService(classLoader: ClassLoader) {
        val lmsClass = HookBridge.findClassIfExists(
            "com.android.server.location.LocationManagerService",
            classLoader
        ) ?: return
        if (!hookedClasses.add(lmsClass)) return
        hookLocationReceiverDelivery(lmsClass)

        HookBridge.hookAllMethods(lmsClass, "getLastLocation", afterHookedMethod {
            val profile = profileForArgs(args) ?: return@afterHookedMethod
            val provider = LocationCallerResolver.providerFromArgs(args)
            result = fakeLocation(profile, provider)
            HookLog.d(
                HookLog.Module.LOCATION,
                "spoofed getLastLocation provider=$provider caller=${LocationCallerResolver.callerFromArgs(args)}"
            )
        })

        HookBridge.hookAllMethods(lmsClass, "getCurrentLocation", beforeHookedMethod {
            val profile = profileForArgs(args) ?: return@beforeHookedMethod
            val callback = args.firstOrNull { it?.javaClass?.hasLocationCallbackMethod() == true } ?: return@beforeHookedMethod
            val location = fakeLocation(profile, LocationCallerResolver.providerFromArgs(args))
            if (callback.dispatchLocation(location)) {
                result = null
                HookLog.d(
                    HookLog.Module.LOCATION,
                    "spoofed getCurrentLocation provider=${location.provider} caller=${LocationCallerResolver.callerFromArgs(args)}"
                )
            }
        })

        listOf(
            "requestLocationUpdates",
            "requestLocationUpdatesWithPackageName",
            "registerLocationListener",
            "registerLocationPendingIntent"
        ).forEach { methodName ->
            HookBridge.hookAllMethods(lmsClass, methodName, hookedMethod(
                before = {
                    val profile = profileForArgs(args) ?: return@hookedMethod
                    args.forEachIndexed { index, arg ->
                        if (arg is Location) {
                            args[index] = arg.spoofed(profile)
                        }
                    }
                },
                after = {
                    val profile = profileForArgs(args) ?: return@hookedMethod
                    val provider = LocationCallerResolver.providerFromArgs(args)
                    val pkg = LocationCallerResolver.callerFromArgs(args)
                        ?: LocationCallerResolver.packageNameForUid(Binder.getCallingUid())
                    args.forEach { arg ->
                        if (isLocationDeliveryTarget(arg)) {
                            scheduleProactiveUpdateDelivery(arg, profile, provider, pkg)
                        }
                    }
                }
            ))
        }
    }

    private fun hookLocationReceiverDelivery(lmsClass: Class<*>) {
        val receiverClasses = lmsClass.declaredClasses
            .filter { it.simpleName == "Receiver" || it.name.endsWith("\$Receiver") }

        receiverClasses.forEach { receiverClass ->
            if (!hookedClasses.add(receiverClass)) return@forEach
            HookBridge.hookAllConstructors(receiverClass, afterHookedMethod {
                val profile = profileForReceiver(thisObject)
                    ?: profileForArgs(args)
                    ?: profileForPackage(LocationCallerResolver.packageNameForUid(Binder.getCallingUid()))
                    ?: return@afterHookedMethod
                val provider = LocationCallerResolver.providerFromArgs(args)
                val pkg = LocationCallerResolver.packageNameFromObject(thisObject)
                    ?: LocationCallerResolver.callerFromArgs(args)
                    ?: LocationCallerResolver.packageNameForUid(Binder.getCallingUid())
                // Receiver 构造后立即 + 延迟各投一次：部分版本字段在构造末尾才写完。
                scheduleProactiveUpdateDelivery(thisObject, profile, provider, pkg)
                val receiverRef = WeakReference(thisObject)
                mainHandler.postDelayed({
                    val receiver = receiverRef.get() ?: return@postDelayed
                    deliverProactiveUpdateLocation(
                        target = receiver,
                        binder = null,
                        profile = profile,
                        provider = provider,
                        packageName = pkg,
                        classLoader = receiver.javaClass.classLoader
                    )
                }, PROACTIVE_UPDATES_DELAY_MS + 100L)
            })
            receiverClass.declaredMethods
                .filter { method ->
                    (
                        method.name == "callLocationChangedLocked" ||
                            method.name == "updateLocation" ||
                            method.name.contains("LocationChanged", ignoreCase = true)
                        ) &&
                        method.parameterTypes.any {
                            it == Location::class.java || it.name.endsWith(".LocationResult")
                        }
                }
                .forEach { method ->
                    HookBridge.hookMethod(method, beforeHookedMethod {
                        val profile = profileForReceiver(thisObject) ?: return@beforeHookedMethod
                        args.forEachIndexed { index, arg ->
                            when {
                                arg is Location -> args[index] = arg.spoofed(profile)
                                arg == null && method.parameterTypes[index] == Location::class.java -> {
                                    args[index] = fakeLocation(
                                        profile,
                                        LocationCallerResolver.providerFromArgs(args)
                                    )
                                }
                                else -> args[index] = rewriteLocationContainer(arg, profile)
                            }
                        }
                        HookLog.d(
                            HookLog.Module.LOCATION,
                            "rewrote receiver delivery for ${LocationCallerResolver.packageNameFromObject(thisObject)} via ${method.name}"
                        )
                    })
                }
        }
    }

    private fun hookProviderManager(classLoader: ClassLoader) {
        val providerManagerClass = HookBridge.findClassIfExists(
            "com.android.server.location.provider.LocationProviderManager",
            classLoader
        ) ?: return
        if (!hookedClasses.add(providerManagerClass)) return

        HookBridge.hookAllMethods(providerManagerClass, "onReportLocation", beforeHookedMethod {
            val profile = globalRewriteProfile() ?: return@beforeHookedMethod
            val report = args.firstOrNull() ?: return@beforeHookedMethod
            if (report is Location) {
                args[0] = report.spoofed(profile)
                return@beforeHookedMethod
            }

            if (rewriteLocationResult(report, profile)) {
                HookLog.d(HookLog.Module.LOCATION, "rewrote provider LocationResult ${report.javaClass.name}")
            }
        })

        // 注册时 framework 会读 last location；若 elapsedRealtime 过旧会被 maxUpdateAge 丢掉。
        // 对 last-location 读路径直接返回“新鲜”假坐标，确保 HelloTalk 这类仅订 network 的客户端能立刻收到。
        listOf("getLastLocation", "getLastLocationUnsafe", "getLastLocationLocked").forEach { methodName ->
            HookBridge.hookAllMethods(providerManagerClass, methodName, afterHookedMethod {
                val profile = globalRewriteProfile() ?: return@afterHookedMethod
                val provider = providerNameOf(thisObject)
                when (val current = result) {
                    is Location -> result = current.spoofed(profile).also {
                        // spoofed() 已刷新 time/elapsedRealtimeNanos
                    }
                    null -> result = fakeLocation(profile, provider)
                    else -> {
                        if (current.javaClass.name.endsWith(".LocationResult")) {
                            rewriteLocationResult(current, profile)
                        }
                    }
                }
            })
        }

        // 注册 listener 后主动 report 一次，覆盖“没有 last / last 被过滤”的路径。
        listOf(
            "registerLocationRequest",
            "registerWithService",
            "onRegistrationActive",
            "setRealRequestLocked"
        ).forEach { methodName ->
            HookBridge.hookAllMethods(providerManagerClass, methodName, afterHookedMethod {
                val profile = globalRewriteProfile() ?: return@afterHookedMethod
                val managerRef = WeakReference(thisObject)
                val provider = providerNameOf(thisObject)
                mainHandler.postDelayed({
                    val manager = managerRef.get() ?: return@postDelayed
                    injectProviderLocation(manager, profile, provider, classLoader)
                }, PROACTIVE_UPDATES_DELAY_MS)
            })
        }

        // Registration 激活时也会尝试交付 last location；在 Registration 层再补一刀。
        providerManagerClass.declaredClasses
            .filter { it.simpleName.contains("Registration") }
            .forEach { registrationClass ->
                if (!hookedClasses.add(registrationClass)) return@forEach
                listOf("onActive", "onListenerRegister", "deliverLastLocation").forEach { methodName ->
                    HookBridge.hookAllMethods(registrationClass, methodName, afterHookedMethod {
                        val profile = profileForReceiver(thisObject) ?: globalRewriteProfile()
                            ?: return@afterHookedMethod
                        val provider = providerNameOf(thisObject)
                        scheduleProactiveUpdateDelivery(
                            target = thisObject,
                            profile = profile,
                            provider = provider,
                            packageName = LocationCallerResolver.packageNameFromObject(thisObject)
                        )
                    })
                }
                registrationClass.declaredMethods
                    .filter { method ->
                        method.parameterTypes.any {
                            it == Location::class.java || it.name.endsWith(".LocationResult")
                        } && (
                            method.name.contains("location", ignoreCase = true) ||
                                method.name.contains("deliver", ignoreCase = true) ||
                                method.name.contains("accept", ignoreCase = true)
                            )
                    }
                    .forEach { method ->
                        HookBridge.hookMethod(method, beforeHookedMethod {
                            val profile = profileForReceiver(thisObject) ?: globalRewriteProfile()
                                ?: return@beforeHookedMethod
                            args.forEachIndexed { index, arg ->
                                args[index] = rewriteLocationContainer(arg, profile)
                            }
                        })
                    }
            }

        HookLog.i(HookLog.Module.LOCATION, "hooked LocationProviderManager last/register inject")
    }

    private fun providerNameOf(managerOrRegistration: Any?): String {
        managerOrRegistration ?: return LocationManager.GPS_PROVIDER
        listOf("mName", "mProviderName", "name", "mProvider").forEach { field ->
            (getFieldValue(managerOrRegistration, field) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        listOf("getName", "getProviderName", "getProvider").forEach { method ->
            runCatching {
                (HookBridge.callMethod(managerOrRegistration, method) as? String)
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }
        return LocationManager.GPS_PROVIDER
    }

    private fun injectProviderLocation(
        manager: Any,
        profile: LocationProfile,
        provider: String,
        classLoader: ClassLoader?
    ) {
        val location = fakeLocation(profile, provider)
        val frameworkResult = createLocationResult(
            className = "android.location.LocationResult",
            classLoader = classLoader ?: manager.javaClass.classLoader,
            location = location
        )

        val delivered = runCatching {
            if (frameworkResult != null) {
                HookBridge.callMethod(manager, "onReportLocation", frameworkResult)
                true
            } else {
                false
            }
        }.getOrDefault(false) || runCatching {
            HookBridge.callMethod(manager, "onReportLocation", location)
            true
        }.getOrDefault(false) || runCatching {
            if (frameworkResult != null) {
                HookBridge.callMethod(manager, "reportLocation", frameworkResult)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (delivered) {
            HookLog.d(
                HookLog.Module.LOCATION,
                "injected provider location provider=$provider via LocationProviderManager"
            )
        }
    }

    private fun hookAndroidFusedProvider(classLoader: ClassLoader) {
        val fusedProviderClass = HookBridge.findClassIfExists(
            "com.android.location.fused.FusedLocationProvider",
            classLoader
        ) ?: return
        if (!hookedClasses.add(fusedProviderClass)) return

        HookBridge.hookAllMethods(fusedProviderClass, "chooseBestLocation", afterHookedMethod {
            val profile = globalRewriteProfile() ?: return@afterHookedMethod
            if (result is Location) {
                result = (result as Location).spoofed(profile)
            }
        })

        HookBridge.hookAllMethods(fusedProviderClass, "reportBestLocationLocked", beforeHookedMethod {
            val profile = globalRewriteProfile() ?: return@beforeHookedMethod
            args.forEachIndexed { index, arg ->
                args[index] = rewriteLocationContainer(arg, profile)
            }
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked Android fused provider")
    }

    private fun hookFrameworkLocationManagerClient(classLoader: ClassLoader) {
        val locationManagerClass = HookBridge.findClassIfExists("android.location.LocationManager", classLoader)
            ?: return
        if (hookedClasses.add(locationManagerClass)) {
            HookBridge.hookAllMethods(locationManagerClass, "getLastKnownLocation", afterHookedMethod {
                val profile = globalRewriteProfile() ?: return@afterHookedMethod
                if (result is Location) {
                    result = (result as Location).spoofed(profile)
                }
            })
        }

        listOf(
            "android.location.LocationManager\$GetCurrentLocationTransport" to "onLocation",
            "android.location.LocationManager\$LocationListenerTransport" to "onLocationChanged",
            "android.location.LocationManager\$BatchedLocationCallbackWrapper" to "onLocationChanged"
        ).forEach { (className, methodName) ->
            val transportClass = HookBridge.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(transportClass)) return@forEach
            HookBridge.hookAllMethods(transportClass, methodName, beforeHookedMethod {
                val profile = globalRewriteProfile() ?: return@beforeHookedMethod
                args.forEachIndexed { index, arg ->
                    args[index] = rewriteLocationContainer(arg, profile)
                }
            })
        }
    }

    private fun hookFrameworkLocationResult(classLoader: ClassLoader) {
        hookLocationResultClass(
            HookBridge.findClassIfExists("android.location.LocationResult", classLoader)
                ?: return
        )
    }

    private fun hookGoogleLocationResult(classLoader: ClassLoader) {
        val resultClass = HookBridge.findClassIfExists(
            "com.google.android.gms.location.LocationResult",
            classLoader
        ) ?: return
        hookLocationResultClass(resultClass)

        HookBridge.hookAllMethods(resultClass, "extractResult", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            result = rewriteLocationContainer(result, profile)
        })
    }

    private fun hookGoogleLocationCallbacks(classLoader: ClassLoader) {
        val callbackClass = HookBridge.findClassIfExists(
            "com.google.android.gms.location.LocationCallback",
            classLoader
        )
        if (callbackClass != null && hookedClasses.add(callbackClass)) {
            HookBridge.hookAllMethods(callbackClass, "onLocationResult", beforeHookedMethod {
                val profile = profileForReceiver(thisObject) ?: resolveProfile() ?: return@beforeHookedMethod
                args[0] = rewriteLocationContainer(args[0], profile)
            })
        }

        val listenerClass = HookBridge.findClassIfExists(
            "com.google.android.gms.location.LocationListener",
            classLoader
        )
        if (listenerClass != null && hookedClasses.add(listenerClass)) {
            HookBridge.hookAllMethods(listenerClass, "onLocationChanged", beforeHookedMethod {
                val profile = profileForReceiver(thisObject) ?: resolveProfile() ?: return@beforeHookedMethod
                if (args[0] is Location) {
                    args[0] = (args[0] as Location).spoofed(profile)
                }
            })
        }

        val availabilityClass = HookBridge.findClassIfExists(
            "com.google.android.gms.location.LocationAvailability",
            classLoader
        )
        if (availabilityClass != null && hookedClasses.add(availabilityClass)) {
            HookBridge.hookAllMethods(availabilityClass, "isLocationAvailable", beforeHookedMethod {
                if (resolveProfile() != null) result = true
            })
        }
    }

    private fun hookGoogleFusedClient(classLoader: ClassLoader) {
        val clientClass = HookBridge.findClassIfExists(
            "com.google.android.gms.location.FusedLocationProviderClient",
            classLoader
        ) ?: return
        if (!hookedClasses.add(clientClass)) return

        listOf("getLastLocation", "getCurrentLocation").forEach { methodName ->
            HookBridge.hookAllMethods(clientClass, methodName, afterHookedMethod {
                hookConcreteGoogleTask(result)
            })
        }

        HookBridge.hookAllMethods(clientClass, "requestLocationUpdates", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            args.forEach { arg ->
                if (isLocationDeliveryTarget(arg)) {
                    scheduleProactiveUpdateDelivery(
                        target = arg,
                        profile = profile,
                        provider = LocationManager.FUSED_PROVIDER,
                        packageName = activeDeliveryPackage.get()
                    )
                }
            }
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked Google FusedLocationProviderClient")
    }

    private fun hookGmsInternalLocationService(classLoader: ClassLoader) {
        // GMS location service implementation and common internal classes
        val serviceClasses = listOf(
            "com.google.android.gms.location.internal.zzas",
            "com.google.android.gms.location.internal.zzat",
            "com.google.android.gms.location.internal.zzau",
            "com.google.android.gms.location.internal.LocationReceiver",
            "com.google.android.gms.location.internal.zzm",
            "com.google.android.gms.location.internal.GoogleLocationManagerService",
            "com.google.android.gms.location.internal.LocationRequestInternal",
            "com.google.android.gms.location.internal.LocationRequestUpdateData"
        )
        
        serviceClasses.forEach { className ->
            val clazz = HookBridge.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(clazz)) return@forEach
            
            clazz.declaredMethods.forEach { method ->
                // Hook methods that handle LocationResult or Location
                if (method.parameterTypes.any { it.name.endsWith(".LocationResult") || it == Location::class.java }) {
                    HookBridge.hookMethod(method, beforeHookedMethod {
                        val profile = profileForReceiver(thisObject) ?: profileForArgs(args) ?: resolveProfile() ?: return@beforeHookedMethod
                        args.forEachIndexed { index, arg ->
                            val rewritten = rewriteLocationContainer(arg, profile)
                            if (rewritten != null && rewritten !== arg) {
                                args[index] = rewritten
                                HookLog.d(HookLog.Module.LOCATION, "spoofed GMS internal method ${method.name} arg $index")
                            }
                        }
                    })
                }
            }

            if (className.endsWith("LocationRequestInternal")) {
                HookBridge.hookAllMethods(clazz, "writeToParcel", beforeHookedMethod {
                    val profile = profileForReceiver(thisObject) ?: resolveProfile() ?: return@beforeHookedMethod
                    val locationRequest = getFieldValue(thisObject, "a") as? Location
                    if (locationRequest != null) {
                        locationRequest.spoofInPlace(profile)
                    }
                })
            }
        }
        
        // Hook FusedLocationProviderApi implementations
        val apiImpls = listOf(
            "com.google.android.gms.location.internal.zzv",
            "com.google.android.gms.location.internal.zzay",
            "com.google.android.gms.location.internal.zzbc"
        )
        apiImpls.forEach { className ->
            val clazz = HookBridge.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(clazz)) return@forEach
            
            HookBridge.hookAllMethods(clazz, "getLastLocation", afterHookedMethod {
                val profile = profileForArgs(args) ?: resolveProfile() ?: return@afterHookedMethod
                result = rewriteLocationContainer(result, profile)
                if (result != null) {
                    HookLog.d(HookLog.Module.LOCATION, "spoofed ${clazz.name}.getLastLocation result")
                }
            })

            HookBridge.hookAllMethods(clazz, "requestLocationUpdates", hookedMethod(
                before = {
                    val profile = profileForArgs(args) ?: resolveProfile() ?: return@hookedMethod
                    args.forEachIndexed { index, arg ->
                        val rewritten = rewriteLocationContainer(arg, profile)
                        if (rewritten != null && rewritten !== arg) {
                            args[index] = rewritten
                            HookLog.d(HookLog.Module.LOCATION, "spoofed ${clazz.name}.requestLocationUpdates arg $index")
                        }
                    }
                },
                after = {
                    val profile = profileForArgs(args) ?: resolveProfile() ?: return@hookedMethod
                    val pkg = LocationCallerResolver.callerFromArgs(args)
                        ?: LocationCallerResolver.packageNameForUid(Binder.getCallingUid())
                    args.forEach { arg ->
                        if (isLocationDeliveryTarget(arg)) {
                            scheduleProactiveUpdateDelivery(
                                target = arg,
                                profile = profile,
                                provider = LocationManager.FUSED_PROVIDER,
                                packageName = pkg
                            )
                        }
                    }
                }
            ))
        }
    }

    private fun hookLocationResultClass(resultClass: Class<*>) {
        if (!hookedClasses.add(resultClass)) return

        listOf("asList", "getLocations").forEach { methodName ->
            HookBridge.hookAllMethods(resultClass, methodName, afterHookedMethod {
                val profile = resolveProfile() ?: return@afterHookedMethod
                result = rewriteLocationContainer(result, profile)
            })
        }

        HookBridge.hookAllMethods(resultClass, "getLastLocation", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            if (result is Location) {
                result = (result as Location).spoofed(profile)
            }
        })

        HookBridge.hookAllMethods(resultClass, "writeToParcel", beforeHookedMethod {
            val profile = resolveProfile() ?: return@beforeHookedMethod
            rewriteLocationResult(thisObject, profile)
        })

        runCatching {
            val creator = HookBridge.getStaticObjectField(resultClass, "CREATOR")
            if (creator != null && hookedClasses.add(creator.javaClass)) {
                HookBridge.hookAllMethods(creator.javaClass, "createFromParcel", afterHookedMethod {
                    val profile = resolveProfile() ?: return@afterHookedMethod
                    rewriteLocationContainer(result, profile)
                })
            }
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked LocationResult ${resultClass.name}")
    }

    private fun hookGoogleTasks(classLoader: ClassLoader) {
        val taskClass = HookBridge.findClassIfExists("com.google.android.gms.tasks.Task", classLoader)
            ?: return
        if (!hookedClasses.add(taskClass)) return

        HookBridge.hookAllMethods(taskClass, "getResult", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            result = rewriteLocationContainer(result, profile)
        })

        listOf("addOnSuccessListener", "addOnCompleteListener").forEach { methodName ->
            HookBridge.hookAllMethods(taskClass, methodName, hookedMethod(
                before = {
                    if (resolveProfile() == null) return@hookedMethod
                    args.forEach { arg ->
                        hookGoogleTaskListener(arg)
                    }
                },
                after = {
                    if (resolveProfile() == null) return@hookedMethod
                    hookConcreteGoogleTask(thisObject)
                    hookConcreteGoogleTask(result)
                }
            ))
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked Google Task base")
    }

    private fun hookConcreteGoogleTask(task: Any?) {
        task ?: return
        val taskClass = task.javaClass
        if (!taskClass.name.startsWith("com.google.android.gms.tasks.")) return
        if (!hookedClasses.add(taskClass)) return

        HookBridge.hookAllMethods(taskClass, "getResult", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            result = rewriteLocationContainer(result, profile)
        })

        listOf("addOnSuccessListener", "addOnCompleteListener").forEach { methodName ->
            HookBridge.hookAllMethods(taskClass, methodName, beforeHookedMethod {
                if (resolveProfile() == null) return@beforeHookedMethod
                args.forEach { arg ->
                    hookGoogleTaskListener(arg)
                }
            })
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked concrete Google Task ${taskClass.name}")
    }

    private fun hookGoogleTaskListener(listener: Any?) {
        listener ?: return
        val listenerClass = listener.javaClass
        if (!listenerClass.name.startsWith(BuildConfig.APPLICATION_ID) &&
            !listenerClass.name.startsWith("com.google.android.gms.")
        ) {
            return
        }
        if (!hookedClasses.add(listenerClass)) return

        HookBridge.hookAllMethods(listenerClass, "onSuccess", beforeHookedMethod {
            val profile = resolveProfile() ?: return@beforeHookedMethod
            args.forEachIndexed { index, arg ->
                args[index] = rewriteLocationContainer(arg, profile)
            }
        })

        HookBridge.hookAllMethods(listenerClass, "onComplete", beforeHookedMethod {
            if (resolveProfile() == null) return@beforeHookedMethod
            args.forEach { arg ->
                hookConcreteGoogleTask(arg)
            }
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked Google Task listener ${listenerClass.name}")
    }

    private fun hookGnssReports(classLoader: ClassLoader) {
        GNSS_CLASSES
            .mapNotNull { HookBridge.findClassIfExists(it, classLoader) }
            .forEach { targetClass ->
                if (!hookedClasses.add(targetClass)) return@forEach

                listOf("reportLocation", "reportLocationBatch").forEach { methodName ->
                    HookBridge.hookAllMethods(targetClass, methodName, beforeHookedMethod {
                        val profile = globalRewriteProfile() ?: return@beforeHookedMethod
                        args.forEachIndexed { index, arg ->
                            args[index] = rewriteLocationContainer(arg, profile)
                        }
                    })
                }

                HookBridge.hookAllMethods(targetClass, "reportNmea", beforeHookedMethod {
                    val profile = globalRewriteProfile() ?: return@beforeHookedMethod
                    args.forEachIndexed { index, arg ->
                        if (arg is String) {
                            args[index] = spoofNmea(arg, profile)
                        }
                    }
                })

                listOf(
                    "reportMeasurementData",
                    "reportAntennaInfo",
                    "reportNavigationMessage"
                ).forEach { methodName ->
                    HookBridge.hookAllMethods(targetClass, methodName, beforeHookedMethod {
                        if (globalRewriteProfile() == null) return@beforeHookedMethod
                        result = null
                    })
                }

                HookLog.i(HookLog.Module.LOCATION, "hooked GNSS class ${targetClass.name}")
            }
    }

    private fun Class<*>.hasLocationCallbackMethod(): Boolean {
        return findLocationCallbackMethod() != null
    }

    private fun Any.dispatchLocation(location: Location): Boolean {
        val method = javaClass.findLocationCallbackMethod() ?: return false
        method.isAccessible = true
        val args = method.parameterTypes.map { type ->
            when {
                type == Location::class.java -> location
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }.toTypedArray()

        return runCatching {
            method.invoke(this, *args)
            true
        }.onFailure {
            HookLog.d(HookLog.Module.LOCATION, "failed to dispatch current location callback: ${it.message}")
        }.getOrDefault(false)
    }

    private fun Class<*>.findLocationCallbackMethod(): Method? {
        var current: Class<*>? = this
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                method.parameterTypes.any { it == Location::class.java } &&
                    (method.name == "onLocation" || method.name.contains("location", ignoreCase = true))
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

}
