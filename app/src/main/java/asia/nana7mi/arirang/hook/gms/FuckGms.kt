package asia.nana7mi.arirang.hook.gms

import asia.nana7mi.arirang.hook.ipc.ArirangClient
import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.api.HookPackageParam
import asia.nana7mi.arirang.hook.target.Targets

private const val GMS_PACKAGE = "com.google.android.gms"

class FuckGms : BaseHookModule(targetPackages = setOf(GMS_PACKAGE)) {
    // unique_identifier_prefs is MODE_PRIVATE, so another process cannot read it and
    // isEnabled() cannot be answered before the bind completes.
    override val installsBeforeConfigIsKnown: Boolean = true

    private val advertisingIdHooks = GmsAdvertisingIdHooks(::currentConfig)
    private val appSetHooks = GmsAppSetHooks(::currentConfig)

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(param: HookPackageParam) {
        Targets.onApplicationContext { context ->
            ArirangClient.autoBindCurrentUser(context)
        }
        Targets.onInterfaceAttached(GmsAdvertisingIdHooks.ADS_IDENTIFIER_DESCRIPTOR) { owner ->
            advertisingIdHooks.hookService(owner.javaClass)
        }
        Targets.onInterfaceAttached(GmsAppSetHooks.APP_SET_SERVICE_DESCRIPTOR) { owner ->
            appSetHooks.hookService(owner.javaClass)
        }
        appSetHooks.hookCallbackBinderProxy(param.classLoader)
        HookLog.i(HookLog.Module.GMS, "GMS identifier hook installed")
    }

    private fun currentConfig(): GmsIdentifierConfig = UniqueIdentifierConfig.current()
}
