package asia.nana7mi.arirang.hook.systemsetting

import android.content.res.Configuration
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
 * app. Two surfaces must agree for the setting to take effect:
 *
 * 1. **Configuration path** — when a process or activity is created,
 *    `PackageConfigPersister.updateConfigIfNeeded` / `findPackageConfiguration`
 *    supply the package's override locales, which
 *    `ConfigurationContainer.applyAppSpecificConfig` writes into the process
 *    Configuration. Hooking only the LocaleManager query leaves this path on
 *    the real (usually empty) package config, so the app never localises.
 * 2. **Query path** — `LocaleManager.getApplicationLocales()` and the internal
 *    unchecked accessor read the same package config; spoofing them keeps
 *    Settings / installer / IME views consistent with the Configuration path.
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

        installed += hookPackageConfigPersister(classLoader)
        installed += hookLocaleManagerQueries(classLoader)

        if (installed == 0) {
            HookLog.w(
                HookLog.Module.CORE,
                "per-app language hooks not installed (PackageConfigPersister / LocaleManagerService missing)"
            )
        } else {
            HookLog.i(HookLog.Module.CORE, "installed $installed per-app locale hook(s)")
        }
    }

    /**
     * The path that actually localises the app: process start and activity
     * creation pull package locales from [PackageConfigPersister] and apply
     * them to the Configuration tree.
     */
    private fun hookPackageConfigPersister(classLoader: ClassLoader): Int {
        val persister = HookBridge.findClassIfExists(PERSISTER_CLASS, classLoader) ?: return 0
        var count = 0

        // Process / activity creation. The stock method only applies a locale
        // when a real PackageConfigRecord exists; after it runs we re-apply with
        // our locales so the container's override Configuration is updated even
        // when the package has never set an app language itself.
        count += HookBridge.hookAllMethods(
            persister,
            "updateConfigIfNeeded",
            afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val packageName = args.getOrNull(2) as? String ?: return@afterHookedMethod
                val locales = resolveLocales(packageName) ?: return@afterHookedMethod
                val container = args.getOrNull(0) ?: return@afterHookedMethod
                if (!applyLocalesToContainer(container, locales)) {
                    HookLog.w(
                        HookLog.Module.CORE,
                        "applyAppSpecificConfig failed for $packageName"
                    )
                    return@afterHookedMethod
                }
                HookLog.d(HookLog.Module.CORE, "applied locale override for $packageName")
            }
        ).size

        // Query surface shared with LocaleManagerService and activity-embedding
        // locale alignment: rewrite the returned PackageConfig so mLocales
        // carries our tag while preserving night mode / gender from any real
        // package-specific config.
        count += HookBridge.hookAllMethods(
            persister,
            "findPackageConfiguration",
            afterHookedMethod {
                if (hasThrowable()) return@afterHookedMethod
                val packageName = args.firstOrNull() as? String ?: return@afterHookedMethod
                val locales = resolveLocales(packageName) ?: return@afterHookedMethod
                val packageConfigClass = HookBridge.findClassIfExists(
                    PACKAGE_CONFIG_CLASS,
                    classLoader
                ) ?: return@afterHookedMethod
                val nightMode = result?.let { readIntegerField(it, "mNightMode") }
                val gender = result?.let { readIntegerField(it, "mGrammaticalGender") }
                result = newPackageConfig(packageConfigClass, nightMode, locales, gender)
                    ?: return@afterHookedMethod
                HookLog.d(HookLog.Module.CORE, "reported PackageConfig locale for $packageName")
            }
        ).size

        return count
    }

    private fun hookLocaleManagerQueries(classLoader: ClassLoader): Int {
        var count = 0
        val hook = localeQueryHook()

        HookBridge.findClassIfExists(BINDER_SERVICE_CLASS, classLoader)?.let { binderService ->
            count += HookBridge.hookAllMethods(
                binderService,
                "getApplicationLocales",
                hook
            ).size
        }

        HookBridge.findClassIfExists(SERVICE_CLASS, classLoader)?.let { service ->
            count += HookBridge.hookAllMethods(
                service,
                "getApplicationLocalesUnchecked",
                hook
            ).size
        }

        return count
    }

    private fun localeQueryHook(): XC_MethodHook = afterHookedMethod {
        if (hasThrowable()) return@afterHookedMethod
        val packageName = args.firstOrNull() as? String ?: return@afterHookedMethod
        val locales = resolveLocales(packageName) ?: return@afterHookedMethod
        result = locales
        HookLog.d(HookLog.Module.CORE, "reported locale query for $packageName")
    }

    /**
     * Resolved [LocaleList] for [packageName], or null when this package must
     * keep the real locale (feature off, package exempt, or empty tag).
     */
    private fun resolveLocales(packageName: String): LocaleList? {
        val current = config.current()
        if (!current.enabled) return null
        val resolved = SystemSettingPrefs.resolveFor(current, packageName)
        if (!resolved.enabled) return null
        val languageTag = resolved.languageTag.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocaleList.forLanguageTags(languageTag) }
            .getOrNull()
            ?.takeIf { !it.isEmpty }
            ?: run {
                HookLog.w(HookLog.Module.CORE, "ignoring unusable language tag '$languageTag'")
                null
            }
    }

    /**
     * Re-invokes `applyAppSpecificConfig` on [container] so only the locale
     * half changes. Night mode is left alone (`null`); grammatical gender is
     * re-supplied from the container's current override so a real app-specific
     * gender is not wiped by the second call (stock always re-evaluates gender
     * even when the argument is null).
     *
     * **Deliberately does not combine with system locales.** Stock
     * `LocaleOverlayHelper.combineLocalesIfOverlayExists` prepends the
     * app-specific tag and then appends the full system list. With more than
     * one entry, `ResourcesImpl` reorders Configuration so the first *resource-
     * supported* locale wins — for an app that only ships `ja`/`en` that puts
     * the real system language first and leaks it through
     * `Configuration.locales[0]` / `Locale.getDefault()`. Passing the spoofed
     * list alone (typically a single tag) keeps every in-process channel on
     * the spoofed primary; AssetManager still falls back for missing strings.
     */
    private fun applyLocalesToContainer(container: Any, locales: LocaleList): Boolean {
        val gender = currentOverrideGender(container)
        val candidates = container.javaClass.methods.filter { method ->
            method.name == "applyAppSpecificConfig"
        }
        for (method in candidates) {
            val applied = runCatching {
                method.isAccessible = true
                when (method.parameterCount) {
                    3 -> method.invoke(container, null, locales, gender)
                    2 -> method.invoke(container, null, locales)
                    else -> return@runCatching false
                }
                true
            }.getOrDefault(false)
            if (applied) return true
        }
        return false
    }

    private fun currentOverrideGender(container: Any): Int? {
        val override = runCatching {
            HookBridge.callMethod(container, "getRequestedOverrideConfiguration")
        }.getOrNull() as? Configuration ?: return null
        return runCatching { override.grammaticalGender }.getOrNull()
    }

    private fun newPackageConfig(
        packageConfigClass: Class<*>,
        nightMode: Int?,
        locales: LocaleList,
        gender: Int?
    ): Any? {
        // Prefer the three-arg AOSP constructor (night, locales, gender). Null
        // arguments need explicit boxed-Integer parameter types so reflection
        // can bind them (primitive int cannot be null).
        val integerClass = Class.forName("java.lang.Integer")
        val threeArg = runCatching {
            packageConfigClass
                .getDeclaredConstructor(integerClass, LocaleList::class.java, integerClass)
                .apply { isAccessible = true }
                .newInstance(nightMode, locales, gender)
        }.getOrNull()
        if (threeArg != null) return threeArg

        return runCatching {
            packageConfigClass
                .getDeclaredConstructor(integerClass, LocaleList::class.java)
                .apply { isAccessible = true }
                .newInstance(nightMode, locales)
        }.onFailure {
            HookLog.w(
                HookLog.Module.CORE,
                "PackageConfig construction failed: ${it.message}"
            )
        }.getOrNull()
    }

    private fun readIntegerField(instance: Any, fieldName: String): Int? {
        return runCatching {
            HookBridge.getObjectField(instance, fieldName) as? Int
        }.getOrNull()
    }

    private companion object {
        private const val SERVICE_CLASS = "com.android.server.locales.LocaleManagerService"
        private const val BINDER_SERVICE_CLASS =
            "com.android.server.locales.LocaleManagerService\$LocaleManagerBinderService"
        private const val PERSISTER_CLASS = "com.android.server.wm.PackageConfigPersister"
        private const val PACKAGE_CONFIG_CLASS =
            "com.android.server.wm.ActivityTaskManagerInternal\$PackageConfig"
    }
}
