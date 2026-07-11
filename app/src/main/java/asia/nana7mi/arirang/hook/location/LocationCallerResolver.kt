package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.hook.core.HookBridge

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.util.callNoArg
import asia.nana7mi.arirang.hook.util.getFieldValue

import android.location.LocationManager
import java.util.Collections
import java.util.IdentityHashMap

internal object LocationCallerResolver {
    fun providerFromArgs(args: Array<Any?>): String {
        return args.firstOrNull {
            it == LocationManager.GPS_PROVIDER ||
                it == LocationManager.NETWORK_PROVIDER ||
                it == LocationManager.FUSED_PROVIDER
        } as? String
            ?: LocationManager.GPS_PROVIDER
    }

    fun callerFromArgs(args: Array<Any?>): String? {
        args.filterIsInstance<String>()
            .firstOrNull { it.isLikelyExternalPackageName() }
            ?.let { return it }

        args.asSequence()
            .mapNotNull(::directPackageNameFromObject)
            .firstOrNull()
            ?.let { return it }

        return args.asSequence()
            .mapNotNull(::packageNameFromObject)
            .firstOrNull()
    }

    fun packageNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        return runCatching {
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
    }

    fun packageNameFromObject(value: Any?): String? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return packageNameFromObject(value, visited, 0)
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
            val size = HookBridge.callMethod(workSource, "size") as? Int ?: return@runCatching null
            repeat(size) { index ->
                (HookBridge.callMethod(workSource, "getName", index) as? String)
                    ?.takeIf { it.isLikelyPackageName() && it != GMS_PACKAGE }
                    ?.let { return it }
            }
            repeat(size) { index ->
                val uid = HookBridge.callMethod(workSource, "getUid", index) as? Int ?: return@repeat
                packageNameForUid(uid)
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

    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val MAX_RESOLUTION_DEPTH = 8
}
