package asia.nana7mi.arirang.hook

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FuckPackageList : BaseHookModule(matchSystem = true) {
    private val config = HookConfig("clipboard_visibility_prefs")

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val computerEngine = XposedHelpers.findClassIfExists("com.android.server.pm.ComputerEngine", lpparam.classLoader)
                ?: throw ClassNotFoundException("ComputerEngine not found")

            // 1. Hook getInstalledApplications
            XposedHelpers.findAndHookMethod(
                computerEngine, "getInstalledApplications",
                Long::class.java, Int::class.java, Int::class.java, Boolean::class.java,
                afterHookedMethod {
                    filterList<ApplicationInfo>(this) { it.packageName }
                }
            )

            // 2. Hook getInstalledPackagesBody
            XposedHelpers.findAndHookMethod(
                computerEngine, "getInstalledPackagesBody",
                Long::class.java, Int::class.java, Int::class.java,
                afterHookedMethod {
                    val parceledListSlice = result ?: return@afterHookedMethod
                    runCatching {
                        val list = XposedHelpers.callMethod(parceledListSlice, "getList") as List<*>
                        val filtered = list.filterIsInstance<PackageInfo>().filter {
                            config.loadIfUpdated("visible_list", "invisible_list")
                            config.shouldKeep(it.packageName)
                        }
                        result = XposedHelpers.newInstance(parceledListSlice.javaClass, filtered)
                    }
                }
            )

            // 拦截单包查询
            XposedHelpers.findAndHookMethod(
                computerEngine, "getPackageInfoInternal",
                String::class.java, Long::class.java, Long::class.java, Int::class.java, Int::class.java,
                beforeHookedMethod {
                    val packageName = args[0] as String
                    if (!config.shouldKeep(packageName)) {
                        // 模拟包不存在的情况，返回 null
                        result = null
                    }
                }
            )

            // 2. 拦截意图搜索 (如查询所有 Launcher 应用)
            XposedHelpers.findAndHookMethod(
                computerEngine, "queryIntentActivitiesInternal",
                android.content.Intent::class.java, String::class.java, Long::class.java, Long::class.java, Int::class.java,Int::class.java,Int::class.java,
                Boolean::class.java,Boolean::class.java,
                afterHookedMethod {
                    val list = result as? List<*> ?: return@afterHookedMethod
                    val filtered = list.filter { resolveInfo ->
                        val activityInfo = XposedHelpers.getObjectField(resolveInfo, "activityInfo")
                        val pkg = XposedHelpers.getObjectField(activityInfo, "packageName") as String
                        config.shouldKeep(pkg)
                    }
                    result = filtered
                }
            )

            HookLog.i(HookLog.Module.PACKAGE_LIST, "hooked successfully")
        }.onFailure {
            HookLog.e(HookLog.Module.PACKAGE_LIST, "hook failed", it)
        }
    }

    /**
     * 通用的列表过滤工具
     */
    private inline fun <reified T> filterList(param: XC_MethodHook.MethodHookParam, getPackageName: (T) -> String) {
        config.loadIfUpdated("visible_list", "invisible_list")
        if (!config.enabled) return

        val originalList = param.result as? List<*> ?: return
        val filteredList = originalList.filterIsInstance<T>().filter {
            config.shouldKeep(getPackageName(it))
        }
        param.result = filteredList
    }
}
