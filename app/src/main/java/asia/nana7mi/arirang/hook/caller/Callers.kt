package asia.nana7mi.arirang.hook.caller

import android.location.LocationManager
import asia.nana7mi.arirang.hook.ipc.ArirangClient
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.util.callNoArg
import asia.nana7mi.arirang.hook.util.getFieldValue
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is asking, for hooks that key their answer on the caller.
 *
 * Three different questions live behind this interface, and keeping them apart is the point:
 *
 *  - [isPlatform] — the caller is the platform, not an app. Framework internals reach these
 *    services in-process, where `Binder.getCallingUid()` reports the system's own uid, and
 *    rewriting results for them would feed spoofed data back into the platform's own
 *    connectivity, locale or telephony logic. Almost every hook has to skip them, so the check
 *    is cheap and free of IPC.
 *  - [packagesFor] — *every* package sharing the uid. This is the allow-list question: a shared
 *    UID must not borrow a more permissive sibling's rule, so all of them have to be considered.
 *  - [packageFor] — the *single* owning package, or null when the uid is shared. This is the
 *    per-app-profile question, where "we cannot tell which app this is" has to stay unanswerable
 *    rather than resolve to an arbitrary sibling.
 *
 * They were previously three separate implementations — `CallerPackages.forUid`,
 * `LocationCallerResolver.packageNameForUid`, and a per-UID cache inside `FuckPackageList` — and
 * conflating them produced wrong answers in both directions.
 *
 * Both lookups are Binder round-trips to PackageManagerService sitting behind hooks that run on
 * every framework call, so both are cached for the process lifetime; a uid's package set only
 * changes across install/uninstall, which gives the uid a new mapping rather than mutating this
 * one. Neither caches an answer obtained before a system Context exists, because there an empty
 * result means "too early to ask", not "this uid owns nothing".
 */
internal object Callers {

    /**
     * Whether [uid] belongs to the platform rather than an installed app.
     *
     * Deliberately folds the per-user range: a secondary user's `system_server` or `systemui` is
     * as much a platform caller as the primary user's.
     *
     * Note this is *wider* than `FuckClipboard`'s access gate, which grants a bypass only to a
     * true system uid (`uid < Process.FIRST_APPLICATION_UID`) because that decision is
     * fail-closed. The two are not interchangeable, and are spelled differently on purpose.
     */
    fun isPlatform(uid: Int): Boolean = Math.floorMod(uid, PER_USER_RANGE) < FIRST_APPLICATION_UID

    private val packagesByUid = ConcurrentHashMap<Int, List<String>>()

    /** Every package sharing [uid], or empty when it cannot be resolved yet. */
    fun packagesFor(uid: Int): List<String> {
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
     * uid -> the single owning package, cached *including* nulls.
     *
     * Nulls are cached too, since "this uid has no single owning package" is the common case for
     * shared UIDs and is exactly what we do not want to re-derive on every call. A uid's package
     * set only changes across install/uninstall, which gives the uid a new mapping rather than
     * mutating this one.
     */
    private val singlePackageByUid = ConcurrentHashMap<Int, Optional<String>>()

    /** The package that owns [uid], or null when the uid is shared or not resolvable yet. */
    fun packageFor(uid: Int): String? {
        if (uid <= 0) return null
        singlePackageByUid[uid]?.let { return it.orElse(null) }

        val resolved = runCatching {
            ArirangClient.getSystemContext()
                ?.packageManager
                ?.getPackagesForUid(uid)
                ?.asSequence()
                ?.filter { it.isLikelyPackageName() }
                ?.distinct()
                ?.sorted()
                ?.toList()
                ?.singleOrNull()
        }.getOrNull()

        // Only cache once a system context exists; before that a null means "too
        // early to ask", not "no package", and must not be memoised.
        if (ArirangClient.getSystemContext() != null) {
            singlePackageByUid[uid] = Optional.ofNullable(resolved)
        }
        return resolved
    }

    /** The package named in a hooked method's [args], if any names one. */
    fun fromArgs(args: Array<Any?>): String? {
        args.filterIsInstance<String>()
            .firstOrNull { it.isLikelyExternalPackageName() }
            ?.let { return it }

        args.asSequence()
            .mapNotNull(::directPackageNameFromObject)
            .firstOrNull()
            ?.let { return it }

        return args.asSequence()
            .mapNotNull(::fromObject)
            .firstOrNull()
    }

    /** The package behind a callback or listener object, identified reflectively. */
    fun fromObject(value: Any?): String? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return packageNameFromObject(value, visited, 0)
    }

    /** Drops both caches, for a caller that knows an install changed the uid mapping. */
    fun invalidate() {
        packagesByUid.clear()
        singlePackageByUid.clear()
    }

    private fun packageNameFromObject(
        value: Any?,
        visited: MutableSet<Any>,
        depth: Int
    ): String? {
        value ?: return null
        if (value is String) return value.takeIf { it.isLikelyExternalPackageName() }
        if (depth > MAX_RESOLUTION_DEPTH || !visited.add(value)) return null

        directPackageNameFromObject(value)?.let { return it }
        packageNameFromWorkSource(value)?.let { return it }

        val className = value.javaClass.name
        if (className == "com.google.android.gms.common.internal.ClientIdentity") {
            (getFieldValue(value, "b") as? String)?.takeIf { it.isLikelyPackageName() }?.let { return it }
        }

        if (className == "com.google.android.gms.location.internal.LocationRequestInternal") {
            listOf("mClientIdentities", "clients", "zzb").forEach { fieldName ->
                val clients = getFieldValue(value, fieldName) as? List<*>
                clients?.forEach { client ->
                    packageNameFromObject(client, visited, depth + 1)?.let { return it }
                }
            }
        }

        if (className == "com.google.android.gms.location.internal.LocationRequestUpdateData") {
            packageNameFromObject(getFieldValue(value, "b"), visited, depth + 1)?.let { return it }
            (getFieldValue(value, "g") as? String)?.takeIf { it.isLikelyExternalPackageName() }?.let { return it }
        }

        listOf(
            "mCallerIdentity",
            "mIdentity",
            "mCaller",
            "mAttributionSource",
            "attributionSource",
            "mWorkSource",
            "workSource",
            "zza",
            "zzb",
            "zzc"
        ).forEach { fieldName ->
            val nested = getFieldValue(value, fieldName)
            if (nested != null && nested !== value) {
                packageNameFromObject(nested, visited, depth + 1)?.let { return it }
            }
        }

        listOf("getCallerIdentity", "getIdentity", "getAttributionSource", "getWorkSource").forEach { methodName ->
            val nested = callNoArg(value, methodName)
            if (nested != null && nested !== value) {
                packageNameFromObject(nested, visited, depth + 1)?.let { return it }
            }
        }

        return null
    }

    private fun directPackageNameFromObject(value: Any?): String? {
        value ?: return null
        if (value is String) return value.takeIf { it.isLikelyPackageName() }

        listOf("mPackageName", "packageName", "mPackage", "package", "zza", "zzb", "zzc").forEach { fieldName ->
            (getFieldValue(value, fieldName) as? String)
                ?.takeIf { it.isLikelyPackageName() && it != GMS_PACKAGE }
                ?.let { return it }
        }

        listOf("getPackageName", "getPackage").forEach { methodName ->
            (callNoArg(value, methodName) as? String)
                ?.takeIf { it.isLikelyPackageName() && it != GMS_PACKAGE }
                ?.let { return it }
        }

        return null
    }

    private fun packageNameFromWorkSource(value: Any?): String? {
        val workSource = value?.takeIf { it.javaClass.name == "android.os.WorkSource" } ?: return null
        return runCatching {
            val size = HookBridge.callMethod(workSource, "size") as? Int
                ?: return@runCatching null
            repeat(size) { index ->
                (HookBridge.callMethod(workSource, "getName", index) as? String)
                    ?.takeIf { it.isLikelyPackageName() && it != GMS_PACKAGE }
                    ?.let { return it }
            }
            repeat(size) { index ->
                val uid = HookBridge.callMethod(workSource, "getUid", index) as? Int
                    ?: return@repeat
                packageFor(uid)
                    ?.takeIf { it != GMS_PACKAGE }
                    ?.let { return it }
            }
            null
        }.getOrNull()
    }

    private fun String.isLikelyPackageName(): Boolean {
        return contains('.') &&
            this != LocationManager.GPS_PROVIDER &&
            this != LocationManager.NETWORK_PROVIDER &&
            this != LocationManager.FUSED_PROVIDER
    }

    private fun String.isLikelyExternalPackageName(): Boolean {
        return isLikelyPackageName() && this != GMS_PACKAGE
    }

    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APPLICATION_UID = 10_000
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val MAX_RESOLUTION_DEPTH = 8
}
