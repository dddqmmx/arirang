package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.getFieldValue

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
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
        private const val DEBUG_HARDCODED_CONFIG = false
        private const val DEBUG_PACKAGE_NAME = "asia.nana7mi.arirang.selfcheck"
        private const val DEBUG_LATITUDE = 39.019444
        private const val DEBUG_LONGITUDE = 125.738052
        private const val DEBUG_PACKAGE_LATITUDE = 35.681236
        private const val DEBUG_PACKAGE_LONGITUDE = 139.767125
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L

        private val GNSS_CLASSES = arrayOf(
            "com.android.server.location.gnss.hal.GnssNative",
            "com.android.server.location.gnss.GnssLocationProvider",
            "com.android.server.location.gnss.GnssLocationProviderImpl"
        )

        private val debugConfig = LocationHookConfig(
            enabled = true,
            defaultProfile = LocationProfile(
                latitude = DEBUG_LATITUDE,
                longitude = DEBUG_LONGITUDE
            ),
            perPackage = mapOf(
                DEBUG_PACKAGE_NAME to LocationProfile(
                    latitude = DEBUG_PACKAGE_LATITUDE,
                    longitude = DEBUG_PACKAGE_LONGITUDE
                )
            )
        )
    }

    private val hookedClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    // GMS 融合定位「按应用」修复所需的两张状态：
    // receiverPackages —— 注册时（getCallingUid 仍指向真正请求方）把投递用的 callback/listener
    //   binder 绑定到包名；key 为 GMS 持有的 BinderProxy，使用 WeakHashMap，GMS 注销后自动回收。
    // activeDeliveryPackage —— 异步投递时按 binder 反查到的包名写入此 thread-local，供 resolveProfile() 消费。
    private val receiverPackages = Collections.synchronizedMap(WeakHashMap<IBinder, String>())
    private val activeDeliveryPackage = ThreadLocal<String?>()

    @Volatile
    private var gmsContext: Context? = null

    private val configFile = HookConfigFile(
        configName = "location",
        prefsName = LocationConfigPrefs.PREFS_NAME,
        defaultValue = LocationHookConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            val context = gmsContext
            ArirangClient.readConfigSnapshot(
                configName = "location",
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
                "com.google.android.gms", BuildConfig.APPLICATION_ID -> {
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
        val applicationClass = XposedHelpers.findClassIfExists("android.app.Application", classLoader)
            ?: Application::class.java
        if (!hookedClasses.add(applicationClass)) return

        XposedBridge.hookAllMethods(applicationClass, "onCreate", afterHookedMethod {
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
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(clazz)) return@forEach
            XposedBridge.hookAllConstructors(clazz, afterHookedMethod {
                captureReceiverBinding(thisObject)
            })
            HookLog.i(HookLog.Module.LOCATION, "hooked GMS receiver binding via ${clazz.simpleName}")
        }
    }

    private fun captureReceiverBinding(holder: Any?) {
        holder ?: return
        val pkg = callingPackageForBinding()

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
                    (method.invoke(holder) as? IInterface)?.let { hookDeliveryProxyClass(it.javaClass) }
                }
            }
        }
    }

    private fun bindBinder(binder: IBinder, pkg: String?) {
        pkg ?: return
        receiverPackages[binder] = pkg
        HookLog.d(HookLog.Module.LOCATION, "bound GMS delivery binder -> $pkg")
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

            XposedBridge.hookMethod(method, hookedMethod(
                before = {
                    // 按投递目标 binder 反查包名，写入 thread-local（供同线程内嵌的 writeToParcel 等改写点消费），
                    // 并就地改写入参，双保险。
                    val pkg = (thisObject as? IInterface)?.asBinder()?.let { receiverPackages[it] }
                    activeDeliveryPackage.set(pkg)
                    resolveProfile(pkg)?.let { profile ->
                        args.forEachIndexed { index, arg ->
                            rewriteLocationContainer(arg, profile)?.let { args[index] = it }
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
        if (DEBUG_HARDCODED_CONFIG) return debugConfig
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
            HookLog.d(HookLog.Module.LOCATION, "resolved profile for pkg=$pkg uid=$callingUid: lat=${profile.latitude} lon=${profile.longitude}")
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

        XposedBridge.hookAllMethods(Location::class.java, "getLatitude", beforeHookedMethod {
            resolveProfile()?.let { result = it.latitude }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getLongitude", beforeHookedMethod {
            resolveProfile()?.let { result = it.longitude }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getAltitude", beforeHookedMethod {
            resolveProfile()?.let { result = it.altitude }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getAccuracy", beforeHookedMethod {
            resolveProfile()?.let { result = it.accuracy }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getSpeed", beforeHookedMethod {
            resolveProfile()?.let { result = it.speed }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getBearing", beforeHookedMethod {
            resolveProfile()?.let { result = it.bearing }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getProvider", beforeHookedMethod {
            if (resolveProfile() != null) result = LocationManager.GPS_PROVIDER
        })
        XposedBridge.hookAllMethods(Location::class.java, "getTime", beforeHookedMethod {
            if (resolveProfile() != null) result = System.currentTimeMillis()
        })
        XposedBridge.hookAllMethods(Location::class.java, "getElapsedRealtimeNanos", beforeHookedMethod {
            if (resolveProfile() != null) result = SystemClock.elapsedRealtimeNanos()
        })

        runCatching {
            XposedBridge.hookAllMethods(Location::class.java, "isFromMockProvider", beforeHookedMethod {
                if (resolveProfile() != null) result = false
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(Location::class.java, "isMock", beforeHookedMethod {
                if (resolveProfile() != null) result = false
            })
        }

        // 关键修复：Hook writeToParcel 确保跨进程传输前数据已被修改
        XposedBridge.hookAllMethods(Location::class.java, "writeToParcel", beforeHookedMethod {
            val profile = resolveProfile() ?: return@beforeHookedMethod
            (thisObject as Location).spoofInPlace(profile)
        })

        runCatching {
            val creator = XposedHelpers.getStaticObjectField(Location::class.java, "CREATOR")
            if (creator != null && hookedClasses.add(creator.javaClass)) {
                XposedBridge.hookAllMethods(creator.javaClass, "createFromParcel", afterHookedMethod {
                    val profile = resolveProfile() ?: return@afterHookedMethod
                    (result as? Location)?.spoofInPlace(profile)
                })
            }
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked Location accessors and CREATOR")
    }

    private fun hookLocationManagerService(classLoader: ClassLoader) {
        val lmsClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.LocationManagerService",
            classLoader
        ) ?: return
        if (!hookedClasses.add(lmsClass)) return
        hookLocationReceiverDelivery(lmsClass)

        XposedBridge.hookAllMethods(lmsClass, "getLastLocation", afterHookedMethod {
            val profile = profileForArgs(args) ?: return@afterHookedMethod
            val provider = LocationCallerResolver.providerFromArgs(args)
            result = fakeLocation(profile, provider)
            HookLog.d(
                HookLog.Module.LOCATION,
                "spoofed getLastLocation provider=$provider caller=${LocationCallerResolver.callerFromArgs(args)}"
            )
        })

        XposedBridge.hookAllMethods(lmsClass, "getCurrentLocation", beforeHookedMethod {
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

        listOf("requestLocationUpdates", "requestLocationUpdatesWithPackageName").forEach { methodName ->
            XposedBridge.hookAllMethods(lmsClass, methodName, beforeHookedMethod {
                val profile = profileForArgs(args) ?: return@beforeHookedMethod
                args.forEachIndexed { index, arg ->
                    if (arg is Location) {
                        args[index] = arg.spoofed(profile)
                    }
                }
            })
        }
    }

    private fun hookLocationReceiverDelivery(lmsClass: Class<*>) {
        val receiverClasses = lmsClass.declaredClasses
            .filter { it.simpleName == "Receiver" || it.name.endsWith("\$Receiver") }

        receiverClasses.forEach { receiverClass ->
            if (!hookedClasses.add(receiverClass)) return@forEach
            receiverClass.declaredMethods
                .filter { method ->
                    method.name == "callLocationChangedLocked" &&
                        method.parameterTypes.any { it == Location::class.java || it.name.endsWith(".LocationResult") }
                }
                .forEach { method ->
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val profile = profileForReceiver(thisObject) ?: return@beforeHookedMethod
                        args.forEachIndexed { index, arg ->
                            args[index] = rewriteLocationContainer(arg, profile)
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
        val providerManagerClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.provider.LocationProviderManager",
            classLoader
        ) ?: return
        if (!hookedClasses.add(providerManagerClass)) return

        XposedBridge.hookAllMethods(providerManagerClass, "onReportLocation", beforeHookedMethod {
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
    }

    private fun hookAndroidFusedProvider(classLoader: ClassLoader) {
        val fusedProviderClass = XposedHelpers.findClassIfExists(
            "com.android.location.fused.FusedLocationProvider",
            classLoader
        ) ?: return
        if (!hookedClasses.add(fusedProviderClass)) return

        XposedBridge.hookAllMethods(fusedProviderClass, "chooseBestLocation", afterHookedMethod {
            val profile = globalRewriteProfile() ?: return@afterHookedMethod
            if (result is Location) {
                result = (result as Location).spoofed(profile)
            }
        })

        XposedBridge.hookAllMethods(fusedProviderClass, "reportBestLocationLocked", beforeHookedMethod {
            val profile = globalRewriteProfile() ?: return@beforeHookedMethod
            args.forEachIndexed { index, arg ->
                args[index] = rewriteLocationContainer(arg, profile)
            }
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked Android fused provider")
    }

    private fun hookFrameworkLocationManagerClient(classLoader: ClassLoader) {
        val locationManagerClass = XposedHelpers.findClassIfExists("android.location.LocationManager", classLoader)
            ?: return
        if (hookedClasses.add(locationManagerClass)) {
            XposedBridge.hookAllMethods(locationManagerClass, "getLastKnownLocation", afterHookedMethod {
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
            val transportClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(transportClass)) return@forEach
            XposedBridge.hookAllMethods(transportClass, methodName, beforeHookedMethod {
                val profile = globalRewriteProfile() ?: return@beforeHookedMethod
                args.forEachIndexed { index, arg ->
                    args[index] = rewriteLocationContainer(arg, profile)
                }
            })
        }
    }

    private fun hookFrameworkLocationResult(classLoader: ClassLoader) {
        hookLocationResultClass(
            XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                ?: return
        )
    }

    private fun hookGoogleLocationResult(classLoader: ClassLoader) {
        val resultClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.LocationResult",
            classLoader
        ) ?: return
        hookLocationResultClass(resultClass)

        XposedBridge.hookAllMethods(resultClass, "extractResult", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            result = rewriteLocationContainer(result, profile)
        })
    }

    private fun hookGoogleLocationCallbacks(classLoader: ClassLoader) {
        val callbackClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.LocationCallback",
            classLoader
        )
        if (callbackClass != null && hookedClasses.add(callbackClass)) {
            XposedBridge.hookAllMethods(callbackClass, "onLocationResult", beforeHookedMethod {
                val profile = profileForReceiver(thisObject) ?: resolveProfile() ?: return@beforeHookedMethod
                args[0] = rewriteLocationContainer(args[0], profile)
            })
        }

        val listenerClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.LocationListener",
            classLoader
        )
        if (listenerClass != null && hookedClasses.add(listenerClass)) {
            XposedBridge.hookAllMethods(listenerClass, "onLocationChanged", beforeHookedMethod {
                val profile = profileForReceiver(thisObject) ?: resolveProfile() ?: return@beforeHookedMethod
                if (args[0] is Location) {
                    args[0] = (args[0] as Location).spoofed(profile)
                }
            })
        }

        val availabilityClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.LocationAvailability",
            classLoader
        )
        if (availabilityClass != null && hookedClasses.add(availabilityClass)) {
            XposedBridge.hookAllMethods(availabilityClass, "isLocationAvailable", beforeHookedMethod {
                if (resolveProfile() != null) result = true
            })
        }
    }

    private fun hookGoogleFusedClient(classLoader: ClassLoader) {
        val clientClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.FusedLocationProviderClient",
            classLoader
        ) ?: return
        if (!hookedClasses.add(clientClass)) return

        listOf("getLastLocation", "getCurrentLocation").forEach { methodName ->
            XposedBridge.hookAllMethods(clientClass, methodName, afterHookedMethod {
                hookConcreteGoogleTask(result)
            })
        }

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
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(clazz)) return@forEach
            
            clazz.declaredMethods.forEach { method ->
                // Hook methods that handle LocationResult or Location
                if (method.parameterTypes.any { it.name.endsWith(".LocationResult") || it == Location::class.java }) {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
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
                XposedBridge.hookAllMethods(clazz, "writeToParcel", beforeHookedMethod {
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
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            if (!hookedClasses.add(clazz)) return@forEach
            
            XposedBridge.hookAllMethods(clazz, "getLastLocation", afterHookedMethod {
                val profile = profileForArgs(args) ?: resolveProfile() ?: return@afterHookedMethod
                result = rewriteLocationContainer(result, profile)
                if (result != null) {
                    HookLog.d(HookLog.Module.LOCATION, "spoofed ${clazz.name}.getLastLocation result")
                }
            })

            XposedBridge.hookAllMethods(clazz, "requestLocationUpdates", beforeHookedMethod {
                val profile = profileForArgs(args) ?: resolveProfile() ?: return@beforeHookedMethod
                args.forEachIndexed { index, arg ->
                    val rewritten = rewriteLocationContainer(arg, profile)
                    if (rewritten != null && rewritten !== arg) {
                        args[index] = rewritten
                        HookLog.d(HookLog.Module.LOCATION, "spoofed ${clazz.name}.requestLocationUpdates arg $index")
                    }
                }
            })
        }
    }

    private fun hookLocationResultClass(resultClass: Class<*>) {
        if (!hookedClasses.add(resultClass)) return

        listOf("asList", "getLocations").forEach { methodName ->
            XposedBridge.hookAllMethods(resultClass, methodName, afterHookedMethod {
                val profile = resolveProfile() ?: return@afterHookedMethod
                result = rewriteLocationContainer(result, profile)
            })
        }

        XposedBridge.hookAllMethods(resultClass, "getLastLocation", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            if (result is Location) {
                result = (result as Location).spoofed(profile)
            }
        })

        XposedBridge.hookAllMethods(resultClass, "writeToParcel", beforeHookedMethod {
            val profile = resolveProfile() ?: return@beforeHookedMethod
            rewriteLocationResult(thisObject, profile)
        })

        runCatching {
            val creator = XposedHelpers.getStaticObjectField(resultClass, "CREATOR")
            if (creator != null && hookedClasses.add(creator.javaClass)) {
                XposedBridge.hookAllMethods(creator.javaClass, "createFromParcel", afterHookedMethod {
                    val profile = resolveProfile() ?: return@afterHookedMethod
                    rewriteLocationContainer(result, profile)
                })
            }
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked LocationResult ${resultClass.name}")
    }

    private fun hookGoogleTasks(classLoader: ClassLoader) {
        val taskClass = XposedHelpers.findClassIfExists("com.google.android.gms.tasks.Task", classLoader)
            ?: return
        if (!hookedClasses.add(taskClass)) return

        XposedBridge.hookAllMethods(taskClass, "getResult", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            result = rewriteLocationContainer(result, profile)
        })

        listOf("addOnSuccessListener", "addOnCompleteListener").forEach { methodName ->
            XposedBridge.hookAllMethods(taskClass, methodName, hookedMethod(
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

        XposedBridge.hookAllMethods(taskClass, "getResult", afterHookedMethod {
            val profile = resolveProfile() ?: return@afterHookedMethod
            result = rewriteLocationContainer(result, profile)
        })

        listOf("addOnSuccessListener", "addOnCompleteListener").forEach { methodName ->
            XposedBridge.hookAllMethods(taskClass, methodName, beforeHookedMethod {
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

        XposedBridge.hookAllMethods(listenerClass, "onSuccess", beforeHookedMethod {
            val profile = resolveProfile() ?: return@beforeHookedMethod
            args.forEachIndexed { index, arg ->
                args[index] = rewriteLocationContainer(arg, profile)
            }
        })

        XposedBridge.hookAllMethods(listenerClass, "onComplete", beforeHookedMethod {
            if (resolveProfile() == null) return@beforeHookedMethod
            args.forEach { arg ->
                hookConcreteGoogleTask(arg)
            }
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked Google Task listener ${listenerClass.name}")
    }

    private fun hookGnssReports(classLoader: ClassLoader) {
        GNSS_CLASSES
            .mapNotNull { XposedHelpers.findClassIfExists(it, classLoader) }
            .forEach { targetClass ->
                if (!hookedClasses.add(targetClass)) return@forEach

                listOf("reportLocation", "reportLocationBatch").forEach { methodName ->
                    XposedBridge.hookAllMethods(targetClass, methodName, beforeHookedMethod {
                        val profile = globalRewriteProfile() ?: return@beforeHookedMethod
                        args.forEachIndexed { index, arg ->
                            args[index] = rewriteLocationContainer(arg, profile)
                        }
                    })
                }

                XposedBridge.hookAllMethods(targetClass, "reportNmea", beforeHookedMethod {
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
                    XposedBridge.hookAllMethods(targetClass, methodName, beforeHookedMethod {
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
