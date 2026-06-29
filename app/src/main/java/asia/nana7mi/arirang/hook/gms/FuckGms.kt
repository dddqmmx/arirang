package asia.nana7mi.arirang.hook.gms

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IInterface
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val GMS_PACKAGE = "com.google.android.gms"

class FuckGms : BaseHookModule(targetPackages = setOf(GMS_PACKAGE)) {
    @Volatile
    private var gmsContext: Context? = null

    private val configStore = GmsIdentifierConfigStore { gmsContext }
    private val advertisingIdHooks = GmsAdvertisingIdHooks(::currentConfig)
    private val appSetHooks = GmsAppSetHooks(::currentConfig)

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookApplicationContext(lpparam.classLoader)
        hookBinderInterfaceAttachment()
        appSetHooks.hookCallbackBinderProxy(lpparam.classLoader)
        HookLog.i(HookLog.Module.GMS, "GMS identifier hook installed")
    }

    private fun hookApplicationContext(classLoader: ClassLoader) {
        val applicationClass = HookBridge.findClassIfExists("android.app.Application", classLoader)
            ?: Application::class.java
        HookBridge.hookAllMethods(applicationClass, "onCreate", afterHookedMethod {
            val app = thisObject as? Application ?: return@afterHookedMethod
            gmsContext = app.applicationContext
            ArirangClient.autoBindCurrentUser(app)
        })
    }

    private fun hookBinderInterfaceAttachment() {
        HookBridge.findAndHookMethod(
            Binder::class.java,
            "attachInterface",
            IInterface::class.java,
            String::class.java,
            afterHookedMethod {
                val descriptor = args.getOrNull(1) as? String ?: return@afterHookedMethod
                val owner = args.getOrNull(0) ?: return@afterHookedMethod
                when (descriptor) {
                    GmsAdvertisingIdHooks.ADS_IDENTIFIER_DESCRIPTOR ->
                        advertisingIdHooks.hookService(owner.javaClass)
                    GmsAppSetHooks.APP_SET_SERVICE_DESCRIPTOR ->
                        appSetHooks.hookService(owner.javaClass)
                }
            }
        )
    }

    private fun currentConfig(): GmsIdentifierConfig {
        return configStore.current()
    }
}
