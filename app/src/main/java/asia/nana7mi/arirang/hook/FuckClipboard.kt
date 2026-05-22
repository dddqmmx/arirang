package asia.nana7mi.arirang.hook

import android.os.Binder
import android.os.Process
import android.os.UserHandle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FuckClipboard : BaseHookModule(matchSystem = true) {
    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val SYSTEM_UID_MAX = Process.FIRST_APPLICATION_UID - 1
        private val bypassPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.shell",
            "asia.nana7mi.arirang"
        )
    }

    private fun hookClipboard(targetClass: Class<*>) {
        val hookCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callingPackage = param.args.firstOrNull { it is String } as? String ?: return
                val uid = Binder.getCallingUid()
                val userId = (param.args.firstOrNull { it is Int } as? Int)
                    ?.takeIf { it >= 0 }
                    ?: runCatching {
                        XposedHelpers.callStaticMethod(UserHandle::class.java, "getUserId", uid) as Int
                    }.getOrDefault(uid / PER_USER_RANGE)

                if (uid == Process.INVALID_UID || shouldBypass(callingPackage, uid)) return

                val allowed = HookNotifyClient.requestClipboardReadAccess(callingPackage, uid, userId)
                if (!allowed) {
                    HookLog.i(HookLog.Module.CLIPBOARD, "denied read for $callingPackage uid=$uid")
                    param.result = null
                }
            }
        }

        XposedBridge.hookAllMethods(targetClass, "getPrimaryClip", hookCallback)
    }

    private fun shouldBypass(callingPackage: String, uid: Int): Boolean {
        if (callingPackage in bypassPackages) return true
        if (uid in 0..SYSTEM_UID_MAX) return true
        return false
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val clipboardService = XposedHelpers.findClass("com.android.server.clipboard.ClipboardService", lpparam.classLoader)
            val clipboardImpl = XposedHelpers.findClassIfExists(
                "com.android.server.clipboard.ClipboardService\$ClipboardImpl",
                lpparam.classLoader
            )
            hookClipboard(clipboardImpl ?: clipboardService)
            HookLog.i(HookLog.Module.CLIPBOARD, "hooked")
        }.onFailure {
            HookLog.e(HookLog.Module.CLIPBOARD, "hook failed", it)
        }
    }
}
