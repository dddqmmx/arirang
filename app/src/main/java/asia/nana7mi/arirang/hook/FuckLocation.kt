package asia.nana7mi.arirang.hook

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
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
        private const val MOCK_VERTICAL_ACCURACY = 2.0f
        private const val MOCK_SPEED_ACCURACY = 0.1f
        private const val MOCK_BEARING_ACCURACY = 0.5f
        private const val MOCK_HDOP = 0.9f
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L

        private val GNSS_CLASSES = arrayOf(
            "com.android.server.location.gnss.hal.GnssNative",
            "com.android.server.location.gnss.GnssLocationProvider",
            "com.android.server.location.gnss.GnssLocationProviderImpl"
        )

        private val debugConfig = HookLocationConfig(
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

    private data class HookLocationConfig(
        val enabled: Boolean = false,
        val defaultProfile: LocationProfile = LocationProfile(),
        val perPackage: Map<String, LocationProfile> = emptyMap()
    )

    private data class LocationProfile(
        val enabled: Boolean = true,
        val latitude: Double = LocationConfigPrefs.DEFAULT_LATITUDE,
        val longitude: Double = LocationConfigPrefs.DEFAULT_LONGITUDE,
        val altitude: Double = LocationConfigPrefs.DEFAULT_ALTITUDE,
        val accuracy: Float = LocationConfigPrefs.DEFAULT_ACCURACY,
        val speed: Float = LocationConfigPrefs.DEFAULT_SPEED,
        val bearing: Float = LocationConfigPrefs.DEFAULT_BEARING,
        val satellites: Int = LocationConfigPrefs.DEFAULT_SATELLITES
    )

    private val configFile = HookConfigFile(
        configName = "location",
        prefsName = LocationConfigPrefs.PREFS_NAME,
        defaultValue = HookLocationConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            val context = gmsContext
            HookNotifyClient.readConfigSnapshot(
                configName = "location",
                force = force,
                allowBind = true,
                bindContext = context,
                bindCurrentUser = context != null,
                logName = "location"
            )
        },
        parseRealtimeSnapshot = ::parseConfigSnapshot,
        readStoredConfig = ::readConfigFromPrefs
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
            HookNotifyClient.autoBindCurrentUser(app)
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
        val pkg = packageNameForUid(Binder.getCallingUid()) ?: return null
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

    private fun currentConfig(): HookLocationConfig {
        if (DEBUG_HARDCODED_CONFIG) return debugConfig
        return configFile.current()
    }

    private fun parseConfigSnapshot(snapshot: String): HookLocationConfig? {
        return runCatching {
            val json = JSONObject(snapshot)
            HookLocationConfig(
                enabled = json.optBoolean(LocationConfigPrefs.KEY_ENABLED, false),
                defaultProfile = profileFromJson(json),
                perPackage = parsePackageProfiles(json)
            )
        }.onFailure {
            HookLog.w(HookLog.Module.LOCATION, "failed to parse location config snapshot: ${it.message}")
        }.getOrNull()
    }

    private fun readConfigFromPrefs(prefs: de.robv.android.xposed.XSharedPreferences): HookLocationConfig {
        return HookLocationConfig(
            enabled = prefs.getBoolean(LocationConfigPrefs.KEY_ENABLED, false),
            defaultProfile = LocationProfile(
                latitude = prefs.getString(LocationConfigPrefs.KEY_LATITUDE, null)?.toDoubleOrNull()
                    ?: LocationConfigPrefs.DEFAULT_LATITUDE,
                longitude = prefs.getString(LocationConfigPrefs.KEY_LONGITUDE, null)?.toDoubleOrNull()
                    ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
                altitude = prefs.getString(LocationConfigPrefs.KEY_ALTITUDE, null)?.toDoubleOrNull()
                    ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
                accuracy = prefs.getString(LocationConfigPrefs.KEY_ACCURACY, null)?.toFloatOrNull()
                    ?: LocationConfigPrefs.DEFAULT_ACCURACY,
                speed = prefs.getString(LocationConfigPrefs.KEY_SPEED, null)?.toFloatOrNull()
                    ?: LocationConfigPrefs.DEFAULT_SPEED,
                bearing = prefs.getString(LocationConfigPrefs.KEY_BEARING, null)?.toFloatOrNull()
                    ?: LocationConfigPrefs.DEFAULT_BEARING,
                satellites = prefs.getInt(LocationConfigPrefs.KEY_SATELLITES, LocationConfigPrefs.DEFAULT_SATELLITES)
                    .coerceIn(0, 64)
            ),
            perPackage = prefs.getString(LocationConfigPrefs.KEY_PER_PACKAGE, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { parsePackageProfiles(JSONObject(it)) }
                .orEmpty()
        )
    }

    private fun profileFromJson(json: JSONObject): LocationProfile {
        return LocationProfile(
            enabled = json.optBoolean(LocationConfigPrefs.KEY_ENABLED, true),
            latitude = json.optDouble(LocationConfigPrefs.KEY_LATITUDE, LocationConfigPrefs.DEFAULT_LATITUDE),
            longitude = json.optDouble(LocationConfigPrefs.KEY_LONGITUDE, LocationConfigPrefs.DEFAULT_LONGITUDE),
            altitude = json.optDouble(LocationConfigPrefs.KEY_ALTITUDE, LocationConfigPrefs.DEFAULT_ALTITUDE),
            accuracy = json.optDouble(LocationConfigPrefs.KEY_ACCURACY, LocationConfigPrefs.DEFAULT_ACCURACY.toDouble()).toFloat(),
            speed = json.optDouble(LocationConfigPrefs.KEY_SPEED, LocationConfigPrefs.DEFAULT_SPEED.toDouble()).toFloat(),
            bearing = json.optDouble(LocationConfigPrefs.KEY_BEARING, LocationConfigPrefs.DEFAULT_BEARING.toDouble()).toFloat(),
            satellites = json.optInt(LocationConfigPrefs.KEY_SATELLITES, LocationConfigPrefs.DEFAULT_SATELLITES)
                .coerceIn(0, 64)
        )
    }

    private fun parsePackageProfiles(json: JSONObject): Map<String, LocationProfile> {
        val root = json.optJSONObject("per_package")
            ?: json.optJSONObject("package_profiles")
            ?: return emptyMap()

        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val profileJson = root.optJSONObject(packageName) ?: continue
                put(packageName, profileFromJson(profileJson))
            }
        }
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
        val pkg = packageName ?: activeDeliveryPackage.get() ?: packageNameForUid(callingUid)

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
        return profileForPackage(callerFromArgs(args) ?: packageNameForUid(Binder.getCallingUid()))
    }

    private fun profileForReceiver(receiver: Any?): LocationProfile? {
        return profileForPackage(packageNameFromObject(receiver))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                XposedBridge.hookAllMethods(Location::class.java, "isMock", beforeHookedMethod {
                    if (resolveProfile() != null) result = false
                })
            }
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
            val provider = providerFromArgs(args)
            result = fakeLocation(profile, provider)
            HookLog.d(HookLog.Module.LOCATION, "spoofed getLastLocation provider=$provider caller=${callerFromArgs(args)}")
        })

        XposedBridge.hookAllMethods(lmsClass, "getCurrentLocation", beforeHookedMethod {
            val profile = profileForArgs(args) ?: return@beforeHookedMethod
            val callback = args.firstOrNull { it?.javaClass?.hasLocationCallbackMethod() == true } ?: return@beforeHookedMethod
            val location = fakeLocation(profile, providerFromArgs(args))
            if (callback.dispatchLocation(location)) {
                result = null
                HookLog.d(
                    HookLog.Module.LOCATION,
                    "spoofed getCurrentLocation provider=${location.provider} caller=${callerFromArgs(args)}"
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
                            "rewrote receiver delivery for ${packageNameFromObject(thisObject)} via ${method.name}"
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

    private fun rewriteLocationResult(locationResult: Any, profile: LocationProfile): Boolean {
        val clazz = locationResult.javaClass
        // 尝试常见的字段名
        val fields = listOf("mLocations", "zza", "zzb", "a", "b")
        for (fieldName in fields) {
            val field = XposedHelpers.findFieldIfExists(clazz, fieldName) ?: continue
            field.isAccessible = true
            val value = field.get(locationResult)
            if (value is List<*> || value is Array<*>) {
                val rewritten = rewriteLocationContainer(value, profile)
                if (rewritten != null) {
                    field.set(locationResult, rewritten)
                    return true
                }
            }
        }
        
        // 如果都没找到，遍历所有字段
        clazz.declaredFields.forEach { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(locationResult)
                if (value is List<*> || value is Array<*>) {
                    val rewritten = rewriteLocationContainer(value, profile)
                    if (rewritten != null) {
                        field.set(locationResult, rewritten)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun rewriteLocationContainer(value: Any?, profile: LocationProfile): Any? {
        if (value == null) return null
        return when (value) {
            is Location -> {
                value.spoofInPlace(profile)
                value
            }
            is Array<*> -> {
                value.forEach { item ->
                    if (item is Location) {
                        item.spoofInPlace(profile)
                    }
                }
                value
            }
            is List<*> -> {
                value.forEach { item ->
                    if (item is Location) {
                        item.spoofInPlace(profile)
                    }
                }
                value
            }
            else -> {
                val className = value.javaClass.name
                if (className.endsWith(".LocationResult")) {
                    rewriteLocationResult(value, profile)
                }
                value
            }
        }
    }

    private fun Location.spoofed(profile: LocationProfile): Location {
        return fakeLocation(profile, provider ?: LocationManager.GPS_PROVIDER, this)
    }

    private fun Location.spoofInPlace(profile: LocationProfile) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()

        latitude = profile.latitude
        longitude = profile.longitude
        altitude = profile.altitude
        speed = profile.speed
        bearing = profile.bearing
        accuracy = profile.accuracy
        time = now
        elapsedRealtimeNanos = elapsed

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            verticalAccuracyMeters = MOCK_VERTICAL_ACCURACY
            speedAccuracyMetersPerSecond = MOCK_SPEED_ACCURACY
            bearingAccuracyDegrees = MOCK_BEARING_ACCURACY
        }

        runCatching {
            XposedHelpers.callMethod(this, "setIsFromMockProvider", false)
        }

        val extras = Bundle(extras ?: Bundle())
        listOf("mockLocation", "is_mock", "portal.enable").forEach(extras::remove)
        extras.putBoolean("mockLocation", false)
        extras.putInt("satellites", profile.satellites)
        extras.putInt("visible_satellites", (profile.satellites + 4).coerceAtLeast(profile.satellites))
        extras.putFloat("hdop", MOCK_HDOP)
        extras.putDouble("altitude", profile.altitude)
        this.extras = extras
    }

    private fun fakeLocation(
        profile: LocationProfile,
        provider: String? = LocationManager.GPS_PROVIDER,
        source: Location? = null
    ): Location {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val location = source?.let(::Location) ?: Location(provider ?: LocationManager.GPS_PROVIDER)

        location.provider = provider ?: LocationManager.GPS_PROVIDER
        location.latitude = profile.latitude
        location.longitude = profile.longitude
        location.altitude = profile.altitude
        location.speed = profile.speed
        location.bearing = profile.bearing
        location.accuracy = profile.accuracy
        location.time = now
        location.elapsedRealtimeNanos = elapsed

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = MOCK_VERTICAL_ACCURACY
            location.speedAccuracyMetersPerSecond = MOCK_SPEED_ACCURACY
            location.bearingAccuracyDegrees = MOCK_BEARING_ACCURACY
        }

        runCatching {
            XposedHelpers.callMethod(location, "setIsFromMockProvider", false)
        }

        val extras = Bundle(location.extras ?: Bundle())
        listOf("mockLocation", "is_mock", "portal.enable").forEach(extras::remove)
        extras.putBoolean("mockLocation", false)
        extras.putInt("satellites", profile.satellites)
        extras.putInt("visible_satellites", (profile.satellites + 4).coerceAtLeast(profile.satellites))
        extras.putFloat("hdop", MOCK_HDOP)
        extras.putDouble("altitude", profile.altitude)
        location.extras = extras

        return location
    }

    private fun providerFromArgs(args: Array<Any?>): String {
        return args.firstOrNull { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER }
            as? String
            ?: LocationManager.GPS_PROVIDER
    }

    private fun callerFromArgs(args: Array<Any?>): String? {
        return args.asSequence()
            .mapNotNull { arg ->
                when (arg) {
                    is String -> arg.takeIf { it.isLikelyPackageName() }
                    else -> packageNameFromObject(arg)
                }
            }
            .firstOrNull()
            ?: args.filterIsInstance<String>()
                .lastOrNull { it.isLikelyPackageName() }
    }

    private fun String.isLikelyPackageName(): Boolean {
        return contains('.') &&
            this != LocationManager.GPS_PROVIDER &&
            this != LocationManager.NETWORK_PROVIDER &&
            this != LocationManager.FUSED_PROVIDER
    }

    private fun packageNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        return runCatching {
            HookNotifyClient.getSystemContext()
                ?.packageManager
                ?.getPackagesForUid(uid)
                ?.firstOrNull()
        }.getOrNull()
    }

    private fun packageNameFromObject(value: Any?): String? {
        value ?: return null
        if (value is String) return value.takeIf { it.isLikelyPackageName() }

        directPackageNameFromObject(value)?.let { return it }
        packageNameFromWorkSource(value)?.let { return it }

        val className = value.javaClass.name
        if (className == "com.google.android.gms.common.internal.ClientIdentity") {
            (getFieldValue(value, "b") as? String)?.takeIf { it.isLikelyPackageName() }?.let { return it }
        }

        if (className == "com.google.android.gms.location.internal.LocationRequestInternal") {
            // LocationRequestInternal usually contains a list of ClientIdentities
            listOf("mClientIdentities", "clients", "zzb").forEach { fieldName ->
                val clients = getFieldValue(value, fieldName) as? List<*>
                clients?.forEach { client ->
                    packageNameFromObject(client)?.let { return it }
                }
            }
        }

        if (className == "com.google.android.gms.location.internal.LocationRequestUpdateData") {
            packageNameFromObject(getFieldValue(value, "b"))?.let { return it } // LocationRequestInternal
            (getFieldValue(value, "g") as? String)?.takeIf { it.isLikelyPackageName() }?.let { return it }
        }

        listOf(
            "mCallerIdentity",
            "mIdentity",
            "mCaller",
            "mAttributionSource",
            "attributionSource",
            "mWorkSource",
            "workSource",
            "zza", "zzb", "zzc"
        ).forEach { fieldName ->
            val nested = getFieldValue(value, fieldName)
            if (nested != null && nested !== value) {
                packageNameFromObject(nested)?.let { return it }
            }
        }

        listOf("getCallerIdentity", "getIdentity", "getAttributionSource", "getWorkSource").forEach { methodName ->
            val nested = callNoArg(value, methodName)
            if (nested != null && nested !== value) {
                packageNameFromObject(nested)?.let { return it }
            }
        }

        return null
    }

    private fun directPackageNameFromObject(value: Any?): String? {
        value ?: return null
        if (value is String) return value.takeIf { it.isLikelyPackageName() }

        listOf("mPackageName", "packageName", "mPackage", "package", "zza", "zzb", "zzc").forEach { fieldName ->
            (getFieldValue(value, fieldName) as? String)
                ?.takeIf { it.isLikelyPackageName() && it != "com.google.android.gms" }
                ?.let { return it }
        }

        listOf("getPackageName", "getPackage").forEach { methodName ->
            (callNoArg(value, methodName) as? String)
                ?.takeIf { it.isLikelyPackageName() && it != "com.google.android.gms" }
                ?.let { return it }
        }

        return null
    }

    private fun packageNameFromWorkSource(value: Any?): String? {
        val workSource = value?.takeIf { it.javaClass.name == "android.os.WorkSource" } ?: return null
        return runCatching {
            val size = XposedHelpers.callMethod(workSource, "size") as? Int ?: return@runCatching null
            repeat(size) { index ->
                (XposedHelpers.callMethod(workSource, "getName", index) as? String)
                    ?.takeIf { it.isLikelyPackageName() && it != "com.google.android.gms" }
                    ?.let { return it }
            }
            repeat(size) { index ->
                val uid = XposedHelpers.callMethod(workSource, "getUid", index) as? Int ?: return@repeat
                packageNameForUid(uid)
                    ?.takeIf { it != "com.google.android.gms" }
                    ?.let { return it }
            }
            null
        }.getOrNull()
    }

    private fun getFieldValue(owner: Any?, fieldName: String): Any? {
        owner ?: return null
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            val field = runCatching { current.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun callNoArg(owner: Any?, methodName: String): Any? {
        owner ?: return null
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            val method = current.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(owner)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
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

    private fun spoofNmea(nmea: String, profile: LocationProfile): String {
        val header = nmea.substringBefore(',').uppercase()
        if (!header.endsWith("GGA") && !header.endsWith("RMC")) return nmea

        val originalParts = nmea.substringBefore('*').split(',').toMutableList()
        if (originalParts.size < 7) return nmea

        val lat = latitudeToNmea(profile.latitude)
        val lon = longitudeToNmea(profile.longitude)
        originalParts[2] = lat.first
        originalParts[3] = lat.second
        originalParts[4] = lon.first
        originalParts[5] = lon.second

        if (header.endsWith("GGA") && originalParts.size > 9) {
            originalParts[6] = "1"
            originalParts[7] = profile.satellites.toString().padStart(2, '0')
            originalParts[8] = "%.1f".format(java.util.Locale.US, MOCK_HDOP)
            originalParts[9] = "%.1f".format(java.util.Locale.US, profile.altitude)
        }

        if (header.endsWith("RMC") && originalParts.size > 8) {
            originalParts[2] = "A"
            originalParts[7] = "0.0"
            originalParts[8] = "0.0"
        }

        return withChecksum(originalParts.joinToString(","))
    }

    private fun latitudeToNmea(latitude: Double): Pair<String, String> {
        val hemisphere = if (latitude >= 0) "N" else "S"
        val abs = kotlin.math.abs(latitude)
        val degrees = abs.toInt()
        val minutes = (abs - degrees) * 60.0
        return "%02d%07.4f".format(java.util.Locale.US, degrees, minutes) to hemisphere
    }

    private fun longitudeToNmea(longitude: Double): Pair<String, String> {
        val hemisphere = if (longitude >= 0) "E" else "W"
        val abs = kotlin.math.abs(longitude)
        val degrees = abs.toInt()
        val minutes = (abs - degrees) * 60.0
        return "%03d%07.4f".format(java.util.Locale.US, degrees, minutes) to hemisphere
    }

    private fun withChecksum(sentence: String): String {
        val body = sentence.removePrefix("$")
        val checksum = body.fold(0) { acc, char -> acc xor char.code }
        return "$$body*%02X".format(java.util.Locale.US, checksum)
    }
}
