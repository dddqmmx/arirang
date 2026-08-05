package asia.nana7mi.arirang.hook.packagelist

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.os.Binder
import android.util.ArrayMap
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.afterHookedMethod
import asia.nana7mi.arirang.hook.core.beforeHookedMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FuckPackageList : BaseHookModule(matchSystem = true) {
    private val config = PackageListHookConfig("clipboard_visibility_prefs")

    override fun isEnabled(): Boolean {
        config.loadIfUpdated()
        return config.enabled
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val smClass = HookBridge.findClassIfExists("android.os.ServiceManager", lpparam.classLoader)
                ?: throw ClassNotFoundException("ServiceManager not found")

            // Find addService method
            val addServiceMethod = smClass.declaredMethods.find {
                it.name == "addService" &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == String::class.java
            } ?: throw NoSuchMethodException("addService not found")

            HookLog.d(HookLog.Module.PACKAGE_LIST, "hooking ServiceManager.addService")

            HookBridge.hookMethod(addServiceMethod, beforeHookedMethod {
                if (args.getOrNull(0) == "package") {
                    val binder = args.getOrNull(1) ?: return@beforeHookedMethod
                    val pmClass = binder.javaClass
                    HookLog.d(HookLog.Module.PACKAGE_LIST, "package manager service registered; hooking methods")
                    hookPackageManagerService(pmClass)
                }
            })

            HookLog.i(HookLog.Module.PACKAGE_LIST, "ServiceManager hook installed successfully")

            hookPmsInternals(lpparam.classLoader)
        }.onFailure {
            HookLog.e(HookLog.Module.PACKAGE_LIST, "failed to install ServiceManager hook", it)
        }
    }

    private val internalHooked = AtomicBoolean(false)
    private val callingPackagesCache = ConcurrentHashMap<Int, Set<String>>()
    @Volatile
    private var cachedConfigVersion = -1L

    /**
     * PMS internal hook layer.
     *
     * Mirrors the classic "hide at the source" design: instead of enumerating
     * every public `IPackageManager` binder method, hook the package-visibility
     * chokepoints inside `com.android.server.pm.ComputerEngine` (Android 12+),
     * which every single-package query and every enumeration funnels through:
     *
     *  - [shouldFilterApplication] / [shouldFilterApplicationIncludingUninstalled]
     *    `(PackageStateInternal, int callingUid, int userId)`: returning `true`
     *    makes the target package invisible to that caller for
     *    `getInstalledPackages`, `getInstalledApplications`, `getPackageInfo`,
     *    `getPackageUid`, `getApplicationInfo`, `resolveIntent`, … all at once.
     *  - `getPackageStates()`: filter the returned state map for paths that
     *    iterate the raw registry without consulting [shouldFilterApplication],
     *    most notably `getPackagesHoldingPermissions`.
     *
     * The existing binder-level hooks remain installed as a safety net; the
     * filtering is idempotent so double-filtering is harmless.
     */
    private fun hookPmsInternals(classLoader: ClassLoader) {
        if (!internalHooked.compareAndSet(false, true)) return

        val engineClass = HookBridge.findClassIfExists("com.android.server.pm.ComputerEngine", classLoader)
        if (engineClass == null) {
            HookLog.w(HookLog.Module.PACKAGE_LIST, "ComputerEngine not found; internal PMS hooks skipped")
            return
        }

        for (name in listOf("shouldFilterApplication", "shouldFilterApplicationIncludingUninstalled")) {
            val overloads = engineClass.declaredMethods.filter { m ->
                m.name == name &&
                    m.parameterTypes.size == 3 &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[0].name.endsWith("PackageStateInternal")
            }
            for (method in overloads) {
                runCatching {
                    HookBridge.findAndHookMethod(
                        engineClass, name,
                        method.parameterTypes[0], Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        beforeHookedMethod { hideTargetForCaller() }
                    )
                    HookLog.d(
                        HookLog.Module.PACKAGE_LIST,
                        "hooked internal $name(${method.parameterTypes[0].simpleName})"
                    )
                }.onFailure {
                    HookLog.e(HookLog.Module.PACKAGE_LIST, "failed to hook internal $name", it)
                }
            }
        }

        val statesMethod = engineClass.declaredMethods.find {
            it.name == "getPackageStates" && it.parameterTypes.isEmpty()
        }
        if (statesMethod != null) {
            runCatching {
                HookBridge.findAndHookMethod(
                    engineClass, "getPackageStates",
                    afterHookedMethod { filterPackageStatesMap() }
                )
                HookLog.d(HookLog.Module.PACKAGE_LIST, "hooked internal getPackageStates")
            }.onFailure {
                HookLog.e(HookLog.Module.PACKAGE_LIST, "failed to hook internal getPackageStates", it)
            }
        }
    }

    /** before-hook for `shouldFilterApplication*`: force `true` when the target must stay hidden. */
    private fun XC_MethodHook.MethodHookParam.hideTargetForCaller() {
        if (isInternalCall.get() == true) return
        val callingUid = args.getOrNull(1) as? Int ?: return
        if (callingUid.appId() < 10000) return
        config.loadIfUpdated()
        if (!config.enabled) return
        val targetPackage = extractPackageStateName(args.getOrNull(0)) ?: return
        val callingPackages = resolveCallingPackages(this.thisObject, callingUid)
        if (!config.shouldKeepForPackages(callingUid, callingPackages, targetPackage)) {
            HookLog.d(
                HookLog.Module.PACKAGE_LIST,
                "internal filter: hiding '$targetPackage' from ${callingPackages.firstOrNull() ?: callingUid}"
            )
            result = true
        }
    }

    /** after-hook for `getPackageStates()`: strip hidden packages from the returned registry map. */
    private fun XC_MethodHook.MethodHookParam.filterPackageStatesMap() {
        if (isInternalCall.get() == true) return
        val callingUid = Binder.getCallingUid()
        if (callingUid.appId() < 10000) return
        config.loadIfUpdated()
        if (!config.enabled) return
        val states = result as? ArrayMap<*, *> ?: return
        val callingPackages = resolveCallingPackages(this.thisObject, callingUid)

        val hiddenKeys = HashSet<Any?>()
        for (i in 0 until states.size) {
            val key = states.keyAt(i)
            val packageName = key as? String ?: extractPackageStateName(states.valueAt(i)) ?: continue
            if (!config.shouldKeepForPackages(callingUid, callingPackages, packageName)) {
                hiddenKeys.add(key)
            }
        }
        if (hiddenKeys.isEmpty()) return

        val filtered = ArrayMap<Any?, Any?>()
        for (i in 0 until states.size) {
            val key = states.keyAt(i)
            if (key in hiddenKeys) continue
            filtered.put(key, states.valueAt(i))
        }
        HookLog.d(
            HookLog.Module.PACKAGE_LIST,
            "getPackageStates: filtered ${hiddenKeys.size} package(s) from ${callingPackages.firstOrNull() ?: callingUid}"
        )
        result = filtered
    }

    private fun extractPackageStateName(state: Any?): String? {
        if (state == null) return null
        return runCatching {
            HookBridge.callMethod(state, "getPackageName") as? String
        }.getOrNull()
    }

    /**
     * Resolves a UID to its package set with a small per-UID cache.
     *
     * The internal [shouldFilterApplication] hook fires once per package inside
     * a full enumeration loop; without a cache every one of those calls would
     * re-issue the `getPackagesForUid` reflection round-trip. The cache is keyed
     * by UID, capped in size, and invalidated whenever the config reloads.
     */
    private fun resolveCallingPackages(pmObject: Any, uid: Int): Set<String> {
        if (uid <= 0) return emptySet()
        val version = config.version
        if (cachedConfigVersion != version) {
            cachedConfigVersion = version
            callingPackagesCache.clear()
        }
        callingPackagesCache[uid]?.let { return it }
        val resolved = getPackagesForUid(pmObject, uid)
        if (callingPackagesCache.size >= CALLING_PACKAGES_CACHE_CAP) {
            callingPackagesCache.clear()
        }
        callingPackagesCache[uid] = resolved
        return resolved
    }

    private val pmHooked = AtomicBoolean(false)
    private val isInternalCall = ThreadLocal<Boolean>()

    private inline fun <T> withInternalCall(block: () -> T): T {
        val previous = isInternalCall.get()
        isInternalCall.set(true)
        return try {
            block()
        } finally {
            if (previous == null) {
                isInternalCall.remove()
            } else {
                isInternalCall.set(previous)
            }
        }
    }

    private fun findDeclaringClass(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Class<*>? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                current.getDeclaredMethod(methodName, *parameterTypes)
                return current
            } catch (e: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun hookMethodIfExists(
        pmClass: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any
    ) {
        val callback = parameterTypesAndCallback.last() as XC_MethodHook
        val parameterTypes = parameterTypesAndCallback.take(parameterTypesAndCallback.size - 1)
            .map { it as Class<*> }
            .toTypedArray()
        
        val declaringClass = findDeclaringClass(pmClass, methodName, *parameterTypes)
        if (declaringClass == null) {
            HookLog.w(HookLog.Module.PACKAGE_LIST, "method $methodName not found")
            return
        }
        
        runCatching {
            HookBridge.findAndHookMethod(declaringClass, methodName, *parameterTypesAndCallback)
            HookLog.d(HookLog.Module.PACKAGE_LIST, "hooked $methodName")
        }.onFailure {
            HookLog.e(HookLog.Module.PACKAGE_LIST, "failed to hook $methodName", it)
        }
    }

    private fun hookPackageManagerService(pmImplClass: Class<*>) {
        if (!pmHooked.compareAndSet(false, true)) return

        runCatching {
            val methods = pmImplClass.declaredMethods
                .filter { it.name.contains("getInstalled") || it.name.contains("getPackageInfo") || it.name.contains("queryIntent") }
                .map { method ->
                    "${method.name}(${method.parameterTypes.joinToString { it.name }}) -> ${method.returnType.name}"
                }
            HookLog.d(HookLog.Module.PACKAGE_LIST, "matching method count=${methods.size}")
        }.onFailure {
            HookLog.d(HookLog.Module.PACKAGE_LIST, "failed to inspect methods: ${it.message}")
        }

        // 1. Hook getInstalledApplications
        hookMethodIfExists(
            pmImplClass, "getInstalledApplications",
            Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "getInstalledApplications") { item ->
                    (item as? ApplicationInfo)?.packageName
                }
            }
        )

        // 2. Hook getInstalledPackages
        hookMethodIfExists(
            pmImplClass, "getInstalledPackages",
            Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "getInstalledPackages") { item ->
                    (item as? PackageInfo)?.packageName
                }
            }
        )

        // 3. Hook getPackageInfo
        hookMethodIfExists(
            pmImplClass, "getPackageInfo",
            String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            beforeHookedMethod {
                runCatching {
                    if (isInternalCall.get() == true) return@beforeHookedMethod
                    val packageName = args[0] as? String ?: return@beforeHookedMethod
                    val callingUid = Binder.getCallingUid()
                    if (callingUid.appId() < 10000) return@beforeHookedMethod
                    config.loadIfUpdated()
                    if (!config.enabled) return@beforeHookedMethod

                    val callingPackages = getPackagesForUid(this.thisObject, callingUid)
                    if (!config.shouldKeepForPackages(callingUid, callingPackages, packageName)) {
                        HookLog.d(HookLog.Module.PACKAGE_LIST, "Blocked getPackageInfo for '$packageName' from caller ${callingPackages.firstOrNull() ?: callingUid}")
                        result = null
                    }
                }.onFailure {
                    HookLog.e(HookLog.Module.PACKAGE_LIST, "getPackageInfo hook failed", it)
                }
            }
        )

        // 4. Hook queryIntentActivities
        hookMethodIfExists(
            pmImplClass, "queryIntentActivities",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "queryIntentActivities") { item ->
                    val resolveInfo = item as? ResolveInfo ?: return@filterParceledListSlice null
                    runCatching {
                        val activityInfo = HookBridge.getObjectField(resolveInfo, "activityInfo")
                        HookBridge.getObjectField(activityInfo, "packageName") as String
                    }.getOrNull() ?: runCatching {
                        val serviceInfo = HookBridge.getObjectField(resolveInfo, "serviceInfo")
                        HookBridge.getObjectField(serviceInfo, "packageName") as String
                    }.getOrNull() ?: runCatching {
                        val providerInfo = HookBridge.getObjectField(resolveInfo, "providerInfo")
                        HookBridge.getObjectField(providerInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 5. Hook queryIntentReceivers
        hookMethodIfExists(
            pmImplClass, "queryIntentReceivers",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "queryIntentReceivers") { item ->
                    val resolveInfo = item as? ResolveInfo ?: return@filterParceledListSlice null
                    runCatching {
                        val activityInfo = HookBridge.getObjectField(resolveInfo, "activityInfo")
                        HookBridge.getObjectField(activityInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 6. Hook queryIntentServices
        hookMethodIfExists(
            pmImplClass, "queryIntentServices",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "queryIntentServices") { item ->
                    val resolveInfo = item as? ResolveInfo ?: return@filterParceledListSlice null
                    runCatching {
                        val serviceInfo = HookBridge.getObjectField(resolveInfo, "serviceInfo")
                        HookBridge.getObjectField(serviceInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 7. Hook queryIntentContentProviders
        hookMethodIfExists(
            pmImplClass, "queryIntentContentProviders",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "queryIntentContentProviders") { item ->
                    val providerInfo = item as? ProviderInfo
                        ?: HookBridge.getObjectField(item, "providerInfo") as? ProviderInfo
                        ?: return@filterParceledListSlice null
                    runCatching {
                        HookBridge.getObjectField(providerInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 8. Hook getPackagesForUid
        hookMethodIfExists(
            pmImplClass, "getPackagesForUid",
            Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                runCatching {
                    if (isInternalCall.get() == true) return@afterHookedMethod
                    val pkgs = result as? Array<*> ?: return@afterHookedMethod
                    val callingUid = Binder.getCallingUid()
                    if (callingUid.appId() < 10000) return@afterHookedMethod
                    config.loadIfUpdated()
                    if (!config.enabled) return@afterHookedMethod

                    val targetUid = args[0] as Int
                    val targetPackages = pkgs.mapNotNull { it as? String }.toSet()
                    val callingPackages = if (targetUid == callingUid) {
                        targetPackages
                    } else {
                        getPackagesForUid(this.thisObject, callingUid)
                    }

                    var filteredCount = 0
                    val filtered = targetPackages.filter { pkg ->
                        val keep = config.shouldKeepForPackages(callingUid, callingPackages, pkg)
                        if (!keep) {
                            filteredCount++
                        }
                        keep
                    }.toTypedArray()

                    if (filteredCount > 0) {
                        HookLog.d(HookLog.Module.PACKAGE_LIST, "getPackagesForUid: filtered $filteredCount package(s) for caller ${callingPackages.firstOrNull() ?: callingUid}")
                    }
                    if (filtered.size != pkgs.size) {
                        result = if (filtered.isEmpty()) null else filtered
                    }
                }.onFailure {
                    HookLog.e(HookLog.Module.PACKAGE_LIST, "getPackagesForUid hook failed", it)
                }
            }
        )

        // 9. Hook getNameForUid
        hookMethodIfExists(
            pmImplClass, "getNameForUid",
            Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                runCatching {
                    if (isInternalCall.get() == true) return@afterHookedMethod
                    val targetUid = args[0] as Int
                    val name = result as? String ?: return@afterHookedMethod
                    val callingUid = Binder.getCallingUid()
                    if (callingUid.appId() < 10000 || targetUid == callingUid) return@afterHookedMethod
                    config.loadIfUpdated()
                    if (!config.enabled) return@afterHookedMethod

                    val targetPackages = getPackagesForUid(this.thisObject, targetUid)
                    val callingPackages = getPackagesForUid(this.thisObject, callingUid)
                    val visibleTargetPackages = if (targetPackages.isEmpty()) {
                        setOf(name).filter { pkg ->
                            config.shouldKeepForPackages(callingUid, callingPackages, pkg)
                        }.toSet()
                    } else {
                        targetPackages.filter { pkg ->
                            config.shouldKeepForPackages(callingUid, callingPackages, pkg)
                        }.toSet()
                    }

                    if (visibleTargetPackages.isEmpty()) {
                        result = null
                    }
                }.onFailure {
                    HookLog.e(HookLog.Module.PACKAGE_LIST, "getNameForUid hook failed", it)
                }
            }
        )

        // 10. Hook queryContentProviders
        hookMethodIfExists(
            pmImplClass, "queryContentProviders",
            String::class.java, Int::class.javaPrimitiveType !!, Long::class.javaPrimitiveType !!, String::class.java,
            afterHookedMethod {
                if (isInternalCall.get() == true) return@afterHookedMethod
                filterParceledListSlice(this, "queryContentProviders") { item ->
                    (item as? ProviderInfo)?.packageName
                }
            }
        )
    }

    private fun getPackagesForUid(pmObject: Any, uid: Int): Set<String> {
        if (uid <= 0) return emptySet()
        return withInternalCall {
            runCatching {
                (HookBridge.callMethod(pmObject, "getPackagesForUid", uid) as? Array<*>)
                    ?.mapNotNull { it as? String }
                    ?.toSet()
                    ?: emptySet()
            }.getOrDefault(emptySet())
        }
    }

    private fun filterParceledListSlice(
        param: XC_MethodHook.MethodHookParam,
        methodName: String,
        getPackageName: (Any) -> String?
    ) {
        runCatching {
            val parceledListSlice = param.result ?: return
            val list = HookBridge.callMethod(parceledListSlice, "getList") as? List<*> ?: return

            val callingUid = Binder.getCallingUid()
            if (callingUid.appId() < 10000) return

            config.loadIfUpdated()
            if (!config.enabled) return

            val callingPackages = getPackagesForUid(param.thisObject, callingUid)
            var filteredCount = 0
            val filtered = list.filter { item ->
                if (item == null) return@filter false
                val pkg = getPackageName(item) ?: return@filter true
                val keep = config.shouldKeepForPackages(callingUid, callingPackages, pkg)
                if (!keep) {
                    filteredCount++
                }
                keep
            }
            if (filteredCount > 0) {
                HookLog.d(HookLog.Module.PACKAGE_LIST, "$methodName: filtered $filteredCount package(s) for caller ${callingPackages.firstOrNull() ?: callingUid}")
            }
            param.result = HookBridge.newInstance(parceledListSlice.javaClass, filtered)
        }.onFailure {
            HookLog.e(HookLog.Module.PACKAGE_LIST, "$methodName hook failed", it)
        }
    }

    private fun Int.appId(): Int = Math.floorMod(this, 100_000)

    private companion object {
        private const val CALLING_PACKAGES_CACHE_CAP = 32
    }
}
