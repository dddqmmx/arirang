package asia.nana7mi.arirang.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import asia.nana7mi.arirang.BuildConfig

class CallerValidator(private val context: Context) {

    companion object {
        private val TRUSTED_CALLER_PACKAGES = setOf(
            "android",
            "com.android.phone",
            "com.google.android.gms",
            "com.android.networkstack",
            "com.android.networkstack.inprocess",
            "com.android.wifi",
            "com.android.bluetooth",
            BuildConfig.APPLICATION_ID
        )
    }

    fun isTrustedCaller(callingUid: Int): Boolean {
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.myUid()) {
            return true
        }

        val packages = context.packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty()
        return packages.any { it in TRUSTED_CALLER_PACKAGES }
    }

    fun isAuthorizedPackageForCaller(callingUid: Int, pkgName: String): Boolean {
        if (pkgName.isBlank()) {
            return false
        }

        if (callingUid == Process.SYSTEM_UID || callingUid == Process.myUid()) {
            return true
        }

        return context.packageManager.getPackagesForUid(callingUid)?.contains(pkgName) == true
    }

    fun isPackageOwnedByUid(uid: Int, pkgName: String): Boolean {
        return context.packageManager.getPackagesForUid(uid)?.contains(pkgName) == true
    }
}
