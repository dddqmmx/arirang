package asia.nana7mi.arirang.hook

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
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
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        filterList<ApplicationInfo>(param) { it.packageName }
                    }
                }
            )

            // 2. Hook getInstalledPackagesBody
            XposedHelpers.findAndHookMethod(
                computerEngine, "getInstalledPackagesBody",
                Long::class.java, Int::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val parceledListSlice = param.result ?: return
                        runCatching {
                            val list = XposedHelpers.callMethod(parceledListSlice, "getList") as List<*>
                            val filtered = list.filterIsInstance<PackageInfo>().filter {
                                config.loadIfUpdated("visible_list", "invisible_list")
                                config.shouldKeep(it.packageName)
                            }
                            param.result = XposedHelpers.newInstance(parceledListSlice.javaClass, filtered)
                        }
                    }
                }
            )

            // 拦截单包查询
            XposedHelpers.findAndHookMethod(
                computerEngine, "getPackageInfoInternal",
                String::class.java, Long::class.java, Long::class.java, Int::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as String
                        if (!config.shouldKeep(packageName)) {
                            // 模拟包不存在的情况，返回 null
                            param.result = null
                        }
                    }
                }
            )

            // 2. 拦截意图搜索 (如查询所有 Launcher 应用)
            XposedHelpers.findAndHookMethod(
                computerEngine, "queryIntentActivitiesInternal",
                android.content.Intent::class.java, String::class.java, Long::class.java, Long::class.java, Int::class.java,Int::class.java,Int::class.java,
                Boolean::class.java,Boolean::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<*> ?: return
                        val filtered = list.filter { resolveInfo ->
                            val activityInfo = XposedHelpers.getObjectField(resolveInfo, "activityInfo")
                            val pkg = XposedHelpers.getObjectField(activityInfo, "packageName") as String
                            config.shouldKeep(pkg)
                        }
                        param.result = filtered
                    }
                }
            )

            XposedBridge.log("FuckPackageList: Hooked successfully")
        }.onFailure {
            XposedBridge.log("FuckPackageList: Error: ${it.message}")
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