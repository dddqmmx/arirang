package asia.nana7mi.arirang.hook.clipboard

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog

import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserHandle
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FuckClipboard : BaseHookModule(matchSystem = true) {
    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val SYSTEM_UID_MAX = Process.FIRST_APPLICATION_UID - 1

        //硬编码始终允许剪切板读取的应用列表
        private val bypassPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.shell",
            BuildConfig.APPLICATION_ID
        )

        // Anr豁免进程的Uid名单
        val pendingUids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val clipboardService = HookBridge.findClass("com.android.server.clipboard.ClipboardService", lpparam.classLoader)
            val clipboardImpl = HookBridge.findClassIfExists(
                "com.android.server.clipboard.ClipboardService\$ClipboardImpl",
                lpparam.classLoader
            )
            // 进行剪切板读取事件的拦截
            hookClipboard(clipboardImpl ?: clipboardService)
            hookAnrExemption(lpparam.classLoader)
            HookLog.i(HookLog.Module.CLIPBOARD, "hooked")
        }.onFailure {
            HookLog.e(HookLog.Module.CLIPBOARD, "hook failed", it)
        }
    }

    private fun hookClipboard(targetClass: Class<*>) {
        val hookCallback = beforeHookedMethod {
            val callingPackage = args.firstOrNull { it is String } as? String ?: return@beforeHookedMethod
            val uid = Binder.getCallingUid()
            val userId = (args.firstOrNull { it is Int } as? Int)
                ?.takeIf { it >= 0 }
                ?: runCatching {
                    HookBridge.callStaticMethod(UserHandle::class.java, "getUserId", uid) as Int
                }.getOrDefault(uid / PER_USER_RANGE)

            if (uid == Process.INVALID_UID || shouldBypass(callingPackage, uid)) return@beforeHookedMethod

            pendingUids.add(uid)
            try {
                val allowed = ArirangClient.requestClipboardReadAccess(callingPackage, uid, userId)
                if (!allowed) {
                    HookLog.i(HookLog.Module.CLIPBOARD, "denied read for $callingPackage uid=$uid")
                    result = null
                }
            } finally {
                pendingUids.remove(uid)
            }
        }

        HookBridge.hookAllMethods(targetClass, "getPrimaryClip", hookCallback)
    }

    private fun shouldBypass(callingPackage: String, uid: Int): Boolean {
        if (callingPackage in bypassPackages) return true
        if (uid in 0..SYSTEM_UID_MAX) return true
        return false
    }

    private fun hookAnrExemption(classLoader: ClassLoader) {
        val hookCallback = object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uid = runCatching {
                    if (param.thisObject.javaClass.simpleName == "ProcessRecord") {
                        HookBridge.getIntField(param.thisObject, "uid")
                    } else {
                        HookBridge.getIntField(param.thisObject, "mUid")
                    }
                }.getOrNull()

                if (uid != null && pendingUids.contains(uid)) {
                    HookLog.i(HookLog.Module.CLIPBOARD, "Bypassing ANR for uid $uid via isDebugging spoof")
                    param.result = true
                }
            }
        }

        runCatching {
            val processRecordClass = HookBridge.findClass("com.android.server.am.ProcessRecord", classLoader)
            HookBridge.hookAllMethods(processRecordClass, "isDebugging", hookCallback)
        }
        runCatching {
            val wpcClass = HookBridge.findClass("com.android.server.wm.WindowProcessController", classLoader)
            HookBridge.hookAllMethods(wpcClass, "isDebugging", hookCallback)
        }
    }
}
