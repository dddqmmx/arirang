package asia.nana7mi.arirang.hook

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FuckLocation : BaseHookModule(matchSystem = true) {
    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            val lmsClass = XposedHelpers.findClass(
                "com.android.server.location.LocationManagerService",
                classLoader
            )
            // 1. 劫持最后已知位置 (getLastLocation)
            hookLastLocation(lmsClass, classLoader)

            // 2. 劫持单次实时位置请求 (getCurrentLocation)
            hookCurrentLocation(lmsClass, classLoader)

            // 3. 劫持 Provider 汇报逻辑 (核心：处理流式定位更新)
            hookProviderManager(classLoader)

        } catch (t: Throwable) {
            XposedBridge.log("FuckLocation: Hook 过程出错 - ${t.message}")
        }
    }

    private fun hookLastLocation(lmsClass: Class<*>, classLoader: ClassLoader) {
        val lastLocationRequestClass =
            XposedHelpers.findClass("android.location.LastLocationRequest", classLoader)

        XposedHelpers.findAndHookMethod(
            lmsClass, "getLastLocation",
            String::class.java,
            lastLocationRequestClass,
            String::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[2] as? String
                    XposedBridge.log("FuckLocation: [拦截缓存定位] App: $pkg")

                    // 统一调用 modifyLocation
                    param.result = modifyLocation(Location(LocationManager.GPS_PROVIDER))
                }
            }
        )
    }

    private fun hookCurrentLocation(lmsClass: Class<*>, classLoader: ClassLoader) {
        val locationRequestClass =
            XposedHelpers.findClass("android.location.LocationRequest", classLoader)
        val iLocationCallbackClass =
            XposedHelpers.findClass("android.location.ILocationCallback", classLoader)

        XposedHelpers.findAndHookMethod(
            lmsClass, "getCurrentLocation",
            String::class.java,
            locationRequestClass,
            iLocationCallbackClass,
            String::class.java,
            String::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[3] as? String
                    XposedBridge.log("FuckLocation: [拦截实时定位] App: $pkg")

                    // 统一调用 modifyLocation
                    param.result = modifyLocation(Location(LocationManager.GPS_PROVIDER))
                }
            }
        )
    }

    private fun hookProviderManager(classLoader: ClassLoader) {
        try {
            val locationResultClass =
                XposedHelpers.findClass("android.location.LocationResult", classLoader)
            val providerManagerClass = XposedHelpers.findClass(
                "com.android.server.location.provider.LocationProviderManager",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                providerManagerClass,
                "onReportLocation",
                locationResultClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val locationResult = param.args[0] ?: return

                        // 反射获取 mLocations 列表 (LocationResult 内部维护一个 List<Location>)
                        val mLocationsField =
                            XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                                ?: return
                        mLocationsField.isAccessible = true

                        val originList = mLocationsField.get(locationResult) as? List<*> ?: return
                        if (originList.isEmpty()) return

                        // 创建新的列表，并将原始位置经过 modifyLocation 处理
                        val newList = ArrayList<Location>()
                        originList.forEach {
                            if (it is Location) {
                                newList.add(modifyLocation(it))
                            }
                        }

                        // 写回修改后的位置列表
                        mLocationsField.set(locationResult, newList)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("FuckLocation: ProviderManager Hook 失败: ${e.message}")
        }
    }

    /**
     * 升级版 Gnss Hook
     * 涵盖了 Android 9.0 - 14+ 的主流实现
     */
    private fun hookGnssLocation(classLoader: ClassLoader) {
        // 1. 自动寻找目标类 (适配不同 Android 版本)
        val gnssClasses = arrayOf(
            "com.android.server.location.gnss.hal.GnssNative", // Android 12+
            "com.android.server.location.gnss.GnssLocationProviderImpl", // 部分厂商定制版本
            "com.android.server.location.gnss.GnssLocationProvider" // Android 11 及以下
        )

        var targetClass: Class<*>? = null
        for (className in gnssClasses) {
            targetClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (targetClass != null) {
                log("成功锁定 GNSS 核心类: $className")
                break
            }
        }

        if (targetClass == null) {
            log("未找到兼容的 GNSS 核心类")
            return
        }

        // --- A. Hook 位置上报 (基础功能升级) ---

        // Hook 单点上报
        XposedBridge.hookAllMethods(targetClass, "reportLocation", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                for (i in args.indices) {
                    val arg = args[i]
                    if (arg is Location) {
                        modifyLocation(arg) // 使用你现有的修改逻辑
                    }
                }
            }
        })

        // Hook 批量上报 (由于底层可能混淆或版本差异，这里做了更强的兼容)
        XposedBridge.hookAllMethods(targetClass, "reportLocationBatch", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val arg = param.args?.getOrNull(0) ?: return
                when (arg) {
                    is Array<*> -> arg.forEach { if (it is Location) modifyLocation(it) }
                    is List<*> -> arg.forEach { if (it is Location) modifyLocation(it) }
                }
            }
        })

        // --- B. Hook NMEA 数据 (进阶功能：同步篡改原始报文) ---
        // 很多高德/百度地图的高精定位会校验 NMEA 字符串，如果不改，定位会跳变
        XposedBridge.hookAllMethods(targetClass, "reportNmea", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // reportNmea(long timestamp, String nmea) 或 (String nmea)
                val args = param.args ?: return
                for (i in args.indices) {
                    if (args[i] is String) {
                        val originalNmea = args[i] as String
                        args[i] = injectNmea(originalNmea) // 见下方 injectNmea 实现
                    }
                }
            }
        })

        // --- C. 屏蔽硬件层面的检测 (防检测关键) ---
        // 很多 App 会通过监听“卫星原始测量数据”来判断是否是模拟定位（模拟器通常没这些数据）
        // 我们直接返回空逻辑，让 App 拿不到真实的卫星原始状态
        val doNothingMethods = arrayOf(
            "reportMeasurementData",
            "reportAntennaInfo",
            "reportNavigationMessage",
            "reportSvStatus" // 卫星状态（星图）
        )

        val disableHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 如果开启模拟，就拦截这些底层硬件信息，返回空或不执行
                param.result = null
            }
        }

        doNothingMethods.forEach { methodName ->
            XposedBridge.hookAllMethods(targetClass, methodName, disableHook)
        }
    }

    /**
     * 伪造 NMEA 字符串
     * NMEA 包含了 $GPGGA, $GPRMC 等，直接决定了 App 看到的原始 GPS 数据
     */
    private fun injectNmea(nmea: String): String {
        try {
            // 如果你不想深度解析 NMEA 协议（非常复杂），
            // 简单暴力的方法是检测到坐标行时直接根据你的模拟坐标生成新的行，
            // 或者简单的返回一个空的/修改后的报文。
            // 这里提供一个思路：如果是 $GPGGA 或 $GPRMC，将其替换或返回空
            if (nmea.startsWith("\$GPGGA") || nmea.startsWith("\$GPRMC")) {
                // 进阶：根据 mockLat/mockLng 生成符合校验和的 NMEA
                // 简化：返回空字符串可以强迫 App 只信任 Location 对象
                return ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return nmea
    }

    // 辅助日志
    private fun log(msg: String) {
        XposedBridge.log("GNSS_HOOK: $msg")
    }

    /**
     * 统一修改坐标的方法
     * 包含：坐标偏转、时间更新、Mock标记移除、精度增强
     */
    private fun modifyLocation(loc: Location): Location { // 1. Add return type
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()

        // --- 1. 核心坐标修改 ---
        loc.latitude = 39.019444
        loc.longitude = 125.738052
        loc.altitude = 27.0

        // --- 2. 状态模拟 (防止静止状态下经纬度却不停跳变引起的检测) ---
        loc.speed = 0.0f
        loc.bearing = 0.0f
        loc.accuracy = 5.0f // 5米精度
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.verticalAccuracyMeters = 2.0f
            loc.speedAccuracyMetersPerSecond = 0.1f
            loc.bearingAccuracyDegrees = 0.5f
        }

        // --- 3. 时间同步 (最重要：系统会过滤掉时间戳过旧的数据) ---
        loc.time = now
        loc.elapsedRealtimeNanos = elapsed

        // --- 4. 移除所有 Mock 特征 ---
        loc.provider = LocationManager.GPS_PROVIDER

        // 移除 setIsFromMockProvider 标记 (Android 12+)
        try {
            XposedHelpers.callMethod(loc, "setIsFromMockProvider", false)
        } catch (ignored: Throwable) {
        }

        // 移除 Bundle 中的 mock 标记
        val extras = loc.extras ?: Bundle()
        extras.remove("mockLocation")
        extras.putBoolean("mockLocation", false)

        // 注入伪造的卫星信息 (让定位看起来更像真实的硬件上报)
        extras.putInt("satellites", 12)
        extras.putInt("visible_satellites", 16)
        extras.putFloat("hdop", 0.9f)

        loc.extras = extras

        return loc // 2. Return the modified object
    }
}