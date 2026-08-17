package asia.nana7mi.arirang.hook.activation

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.afterHookedMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

// Diagnostic: dump every DetectionData returned by com.reveny.nativecheck's
// native detector, so the exact sub-checks behind "Detected Zygisk (2)" are
// visible. Development-only instrumentation.
class NativeCheckDump : BaseHookModule(targetPackages = setOf("com.reveny.nativecheck")) {
    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookLog.i(HookLog.Module.CORE, "NC-DUMP onHook for ${lpparam.packageName}")
        val native = HookBridge.findClassIfExists(
            "com.reveny.nativecheck.app.Native", lpparam.classLoader
        )
        HookLog.i(HookLog.Module.CORE, "NC-DUMP Native class = $native")
        if (native == null) return
        HookBridge.hookAllMethods(native, "getDetections", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val arr = param.result as? Array<*> ?: return
                HookLog.i(HookLog.Module.CORE, "NC-DUMP total=${arr.size}")
                for (o in arr) {
                    if (o == null) continue
                    val clazz = o::class.java
                    val name = try { clazz.getMethod("getName").invoke(o) as? String ?: "" } catch (t: Throwable) { "<err>" }
                    val desc = try { clazz.getMethod("getDescription").invoke(o) as? String ?: "" } catch (t: Throwable) { "<err>" }
                    HookLog.i(HookLog.Module.CORE, "NC-DUMP  entry name=[$name]")
                    HookLog.i(HookLog.Module.CORE, "NC-DUMP        desc=[$desc]")
                }
            }
        })
        HookLog.i(HookLog.Module.CORE, "NC-DUMP hooks installed")
        probeZygoteMapsAccess()
    }

    // Diagnostic: determine whether this app process can actually read the
    // zygote's /proc maps (tests the "zygote-side mapping count" theory).
    private fun probeZygoteMapsAccess() {
        try {
            val z64 = findPidByCmdline("zygote64")
            val z = findPidByCmdline("zygote")
            HookLog.i(HookLog.Module.CORE, "NC-DUMP probe zygote64=$z64 zygote=$z")
            for (pid in listOf(z64, z)) {
                if (pid.isEmpty()) continue
                val pidPart = pid.split(" ").first()
                val f = java.io.File("/proc/$pidPart/maps")
                HookLog.i(HookLog.Module.CORE, "NC-DUMP probe /proc/$pidPart/maps exists=${f.exists()} readable=${f.canRead()}")
                if (f.exists()) {
                    val lines = runCatching { f.readLines() }.getOrElse { listOf("<err ${it.message}>") }
                    val hits = lines.filter { it.contains("zygisk") || it.contains("arirang") || it.contains("libmod") }
                    HookLog.i(HookLog.Module.CORE, "NC-DUMP probe /proc/$pidPart/maps lines=${lines.size} zygiskHits=${hits.size} first=${hits.firstOrNull()?.trim() ?: "-"}")
                }
            }
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.CORE, "NC-DUMP probe error: $t")
        }
    }

    private fun findPidByCmdline(name: String): String {
        val root = java.io.File("/proc")
        val dirs = root.listFiles()
        HookLog.i(HookLog.Module.CORE, "NC-DUMP probe /proc listing=${dirs?.size} err=${root.canRead()}")
        return dirs?.filter { it.name.all { c -> c.isDigit() } }?.firstOrNull { dir ->
            runCatching {
                val b = java.io.File(dir, "cmdline").readBytes()
                val idx = b.indexOf(0.toByte())
                val c = if (idx >= 0) String(b, 0, idx) else String(b)
                HookLog.i(HookLog.Module.CORE, "NC-DUMP probe cmdline ${dir.name}=$c")
                c == name
            }.getOrElse { false }
        }?.name ?: ""
    }
}