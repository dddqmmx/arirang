package asia.nana7mi.arirang.hook.location

import android.os.Binder
import asia.nana7mi.arirang.hook.core.HookLog

/**
 * Decides which [LocationProfile] applies to a hooked call.
 *
 * There are only two behaviours here, and telling them apart at a call site is
 * the whole point of this class:
 *
 *  - The `for*` family honours `config.perPackage`, so a per-app override — and
 *    a per-app opt-out, which is a profile with `enabled = false` — is applied.
 *    They differ only in how the calling package is identified.
 *  - [globalProfileIgnoringPerPackage] deliberately does not. It is only correct
 *    where no caller identity is recoverable.
 *
 * These were previously five similarly-named private methods on FuckLocation
 * (`resolveProfile`, `profileForPackage`, `profileForArgs`, `profileForReceiver`,
 * `globalRewriteProfile`, the second a pure alias of the first), combined into
 * eight different precedence chains across 60+ call sites. Because the
 * distinction was a naming accident rather than a visible one, a reader could
 * not tell whether a given call honoured the user's per-app opt-out.
 *
 * @param currentConfig reads the current location config snapshot.
 * @param activeDeliveryPackage the package currently being delivered to, if the
 *   delivery machinery has attributed one on this thread.
 */
internal class LocationProfileResolver(
    private val currentConfig: () -> LocationHookConfig,
    private val activeDeliveryPackage: () -> String?
) {

    /** Profile for the current Binder caller. Honours per-package overrides. */
    fun forCurrentCaller(): LocationProfile? = forPackage(null)

    /**
     * Profile for [packageName]. Honours per-package overrides. A null
     * [packageName] falls back to the active delivery package, then to the
     * calling UID.
     */
    fun forPackage(packageName: String?): LocationProfile? {
        val config = currentConfig()
        if (!config.enabled) return null

        val callingUid = Binder.getCallingUid()
        val pkg = packageName
            ?: activeDeliveryPackage()
            ?: LocationCallerResolver.packageNameForUid(callingUid)

        val profile = if (pkg != null && pkg != "android" && pkg != "com.google.android.gms") {
            config.perPackage[pkg] ?: config.defaultProfile
        } else {
            config.defaultProfile
        }

        if (profile.enabled) {
            val source = if (pkg != null && config.perPackage.containsKey(pkg)) "per-package" else "default"
            HookLog.d(HookLog.Module.LOCATION, "resolved $source location profile for uid=$callingUid")
            return profile
        }
        return null
    }

    /**
     * Profile for the caller named in a hooked method's [args], falling back to
     * the calling UID. Honours per-package overrides.
     */
    fun forArgs(args: Array<Any?>): LocationProfile? = forPackage(
        LocationCallerResolver.callerFromArgs(args)
            ?: LocationCallerResolver.packageNameForUid(Binder.getCallingUid())
    )

    /**
     * Profile for the caller behind a receiver/listener object, identified
     * reflectively. Honours per-package overrides.
     */
    fun forReceiver(receiver: Any?): LocationProfile? =
        forPackage(LocationCallerResolver.packageNameFromObject(receiver))

    /**
     * The global default profile, **ignoring `config.perPackage` entirely**.
     *
     * Use only where the caller genuinely cannot be identified: any call site
     * using this bypasses per-app overrides and per-app opt-outs, so the user's
     * "don't spoof this app" setting does not apply there.
     */
    fun globalProfileIgnoringPerPackage(): LocationProfile? {
        val config = currentConfig()
        if (!config.enabled || !config.defaultProfile.enabled) return null
        return config.defaultProfile
    }
}
