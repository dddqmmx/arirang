package asia.nana7mi.arirang.hook.api

import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs
import asia.nana7mi.arirang.hook.activation.XposedActivation
import asia.nana7mi.arirang.hook.bluetooth.FuckBluetooth
import asia.nana7mi.arirang.hook.clipboard.FuckClipboard
import asia.nana7mi.arirang.hook.config.xSharedPreferences
import asia.nana7mi.arirang.hook.gms.FuckGms
import asia.nana7mi.arirang.hook.location.FuckLocation
import asia.nana7mi.arirang.hook.network.FuckVpnStatus
import asia.nana7mi.arirang.hook.packagelist.FuckPackageList
import asia.nana7mi.arirang.hook.process.FuckProcess
import asia.nana7mi.arirang.hook.settings.FuckSettingsProvider
import asia.nana7mi.arirang.hook.sim.FuckSim
import asia.nana7mi.arirang.hook.systemserver.SystemServerHook
import asia.nana7mi.arirang.hook.systemsetting.FuckAppLocale
import asia.nana7mi.arirang.hook.wifi.FuckWifi
import asia.nana7mi.arirang.hook.xposed.HookLog

/**
 * The modules this build installs, and the policy for dispatching a loaded package to them.
 *
 * Deliberately free of libxposed types: [asia.nana7mi.arirang.hook.xposed.ArirangModule] is the
 * only class the framework instantiates by name, and it translates the framework's parameters
 * into [HookPackageParam] before calling [dispatch]. That keeps the module list and the
 * dispatch policy testable and independent of the framework.
 */
class HookRegistry {

    private val modules = listOf(
        SystemServerHook(),
        FuckClipboard(),
        FuckSim(),
        FuckWifi(),
        FuckBluetooth(),
        FuckLocation(),
        FuckSettingsProvider(),
        FuckGms(),
        FuckProcess(),
        FuckPackageList(),
        FuckVpnStatus(),
        FuckAppLocale(),
        XposedActivation()
    )

    fun dispatch(pkgParam: HookPackageParam) {
        val prefs = runCatching {
            xSharedPreferences(GlobalConfigPrefs.PREFS_NAME)
        }.getOrNull()
        val restrictHotSwitching = prefs?.getBoolean(GlobalConfigPrefs.KEY_RESTRICT_HOT_SWITCHING, false) ?: false

        HookLog.d(
            HookLog.Module.CORE,
            "dispatch(${pkgParam.packageName}) restrictHotSwitching=$restrictHotSwitching"
        )
        modules
            .filter { it.matches(pkgParam.packageName) }
            .filter { module ->
                !restrictHotSwitching || module.installsBeforeConfigIsKnown || module.isEnabled()
            }
            .forEach { module ->
                runCatching {
                    module.onHook(pkgParam)
                }.onFailure {
                    HookLog.e(
                        HookLog.Module.CORE,
                        "module ${module.javaClass.simpleName} failed for ${pkgParam.packageName}",
                        it
                    )
                }
            }
    }

}
