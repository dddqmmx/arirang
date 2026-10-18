package asia.nana7mi.arirang.hook.xposed

import asia.nana7mi.arirang.hook.api.HookPackageParam
import asia.nana7mi.arirang.hook.api.HookRegistry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * The libxposed entry point, named by `META-INF/xposed/java_init.list`.
 *
 * This class holds every libxposed dependency in the module: it wires [HookBridge] to the
 * framework and translates the framework's own parameter types into [HookPackageParam], so
 * [HookRegistry] and the feature modules never see them.
 */
class ArirangModule : XposedModule() {

    private val registry = HookRegistry()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookBridge.init(this)
        HookLog.i(HookLog.Module.CORE, "libxposed module loaded in ${param.processName}, api=$apiVersion")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        HookBridge.init(this)
        registry.dispatch(
            HookPackageParam(
                packageName = "android",
                classLoader = param.classLoader,
                processName = "system_server"
            )
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        HookBridge.init(this)
        registry.dispatch(
            HookPackageParam(
                packageName = param.packageName,
                classLoader = param.classLoader,
                processName = param.applicationInfo.processName
            )
        )
    }
}
