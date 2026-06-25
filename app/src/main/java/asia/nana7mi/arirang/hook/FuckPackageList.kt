package asia.nana7mi.arirang.hook

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ProviderInfo
import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class FuckPackageList : BaseHookModule(matchSystem = true) {
    private val config = PackageListConfig("clipboard_visibility_prefs")

    override fun isEnabled(): Boolean {
        config.loadIfUpdated()
        return config.enabled
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", lpparam.classLoader)
                ?: throw ClassNotFoundException("ServiceManager not found")

            // Find addService method
            val addServiceMethod = smClass.declaredMethods.find {
                it.name == "addService" &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == String::class.java
            } ?: throw NoSuchMethodException("addService not found")

            XposedBridge.log("Arirang/package_list/I: Hooking ServiceManager.addService to capture package manager class")

            XposedBridge.hookMethod(addServiceMethod, beforeHookedMethod {
                if (args.getOrNull(0) == "package") {
                    val binder = args.getOrNull(1) ?: return@beforeHookedMethod
                    val pmClass = binder.javaClass
                    XposedBridge.log("Arirang/package_list/I: Package manager service registered with class: ${pmClass.name}. Hooking methods...")
                    hookPackageManagerService(pmClass)
                }
            })

            HookLog.i(HookLog.Module.PACKAGE_LIST, "ServiceManager hook installed successfully")
        }.onFailure {
            XposedBridge.log("Arirang/package_list/E: Failed to install ServiceManager hook: ${it.message}\n${it.stackTraceToString()}")
        }
    }

    private val pmHooked = AtomicBoolean(false)

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
            XposedBridge.log("Arirang/package_list/W: Method $methodName not found in ${pmClass.name} hierarchy")
            return
        }
        
        runCatching {
            XposedHelpers.findAndHookMethod(declaringClass, methodName, *parameterTypesAndCallback)
            XposedBridge.log("Arirang/package_list/I: Successfully hooked $methodName on ${declaringClass.name}")
        }.onFailure {
            XposedBridge.log("Arirang/package_list/E: Failed to hook $methodName on ${declaringClass.name}: ${it.message}")
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
            XposedBridge.log("Arirang/package_list/I: Declared matching methods: \n" + methods.joinToString("\n"))
        }.onFailure {
            XposedBridge.log("Arirang/package_list/E: Failed to dump methods: ${it.message}")
        }

        // 1. Hook getInstalledApplications
        hookMethodIfExists(
            pmImplClass, "getInstalledApplications",
            Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
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
                val packageName = args[0] as? String ?: return@beforeHookedMethod
                config.loadIfUpdated()
                val callingUid = Binder.getCallingUid()
                if (!config.shouldKeepForUid(callingUid, packageName)) {
                    val callingPackages = config.getPackagesForUid(callingUid)
                    XposedBridge.log("Arirang/package_list/D: Blocked getPackageInfo for package '$packageName' for caller ${callingPackages.firstOrNull() ?: callingUid}")
                    result = null
                }
            }
        )

        // 4. Hook queryIntentActivities
        hookMethodIfExists(
            pmImplClass, "queryIntentActivities",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                filterParceledListSlice(this, "queryIntentActivities") { item ->
                    val resolveInfo = item as? ResolveInfo ?: return@filterParceledListSlice null
                    runCatching {
                        val activityInfo = XposedHelpers.getObjectField(resolveInfo, "activityInfo")
                        XposedHelpers.getObjectField(activityInfo, "packageName") as String
                    }.getOrNull() ?: runCatching {
                        val serviceInfo = XposedHelpers.getObjectField(resolveInfo, "serviceInfo")
                        XposedHelpers.getObjectField(serviceInfo, "packageName") as String
                    }.getOrNull() ?: runCatching {
                        val providerInfo = XposedHelpers.getObjectField(resolveInfo, "providerInfo")
                        XposedHelpers.getObjectField(providerInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 5. Hook queryIntentReceivers
        hookMethodIfExists(
            pmImplClass, "queryIntentReceivers",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                filterParceledListSlice(this, "queryIntentReceivers") { item ->
                    val resolveInfo = item as? ResolveInfo ?: return@filterParceledListSlice null
                    runCatching {
                        val activityInfo = XposedHelpers.getObjectField(resolveInfo, "activityInfo")
                        XposedHelpers.getObjectField(activityInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 6. Hook queryIntentServices
        hookMethodIfExists(
            pmImplClass, "queryIntentServices",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                filterParceledListSlice(this, "queryIntentServices") { item ->
                    val resolveInfo = item as? ResolveInfo ?: return@filterParceledListSlice null
                    runCatching {
                        val serviceInfo = XposedHelpers.getObjectField(resolveInfo, "serviceInfo")
                        XposedHelpers.getObjectField(serviceInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )

        // 7. Hook queryIntentContentProviders
        hookMethodIfExists(
            pmImplClass, "queryIntentContentProviders",
            Intent::class.java, String::class.java, Long::class.javaPrimitiveType !!, Int::class.javaPrimitiveType !!,
            afterHookedMethod {
                filterParceledListSlice(this, "queryIntentContentProviders") { item ->
                    val providerInfo = item as? ProviderInfo
                        ?: XposedHelpers.getObjectField(item, "providerInfo") as? ProviderInfo
                        ?: return@filterParceledListSlice null
                    runCatching {
                        XposedHelpers.getObjectField(providerInfo, "packageName") as String
                    }.getOrNull()
                }
            }
        )
    }

    private fun filterParceledListSlice(
        param: XC_MethodHook.MethodHookParam,
        methodName: String,
        getPackageName: (Any) -> String?
    ) {
        val parceledListSlice = param.result ?: return
        val list = XposedHelpers.callMethod(parceledListSlice, "getList") as? List<*> ?: return
        
        config.loadIfUpdated()
        if (!config.enabled) return

        val callingUid = Binder.getCallingUid()
        val callingPackages = config.getPackagesForUid(callingUid)
        val filtered = list.filter { item ->
            if (item == null) return@filter false
            val pkg = getPackageName(item) ?: return@filter true
            val keep = config.shouldKeepForUid(callingUid, pkg)
            if (!keep) {
                XposedBridge.log("Arirang/package_list/D: Filtered package '$pkg' for caller ${callingPackages.firstOrNull() ?: callingUid} in $methodName")
            }
            keep
        }
        param.result = XposedHelpers.newInstance(parceledListSlice.javaClass, filtered)
    }

    private class PackageListConfig(private val prefsName: String) {
        var enabled = false
        private var defaultMode = "ALL_VISIBLE"
        private var defaultTemplateId: String? = null
        
        private val templates = mutableMapOf<String, Template>()
        private val appRules = mutableMapOf<String, AppRule>()
        
        private var lastLoadedTimestamp = -1L

        private val pref by lazy {
            HookConfigFile.xSharedPreferences(prefsName)
        }

        class Template(
            val id: String,
            val name: String,
            val parentId: String?,
            val visiblePackages: Set<String>,
            val isBlacklist: Boolean
        )

        class AppRule(
            val packageName: String,
            val mode: String,
            val templateId: String?,
            val visiblePackages: Set<String>
        )

        fun loadIfUpdated() {
            val snapshot = ArirangClient.readConfigSnapshot(
                configName = "package_list",
                force = false,
                allowBind = true,
                logName = "Package List"
            )

            if (!snapshot.isNullOrBlank()) {
                runCatching {
                    val json = org.json.JSONObject(snapshot)
                    val newTimestamp = json.optLong("last_modified", -1L)
                    if (newTimestamp == lastLoadedTimestamp) return
                    lastLoadedTimestamp = newTimestamp

                    enabled = json.optBoolean("enabled", false)
                    defaultMode = json.optString("default_display_mode", "ALL_VISIBLE")
                    defaultTemplateId = json.optString("default_template_id").takeIf { it.isNotEmpty() }

                    XposedBridge.log("Arirang/package_list/I: Reloaded config from snapshot: enabled=$enabled, defaultMode=$defaultMode, defaultTemplateId=$defaultTemplateId")

                    val templatesJsonStr = json.optString("visibility_templates").takeIf { it.isNotEmpty() }
                    parseTemplates(templatesJsonStr)

                    val appRulesJsonStr = json.optString("visibility_app_rules").takeIf { it.isNotEmpty() }
                    parseAppRules(appRulesJsonStr)
                }.onFailure {
                    XposedBridge.log("Arirang/package_list/E: Failed to parse config snapshot: ${it.message}")
                }
            } else {
                if (pref.file.canRead()) {
                    pref.reload()
                    val newTimestamp = pref.getLong("last_modified", -1L)
                    if (newTimestamp == lastLoadedTimestamp) return
                    lastLoadedTimestamp = newTimestamp

                    enabled = pref.getBoolean("enabled", false)
                    defaultMode = pref.getString("default_display_mode", "ALL_VISIBLE") ?: "ALL_VISIBLE"
                    defaultTemplateId = pref.getString("default_template_id", null)

                    XposedBridge.log("Arirang/package_list/I: Reloaded config from XSharedPreferences: enabled=$enabled, defaultMode=$defaultMode, defaultTemplateId=$defaultTemplateId")

                    val templatesJsonStr = pref.getString("visibility_templates", null)
                    parseTemplates(templatesJsonStr)

                    val appRulesJsonStr = pref.getString("visibility_app_rules", null)
                    parseAppRules(appRulesJsonStr)
                } else {
                    if (lastLoadedTimestamp != -2L) {
                        XposedBridge.log("Arirang/package_list/D: Config snapshot is empty and XSharedPreferences file ${pref.file.absolutePath} is not readable")
                        lastLoadedTimestamp = -2L
                        enabled = false
                    }
                }
            }
        }

        private fun parseTemplates(templatesJsonStr: String?) {
            templates.clear()
            if (!templatesJsonStr.isNullOrBlank()) {
                runCatching {
                    val array = org.json.JSONArray(templatesJsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val id = obj.getString("id")
                        val name = obj.getString("name")
                        val parentId = obj.optString("parentId").takeIf { it.isNotEmpty() }
                        val visiblePackages = mutableSetOf<String>()
                        val pkgsArray = obj.optJSONArray("visiblePackages")
                        if (pkgsArray != null) {
                            for (j in 0 until pkgsArray.length()) {
                                visiblePackages.add(pkgsArray.getString(j))
                            }
                        }
                        val listMode = obj.optString("listMode", "WHITELIST")
                        templates[id] = Template(
                            id = id,
                            name = name,
                            parentId = parentId,
                            visiblePackages = visiblePackages,
                            isBlacklist = listMode == "BLACKLIST"
                        )
                    }
                }
            }
        }

        private fun parseAppRules(appRulesJsonStr: String?) {
            appRules.clear()
            if (!appRulesJsonStr.isNullOrBlank()) {
                runCatching {
                    val array = org.json.JSONArray(appRulesJsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val packageName = obj.getString("packageName")
                        val mode = obj.optString("mode", "DEFAULT")
                        val templateId = obj.optString("templateId").takeIf { it.isNotEmpty() }
                        val visiblePackages = mutableSetOf<String>()
                        val pkgsArray = obj.optJSONArray("visiblePackages")
                        if (pkgsArray != null) {
                            for (j in 0 until pkgsArray.length()) {
                                visiblePackages.add(pkgsArray.getString(j))
                            }
                        }
                        appRules[packageName] = AppRule(
                            packageName = packageName,
                            mode = mode,
                            templateId = templateId,
                            visiblePackages = visiblePackages
                        )
                    }
                }
            }
        }

        private fun getResolvedTemplatePackages(templateId: String): Pair<Set<String>, Boolean> {
            val visited = mutableSetOf<String>()
            val pkgs = mutableSetOf<String>()
            var current = templates[templateId]
            var isBlacklist = false
            if (current != null) {
                isBlacklist = current.isBlacklist
            }
            while (current != null && visited.add(current.id)) {
                pkgs.addAll(current.visiblePackages)
                current = current.parentId?.let { templates[it] }
            }
            return pkgs to isBlacklist
        }

        fun shouldKeep(callingPackage: String, targetPackage: String): Boolean {
            if (!enabled) return true
            if (callingPackage == targetPackage) return true
            if (targetPackage == "android" || targetPackage == asia.nana7mi.arirang.BuildConfig.APPLICATION_ID) return true

            val rule = appRules[callingPackage]
            val mode = rule?.mode ?: "DEFAULT"
            
            val resolvedMode = if (mode == "DEFAULT") defaultMode else mode
            val resolvedTemplateId = if (mode == "DEFAULT") defaultTemplateId else rule?.templateId

            return when (resolvedMode) {
                "ALL_VISIBLE" -> true
                "ALL_HIDDEN" -> false
                "TEMPLATE" -> {
                    if (resolvedTemplateId == null) {
                        true
                    } else {
                        val (templatePkgs, isBlacklist) = getResolvedTemplatePackages(resolvedTemplateId)
                        if (isBlacklist) {
                            !templatePkgs.contains(targetPackage)
                        } else {
                            templatePkgs.contains(targetPackage)
                        }
                    }
                }
                "CUSTOM" -> {
                    val customPkgs = rule?.visiblePackages ?: emptySet()
                    customPkgs.contains(targetPackage)
                }
                else -> true
            }
        }

        fun getPackagesForUid(uid: Int): List<String> {
            if (uid <= 0) return emptyList()
            return runCatching {
                ArirangClient.getSystemContext()
                    ?.packageManager
                    ?.getPackagesForUid(uid)
                    ?.toList()
            }.getOrNull() ?: emptyList()
        }

        fun shouldKeepForUid(callingUid: Int, targetPackage: String): Boolean {
            if (!enabled) return true
            if (callingUid < 10000) return true
            if (targetPackage == "android" || targetPackage == asia.nana7mi.arirang.BuildConfig.APPLICATION_ID) return true
            
            val callingPackages = getPackagesForUid(callingUid)
            if (callingPackages.isEmpty()) return true
            if (callingPackages.contains(asia.nana7mi.arirang.BuildConfig.APPLICATION_ID)) return true
            
            return callingPackages.all { shouldKeep(it, targetPackage) }
        }
    }
}


