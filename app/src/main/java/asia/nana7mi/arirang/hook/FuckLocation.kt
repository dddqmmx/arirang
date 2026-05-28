package asia.nana7mi.arirang.hook

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class FuckLocation : BaseHookModule(
    targetPackages = setOf(
        "android",
        "com.android.location.fused",
        "com.google.android.gms"
    ),
    matchClient = true
) {

    private companion object {
        private const val DEBUG_HARDCODED_CONFIG = false
        private const val DEBUG_LATITUDE = 39.019444
        private const val DEBUG_LONGITUDE = 125.738052
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
            latitude = DEBUG_LATITUDE,
            longitude = DEBUG_LONGITUDE
        )
    }

    private val hookedClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private data class HookLocationConfig(
        val enabled: Boolean = false,
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
            HookNotifyClient.readConfigSnapshot(
                configName = "location",
                force = force,
                allowBind = true,
                logName = "location"
            )
        },
        parseRealtimeSnapshot = ::parseConfigSnapshot,
        readStoredConfig = ::readConfigFromPrefs
    )

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
                    hookLocationAccessors()
                    hookFrameworkLocationManagerClient(lpparam.classLoader)
                    hookFrameworkLocationResult(lpparam.classLoader)
                    hookGoogleFusedClient(lpparam.classLoader)
                    hookGoogleLocationResult(lpparam.classLoader)
                    hookGoogleTasks(lpparam.classLoader)
                }
            }
            HookLog.i(HookLog.Module.LOCATION, "location hook installed for ${lpparam.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.LOCATION, "location hook failed for ${lpparam.packageName}", it)
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
                latitude = json.optDouble(LocationConfigPrefs.KEY_LATITUDE, LocationConfigPrefs.DEFAULT_LATITUDE),
                longitude = json.optDouble(LocationConfigPrefs.KEY_LONGITUDE, LocationConfigPrefs.DEFAULT_LONGITUDE),
                altitude = json.optDouble(LocationConfigPrefs.KEY_ALTITUDE, LocationConfigPrefs.DEFAULT_ALTITUDE),
                accuracy = json.optDouble(LocationConfigPrefs.KEY_ACCURACY, LocationConfigPrefs.DEFAULT_ACCURACY.toDouble()).toFloat(),
                speed = json.optDouble(LocationConfigPrefs.KEY_SPEED, LocationConfigPrefs.DEFAULT_SPEED.toDouble()).toFloat(),
                bearing = json.optDouble(LocationConfigPrefs.KEY_BEARING, LocationConfigPrefs.DEFAULT_BEARING.toDouble()).toFloat(),
                satellites = json.optInt(LocationConfigPrefs.KEY_SATELLITES, LocationConfigPrefs.DEFAULT_SATELLITES)
                    .coerceIn(0, 64)
            )
        }.onFailure {
            HookLog.w(HookLog.Module.LOCATION, "failed to parse location config snapshot: ${it.message}")
        }.getOrNull()
    }

    private fun readConfigFromPrefs(prefs: de.robv.android.xposed.XSharedPreferences): HookLocationConfig {
        return HookLocationConfig(
            enabled = prefs.getBoolean(LocationConfigPrefs.KEY_ENABLED, false),
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
        )
    }

    private fun hookLocationAccessors() {
        if (!hookedClasses.add(Location::class.java)) return

        XposedBridge.hookAllMethods(Location::class.java, "getLatitude", beforeHookedMethod {
            currentConfig().takeIf { it.enabled }?.let { result = it.latitude }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getLongitude", beforeHookedMethod {
            currentConfig().takeIf { it.enabled }?.let { result = it.longitude }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getAltitude", beforeHookedMethod {
            currentConfig().takeIf { it.enabled }?.let { result = it.altitude }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getAccuracy", beforeHookedMethod {
            currentConfig().takeIf { it.enabled }?.let { result = it.accuracy }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getSpeed", beforeHookedMethod {
            currentConfig().takeIf { it.enabled }?.let { result = it.speed }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getBearing", beforeHookedMethod {
            currentConfig().takeIf { it.enabled }?.let { result = it.bearing }
        })
        XposedBridge.hookAllMethods(Location::class.java, "getProvider", beforeHookedMethod {
            if (currentConfig().enabled) result = LocationManager.GPS_PROVIDER
        })
        XposedBridge.hookAllMethods(Location::class.java, "getTime", beforeHookedMethod {
            if (currentConfig().enabled) result = System.currentTimeMillis()
        })
        XposedBridge.hookAllMethods(Location::class.java, "getElapsedRealtimeNanos", beforeHookedMethod {
            if (currentConfig().enabled) result = SystemClock.elapsedRealtimeNanos()
        })

        runCatching {
            XposedBridge.hookAllMethods(Location::class.java, "isFromMockProvider", beforeHookedMethod {
                if (currentConfig().enabled) result = false
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                XposedBridge.hookAllMethods(Location::class.java, "isMock", beforeHookedMethod {
                    if (currentConfig().enabled) result = false
                })
            }
        }

        HookLog.i(HookLog.Module.LOCATION, "hooked Location accessors")
    }

    private fun hookLocationManagerService(classLoader: ClassLoader) {
        val lmsClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.LocationManagerService",
            classLoader
        ) ?: return
        if (!hookedClasses.add(lmsClass)) return

        XposedBridge.hookAllMethods(lmsClass, "getLastLocation", afterHookedMethod {
            if (!currentConfig().enabled) return@afterHookedMethod
            val provider = providerFromArgs(args)
            result = fakeLocation(provider)
            HookLog.d(HookLog.Module.LOCATION, "spoofed getLastLocation provider=$provider caller=${callerFromArgs(args)}")
        })

        XposedBridge.hookAllMethods(lmsClass, "getCurrentLocation", beforeHookedMethod {
            if (!currentConfig().enabled) return@beforeHookedMethod
            val callback = args.firstOrNull { it?.javaClass?.hasLocationCallbackMethod() == true } ?: return@beforeHookedMethod
            val location = fakeLocation(providerFromArgs(args))
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
                if (!currentConfig().enabled) return@beforeHookedMethod
                args.forEachIndexed { index, arg ->
                    if (arg is Location) {
                        args[index] = arg.spoofed()
                    }
                }
            })
        }
    }

    private fun hookProviderManager(classLoader: ClassLoader) {
        val providerManagerClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.provider.LocationProviderManager",
            classLoader
        ) ?: return
        if (!hookedClasses.add(providerManagerClass)) return

        XposedBridge.hookAllMethods(providerManagerClass, "onReportLocation", beforeHookedMethod {
            if (!currentConfig().enabled) return@beforeHookedMethod
            val report = args.firstOrNull() ?: return@beforeHookedMethod
            if (report is Location) {
                args[0] = report.spoofed()
                return@beforeHookedMethod
            }

            if (rewriteLocationResult(report)) {
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
            if (!currentConfig().enabled) return@afterHookedMethod
            if (result is Location) {
                result = (result as Location).spoofed()
            }
        })

        XposedBridge.hookAllMethods(fusedProviderClass, "reportBestLocationLocked", beforeHookedMethod {
            if (!currentConfig().enabled) return@beforeHookedMethod
            args.forEachIndexed { index, arg ->
                args[index] = rewriteLocationContainer(arg)
            }
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked Android fused provider")
    }

    private fun hookFrameworkLocationManagerClient(classLoader: ClassLoader) {
        val locationManagerClass = XposedHelpers.findClassIfExists("android.location.LocationManager", classLoader)
            ?: return
        if (hookedClasses.add(locationManagerClass)) {
            XposedBridge.hookAllMethods(locationManagerClass, "getLastKnownLocation", afterHookedMethod {
                if (!currentConfig().enabled) return@afterHookedMethod
                if (result is Location) {
                    result = (result as Location).spoofed()
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
                if (!currentConfig().enabled) return@beforeHookedMethod
                args.forEachIndexed { index, arg ->
                    args[index] = rewriteLocationContainer(arg)
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

    private fun hookLocationResultClass(resultClass: Class<*>) {
        if (!hookedClasses.add(resultClass)) return

        listOf("asList", "getLocations").forEach { methodName ->
            XposedBridge.hookAllMethods(resultClass, methodName, afterHookedMethod {
                if (!currentConfig().enabled) return@afterHookedMethod
                result = rewriteLocationContainer(result)
            })
        }

        XposedBridge.hookAllMethods(resultClass, "getLastLocation", afterHookedMethod {
            if (!currentConfig().enabled) return@afterHookedMethod
            if (result is Location) {
                result = (result as Location).spoofed()
            }
        })

        XposedBridge.hookAllMethods(resultClass, "writeToParcel", beforeHookedMethod {
            if (!currentConfig().enabled) return@beforeHookedMethod
            rewriteLocationResult(thisObject)
        })

        HookLog.i(HookLog.Module.LOCATION, "hooked LocationResult ${resultClass.name}")
    }

    private fun hookGoogleTasks(classLoader: ClassLoader) {
        val taskClass = XposedHelpers.findClassIfExists("com.google.android.gms.tasks.Task", classLoader)
            ?: return
        if (!hookedClasses.add(taskClass)) return

        XposedBridge.hookAllMethods(taskClass, "getResult", afterHookedMethod {
            if (!currentConfig().enabled) return@afterHookedMethod
            result = rewriteLocationContainer(result)
        })

        listOf("addOnSuccessListener", "addOnCompleteListener").forEach { methodName ->
            XposedBridge.hookAllMethods(taskClass, methodName, hookedMethod(
                before = {
                    if (!currentConfig().enabled) return@hookedMethod
                    args.forEach { arg ->
                        hookGoogleTaskListener(arg)
                    }
                },
                after = {
                    if (!currentConfig().enabled) return@hookedMethod
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
            if (!currentConfig().enabled) return@afterHookedMethod
            result = rewriteLocationContainer(result)
        })

        listOf("addOnSuccessListener", "addOnCompleteListener").forEach { methodName ->
            XposedBridge.hookAllMethods(taskClass, methodName, beforeHookedMethod {
                if (!currentConfig().enabled) return@beforeHookedMethod
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
            if (!currentConfig().enabled) return@beforeHookedMethod
            args.forEachIndexed { index, arg ->
                args[index] = rewriteLocationContainer(arg)
            }
        })

        XposedBridge.hookAllMethods(listenerClass, "onComplete", beforeHookedMethod {
            if (!currentConfig().enabled) return@beforeHookedMethod
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
                        if (!currentConfig().enabled) return@beforeHookedMethod
                        args.forEachIndexed { index, arg ->
                            args[index] = rewriteLocationContainer(arg)
                        }
                    })
                }

                XposedBridge.hookAllMethods(targetClass, "reportNmea", beforeHookedMethod {
                    if (!currentConfig().enabled) return@beforeHookedMethod
                    args.forEachIndexed { index, arg ->
                        if (arg is String) {
                            args[index] = spoofNmea(arg)
                        }
                    }
                })

                listOf(
                    "reportMeasurementData",
                    "reportAntennaInfo",
                    "reportNavigationMessage"
                ).forEach { methodName ->
                    XposedBridge.hookAllMethods(targetClass, methodName, beforeHookedMethod {
                        if (!currentConfig().enabled) return@beforeHookedMethod
                        result = null
                    })
                }

                HookLog.i(HookLog.Module.LOCATION, "hooked GNSS class ${targetClass.name}")
            }
    }

    private fun rewriteLocationResult(locationResult: Any): Boolean {
        val locationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations") ?: return false
        locationsField.isAccessible = true
        val original = locationsField.get(locationResult)
        val rewritten = rewriteLocationContainer(original)
        if (rewritten === original) return false
        locationsField.set(locationResult, rewritten)
        return true
    }

    private fun rewriteLocationContainer(value: Any?): Any? {
        if (!currentConfig().enabled) return value
        return when (value) {
            is Location -> value.spoofed()
            is Array<*> -> {
                value.forEachIndexed { index, item ->
                    if (item is Location) {
                        @Suppress("UNCHECKED_CAST")
                        (value as Array<Any?>)[index] = item.spoofed()
                    }
                }
                value
            }
            is List<*> -> value.mapNotNull { rewriteLocationContainer(it) as? Location }
            else -> value
        }
    }

    private fun Location.spoofed(): Location {
        if (!currentConfig().enabled) return this
        return fakeLocation(provider ?: LocationManager.GPS_PROVIDER, this)
    }

    private fun fakeLocation(
        provider: String? = LocationManager.GPS_PROVIDER,
        source: Location? = null
    ): Location {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val config = currentConfig()
        val location = source?.let(::Location) ?: Location(provider ?: LocationManager.GPS_PROVIDER)

        location.provider = provider ?: LocationManager.GPS_PROVIDER
        location.latitude = config.latitude
        location.longitude = config.longitude
        location.altitude = config.altitude
        location.speed = config.speed
        location.bearing = config.bearing
        location.accuracy = config.accuracy
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
        extras.putInt("satellites", config.satellites)
        extras.putInt("visible_satellites", (config.satellites + 4).coerceAtLeast(config.satellites))
        extras.putFloat("hdop", MOCK_HDOP)
        location.extras = extras

        return location
    }

    private fun providerFromArgs(args: Array<Any?>): String {
        return args.firstOrNull { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER }
            as? String
            ?: LocationManager.GPS_PROVIDER
    }

    private fun callerFromArgs(args: Array<Any?>): String? {
        return args.filterIsInstance<String>()
            .lastOrNull { it.contains('.') && it != LocationManager.GPS_PROVIDER && it != LocationManager.NETWORK_PROVIDER }
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

    private fun spoofNmea(nmea: String): String {
        val config = currentConfig()
        if (!config.enabled) return nmea
        val header = nmea.substringBefore(',').uppercase()
        if (!header.endsWith("GGA") && !header.endsWith("RMC")) return nmea

        val originalParts = nmea.substringBefore('*').split(',').toMutableList()
        if (originalParts.size < 7) return nmea

        val lat = latitudeToNmea(config.latitude)
        val lon = longitudeToNmea(config.longitude)
        originalParts[2] = lat.first
        originalParts[3] = lat.second
        originalParts[4] = lon.first
        originalParts[5] = lon.second

        if (header.endsWith("GGA") && originalParts.size > 9) {
            originalParts[6] = "1"
            originalParts[7] = config.satellites.toString().padStart(2, '0')
            originalParts[8] = "%.1f".format(java.util.Locale.US, MOCK_HDOP)
            originalParts[9] = "%.1f".format(java.util.Locale.US, config.altitude)
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
