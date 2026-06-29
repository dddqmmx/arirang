package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.hook.core.BaseHookModule

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.util.callNoArg
import asia.nana7mi.arirang.hook.util.getFieldValue

import android.location.LocationManager

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
        return args.asSequence()
            .mapNotNull { arg ->
                when (arg) {
                    is String -> arg.takeIf { it.isLikelyPackageName() }
                    else -> packageNameFromObject(arg)
                }
            }
            .firstOrNull()
            ?: args.filterIsInstance<String>()
                .lastOrNull { it.isLikelyPackageName() }
    }

    fun packageNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        return runCatching {
            ArirangClient.getSystemContext()
                ?.packageManager
                ?.getPackagesForUid(uid)
                ?.firstOrNull()
        }.getOrNull()
    }

    fun packageNameFromObject(value: Any?): String? {
        value ?: return null
        if (value is String) return value.takeIf { it.isLikelyPackageName() }

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
                    packageNameFromObject(client)?.let { return it }
                }
            }
        }

        if (className == "com.google.android.gms.location.internal.LocationRequestUpdateData") {
            packageNameFromObject(getFieldValue(value, "b"))?.let { return it }
            (getFieldValue(value, "g") as? String)?.takeIf { it.isLikelyPackageName() }?.let { return it }
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
                packageNameFromObject(nested)?.let { return it }
            }
        }

        listOf("getCallerIdentity", "getIdentity", "getAttributionSource", "getWorkSource").forEach { methodName ->
            val nested = callNoArg(value, methodName)
            if (nested != null && nested !== value) {
                packageNameFromObject(nested)?.let { return it }
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
            val size = BaseHookModule.callMethod(workSource, "size") as? Int ?: return@runCatching null
            repeat(size) { index ->
                (BaseHookModule.callMethod(workSource, "getName", index) as? String)
                    ?.takeIf { it.isLikelyPackageName() && it != GMS_PACKAGE }
                    ?.let { return it }
            }
            repeat(size) { index ->
                val uid = BaseHookModule.callMethod(workSource, "getUid", index) as? Int ?: return@repeat
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

    private const val GMS_PACKAGE = "com.google.android.gms"
}
