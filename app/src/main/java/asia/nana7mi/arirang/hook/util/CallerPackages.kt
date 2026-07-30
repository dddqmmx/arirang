package asia.nana7mi.arirang.hook.util

import asia.nana7mi.arirang.hook.core.ArirangClient
import java.util.concurrent.ConcurrentHashMap

/**
 * uid -> owning packages, for hooks that need to key behaviour on the caller.
 *
 * `LocationCallerResolver.packageNameForUid` answers a narrower question — the
 * *single* owning package, i.e. null for any shared UID — which is right for
 * picking one location profile but wrong for an allow-list check, where every
 * package behind the UID matters. The caching discipline is the same, and for
 * the same reason: the lookup is a synchronous Binder round-trip to
 * PackageManagerService sitting behind hooks that run on every framework call.
 */
internal object CallerPackages {

    private val packagesByUid = ConcurrentHashMap<Int, List<String>>()

    /** Every package sharing [uid], or empty when it cannot be resolved yet. */
    fun forUid(uid: Int): List<String> {
        if (uid <= 0) return emptyList()
        packagesByUid[uid]?.let { return it }

        val resolved = runCatching {
            ArirangClient.getSystemContext()
                ?.packageManager
                ?.getPackagesForUid(uid)
                ?.filterNotNull()
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())

        // Only memoise once a system context exists: before that an empty result
        // means "too early to ask", not "this uid owns nothing".
        if (ArirangClient.getSystemContext() != null) {
            packagesByUid[uid] = resolved
        }
        return resolved
    }

    /**
     * Whether [uid] belongs to the platform rather than an installed app.
     *
     * Framework internals reach these services in-process, where
     * `Binder.getCallingUid()` reports the system's own uid. Rewriting results
     * for those callers would feed spoofed data back into the platform's own
     * connectivity and locale logic, so every hook has to skip them.
     */
    fun isPlatformCaller(uid: Int): Boolean = Math.floorMod(uid, PER_USER_RANGE) < FIRST_APPLICATION_UID

    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APPLICATION_UID = 10_000
}
