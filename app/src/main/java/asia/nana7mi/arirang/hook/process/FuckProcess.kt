package asia.nana7mi.arirang.hook.process

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.core.beforeHookedMethod
import asia.nana7mi.arirang.hook.core.HookPackageParam
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class FuckProcess : BaseHookModule(matchSystem = true) {

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

    override fun onHook(param: HookPackageParam) {
        if (param.packageName == "android") return // Don't hook system_server ProcessBuilder

        runCatching {
            val processBuilderClass = HookBridge.findClass("java.lang.ProcessBuilder", param.classLoader)
            
            HookBridge.hookAllMethods(processBuilderClass, "start", beforeHookedMethod {
                val pb = this.thisObject as ProcessBuilder
                val cmd = pb.command()
                
                if (cmd.isNotEmpty() && (cmd[0] == "getprop" || cmd[0] == "/system/bin/getprop")) {
                    val propName = if (cmd.size > 1) cmd[1] else null
                    if (propName != null) {
                        val spClass = HookBridge.findClass("android.os.SystemProperties", param.classLoader)
                        val spoofedValue = HookBridge.callStaticMethod(spClass, "get", propName, "") as String
                        
                        HookLog.d(HookLog.Module.CORE, "FuckProcess intercepted getprop: $propName -> $spoofedValue")

                        this.result = MockProcess(spoofedValue + "\n")
                    }
                }
            })
            HookLog.i(HookLog.Module.CORE, "FuckProcess installed for ${param.packageName}")
        }.onFailure {
            HookLog.e(HookLog.Module.CORE, "FuckProcess failed for ${param.packageName}", it)
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
