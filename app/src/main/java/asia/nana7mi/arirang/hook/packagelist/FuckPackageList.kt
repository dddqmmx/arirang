package asia.nana7mi.arirang.hook.packagelist

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookLog

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ProviderInfo
import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class FuckPackageList : BaseHookModule(matchSystem = true) {
    private val config = PackageListHookConfig("clipboard_visibility_prefs")

    override fun isEnabled(): Boolean {
        config.loadIfUpdated()
        return config.enabled
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val smClass = BaseHookModule.findClassIfExists("android.os.ServiceManager", lpparam.classLoader)
                ?: throw ClassNotFoundException("ServiceManager not found")

            // Find addService method
            val addServiceMethod = smClass.declaredMethods.find {
                it.name == "addService" &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == String::class.java
            } ?: throw NoSuchMethodException("addService not found")

            BaseHookModule.log("Arirang/package_list/I: Hooking ServiceManager.addService to capture package manager class")

            BaseHookModule.hookMethod(addServiceMethod, beforeHookedMethod {
                if (args.getOrNull(0) == "package") {
                    val binder = args.getOrNull(1) ?: return@beforeHookedMethod
                    val pmClass = binder.javaClass
                    BaseHookModule.log("Arirang/package_list/I: Package manager service registered with class: ${pmClass.name}. Hooking methods...")
                    hookPackageManagerService(pmClass)
                }
            })

            HookLog.i(HookLog.Module.PACKAGE_LIST, "ServiceManager hook installed successfully")
        }.onFailure {
            BaseHookModule.log("Arirang/package_list/E: Failed to install ServiceManager hook: ${it.message}\n${it.stackTraceToString()}")
        }
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
            BaseHookModule.log("Arirang/package_list/W: Method $methodName not found in ${pmClass.name} hierarchy")
            return
        }
        
        runCatching {
            BaseHookModule.findAndHookMethod(declaringClass, methodName, *parameterTypesAndCallback)
            BaseHookModule.log("Arirang/package_list/I: Successfully hooked $methodName on ${declaringClass.name}")
        }.onFailure {
            BaseHookModule.log("Arirang/package_list/E: Failed to hook $methodName on ${declaringClass.name}: ${it.message}")
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
            BaseHookModule.log("Arirang/package_list/I: Declared matching methods: \n" + methods.joinToString("\n"))
        }.onFailure {
            BaseHookModule.log("Arirang/package_list/E: Failed to dump methods: ${it.message}")
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
                    if (callingUid < 10000) return@beforeHookedMethod
                    config.loadIfUpdated()
                    if (!config.enabled) return@beforeHookedMethod

                    val callingPackages = getPackagesForUid(this.thisObject, callingUid)
                    if (!config.shouldKeepForPackages(callingUid, callingPackages, packageName)) {
                        BaseHookModule.log("Arirang/package_list/D: Blocked getPackageInfo for package '$packageName' for caller ${callingPackages.firstOrNull() ?: callingUid}")
                        result = null
                    }
                }.onFailure {
                    BaseHookModule.log("Arirang/package_list/E: getPackageInfo hook failed: ${it.message}")
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
                        val activityInfo = BaseHookModule.getObjectField(resolveInfo, "activityInfo")
                        BaseHookModule.getObjectField(activityInfo, "packageName") as String
                    }.getOrNull() ?: runCatching {
                        val serviceInfo = BaseHookModule.getObjectField(resolveInfo, "serviceInfo")
                        BaseHookModule.getObjectField(serviceInfo, "packageName") as String
                    }.getOrNull() ?: runCatching {
                        val providerInfo = BaseHookModule.getObjectField(resolveInfo, "providerInfo")
                        BaseHookModule.getObjectField(providerInfo, "packageName") as String
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
                        val activityInfo = BaseHookModule.getObjectField(resolveInfo, "activityInfo")
                        BaseHookModule.getObjectField(activityInfo, "packageName") as String
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
                        val serviceInfo = BaseHookModule.getObjectField(resolveInfo, "serviceInfo")
                        BaseHookModule.getObjectField(serviceInfo, "packageName") as String
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
                        ?: BaseHookModule.getObjectField(item, "providerInfo") as? ProviderInfo
                        ?: return@filterParceledListSlice null
                    runCatching {
                        BaseHookModule.getObjectField(providerInfo, "packageName") as String
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
                    if (callingUid < 10000) return@afterHookedMethod
                    config.loadIfUpdated()
                    if (!config.enabled) return@afterHookedMethod

                    val targetUid = args[0] as Int
                    val targetPackages = pkgs.mapNotNull { it as? String }
                    val callingPackages = if (targetUid == callingUid) {
                        targetPackages
                    } else {
                        getPackagesForUid(this.thisObject, callingUid)
                    }

                    val filtered = targetPackages.filter { pkg ->
                        val keep = config.shouldKeepForPackages(callingUid, callingPackages, pkg) &&
                            isInstalledPackageForCaller(this.thisObject, callingUid, pkg)
                        if (!keep) {
                            BaseHookModule.log("Arirang/package_list/D: Filtered package '$pkg' for caller ${callingPackages.firstOrNull() ?: callingUid} in getPackagesForUid")
                        }
                        keep
                    }.toTypedArray()

                    if (filtered.size != pkgs.size) {
                        result = if (filtered.isEmpty()) null else filtered
                    }
                }.onFailure {
                    BaseHookModule.log("Arirang/package_list/E: getPackagesForUid hook failed: ${it.message}")
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
                    if (callingUid < 10000 || targetUid == callingUid) return@afterHookedMethod
                    config.loadIfUpdated()
                    if (!config.enabled) return@afterHookedMethod

                    val targetPackages = getPackagesForUid(this.thisObject, targetUid)
                    val callingPackages = getPackagesForUid(this.thisObject, callingUid)
                    val visibleTargetPackages = if (targetPackages.isEmpty()) {
                        listOf(name).filter { pkg ->
                            config.shouldKeepForPackages(callingUid, callingPackages, pkg) &&
                                isInstalledPackageForCaller(this.thisObject, callingUid, pkg)
                        }
                    } else {
                        targetPackages.filter { pkg ->
                            config.shouldKeepForPackages(callingUid, callingPackages, pkg) &&
                                isInstalledPackageForCaller(this.thisObject, callingUid, pkg)
                        }
                    }

                    if (visibleTargetPackages.isEmpty()) {
                        BaseHookModule.log("Arirang/package_list/D: Filtered name '$name' for caller ${callingPackages.firstOrNull() ?: callingUid} in getNameForUid")
                        result = null
                    } else {
                        result = visibleTargetPackages.first()
                    }
                }.onFailure {
                    BaseHookModule.log("Arirang/package_list/E: getNameForUid hook failed: ${it.message}")
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

    private fun getPackagesForUid(pmObject: Any, uid: Int): List<String> {
        if (uid <= 0) return emptyList()
        return withInternalCall {
            runCatching {
                (BaseHookModule.callMethod(pmObject, "getPackagesForUid", uid) as? Array<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    private fun isInstalledPackageForCaller(pmObject: Any, callingUid: Int, packageName: String): Boolean {
        val userId = callingUid / 100000
        return withInternalCall {
            runCatching {
                BaseHookModule.callMethod(pmObject, "getPackageInfo", packageName, 0L, userId) != null
            }.getOrDefault(true)
        }
    }

    private fun filterParceledListSlice(
        param: XC_MethodHook.MethodHookParam,
        methodName: String,
        getPackageName: (Any) -> String?
    ) {
        runCatching {
            val parceledListSlice = param.result ?: return
            val list = BaseHookModule.callMethod(parceledListSlice, "getList") as? List<*> ?: return

            val callingUid = Binder.getCallingUid()
            if (callingUid < 10000) return

            config.loadIfUpdated()
            if (!config.enabled) return

            val callingPackages = getPackagesForUid(param.thisObject, callingUid)
            val filtered = list.filter { item ->
                if (item == null) return@filter false
                val pkg = getPackageName(item) ?: return@filter true
                val keep = config.shouldKeepForPackages(callingUid, callingPackages, pkg)
                if (!keep) {
                    BaseHookModule.log("Arirang/package_list/D: Filtered package '$pkg' for caller ${callingPackages.firstOrNull() ?: callingUid} in $methodName")
                }
                keep
            }
            param.result = BaseHookModule.newInstance(parceledListSlice.javaClass, filtered)
        }.onFailure {
            BaseHookModule.log("Arirang/package_list/E: $methodName hook failed: ${it.message}")
        }
    }

}
