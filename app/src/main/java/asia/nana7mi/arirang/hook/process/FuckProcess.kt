package asia.nana7mi.arirang.hook.process

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog

import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class FuckProcess : BaseHookModule(matchSystem = true, matchClient = false) {

    /**
     * MANDATORY DESIGN COMPLIANCE: Arirang is a system-level privacy model.
     *
     * To avoid arbitrary third-party app injection and minimize performance impact,
     * hooks are restricted to framework-level components. 
     * 
     * 1. Protection for apps is achieved by spoofing the source of truth (system_server)
     *    or by globally modifying system state (resetprop).
     * 2. DO NOT add third-party apps (including self-check tools) to the match list.
     */
    override fun matches(packageName: String): Boolean {
        return packageName == "android" || packageName == "com.android.phone"
    }

    override fun isEnabled(): Boolean = true // Always active if module is loaded, rely on underlying SystemProperties

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return // Don't hook system_server ProcessBuilder

        runCatching {
            val processBuilderClass = HookBridge.findClass("java.lang.ProcessBuilder", lpparam.classLoader)
            
            HookBridge.hookAllMethods(processBuilderClass, "start", beforeHookedMethod {
                val pb = this.thisObject as ProcessBuilder
                val cmd = pb.command()
                
                if (cmd.isNotEmpty() && (cmd[0] == "getprop" || cmd[0] == "/system/bin/getprop")) {
                    val key = cmd.getOrNull(1)
                    if (key != null) {
                        // Let's call the Java SystemProperties.get() which is ALREADY SPOOFED by our native zygisk module.
                        val spoofedValue = runCatching {
                            val spClass = HookBridge.findClass("android.os.SystemProperties", lpparam.classLoader)
                            HookBridge.callStaticMethod(spClass, "get", key, "") as String
                        }.getOrDefault("")

                        HookLog.i(HookLog.Module.CORE, "Spoofed getprop execution for key: $key -> $spoofedValue")

                        this.result = MockProcess(spoofedValue + "\n")
                    }
                }
            })
            HookLog.i(HookLog.Module.CORE, "FuckProcess installed for ${lpparam.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.CORE, "FuckProcess failed for ${lpparam.packageName}", it)
        }
    }

    private class MockProcess(private val output: String) : Process() {
        override fun getOutputStream(): OutputStream {
            return object : OutputStream() {
                override fun write(b: Int) {}
            }
        }

        override fun getInputStream(): InputStream {
            return ByteArrayInputStream(output.toByteArray(Charsets.UTF_8))
        }

        override fun getErrorStream(): InputStream {
            return ByteArrayInputStream(ByteArray(0))
        }

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {}
    }
}
