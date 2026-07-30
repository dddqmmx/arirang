package asia.nana7mi.arirang.hook.systemsetting

import android.os.LocaleList
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.afterHookedMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Reports a configured per-application locale from system_server.
 *
 * Rides Android 13+'s own per-app language plumbing rather than touching the
 * app: `LocaleManagerService` is the platform's record of "which locale is this
 * package set to", and both `LocaleManager.getApplicationLocales()` and the
 * ActivityTaskManager path that builds an app's Configuration read through it.
 *
 * Unlike [asia.nana7mi.arirang.hook.network.FuckVpnStatus] this does **not**
 * filter on the Binder caller. The question these methods answer is "what
 * locale is package X configured with", keyed on the package in the arguments,
 * not on who is asking — and the platform asking on the app's behalf is exactly
 * the case that has to be answered for the setting to take effect at all.
 */
class FuckAppLocale : BaseHookModule(matchSystem = true) {

    private val config = SystemSettingHookConfigFile.create()

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        var installed = 0

        // The Binder entry point apps call, on the inner stub.
        HookBridge.findClassIfExists(BINDER_SERVICE_CLASS, classLoader)?.let { binderService ->
            installed += HookBridge.hookAllMethods(
                binderService,
                "getApplicationLocales",
                localeHook()
            ).size
        }

        // The internal accessor the platform uses when composing an app's
        // Configuration; hooking only the Binder stub would report the locale
        // without the app actually being localised.
        HookBridge.findClassIfExists(SERVICE_CLASS, classLoader)?.let { service ->
            installed += HookBridge.hookAllMethods(
                service,
                "getApplicationLocalesUnchecked",
                localeHook()
            ).size
        }

        if (installed == 0) {
            HookLog.w(
                HookLog.Module.CORE,
                "LocaleManagerService not found; per-app language inactive"
            )
        } else {
            HookLog.i(HookLog.Module.CORE, "installed $installed per-app locale hook(s)")
        }
    }

    private fun localeHook(): XC_MethodHook = afterHookedMethod {
        if (hasThrowable()) return@afterHookedMethod
        val packageName = args.firstOrNull() as? String ?: return@afterHookedMethod
        val current = config.current()
        if (!current.enabled) return@afterHookedMethod

        val resolved = SystemSettingPrefs.resolveFor(current, packageName)
        if (!resolved.enabled) return@afterHookedMethod
        val languageTag = resolved.languageTag.takeIf { it.isNotEmpty() } ?: return@afterHookedMethod

        val locales = runCatching { LocaleList.forLanguageTags(languageTag) }
            .getOrNull()
            ?.takeIf { !it.isEmpty }
            ?: run {
                // An unresolvable tag degrades to the real locale rather than to
                // an empty LocaleList, which callers read as "no preference".
                HookLog.w(HookLog.Module.CORE, "ignoring unusable language tag '$languageTag'")
                return@afterHookedMethod
            }

        result = locales
        HookLog.d(HookLog.Module.CORE, "reported locale $languageTag for $packageName")
    }

    private companion object {
        private const val SERVICE_CLASS = "com.android.server.locales.LocaleManagerService"
        private const val BINDER_SERVICE_CLASS =
            "com.android.server.locales.LocaleManagerService\$LocaleManagerBinderService"
    }
}
