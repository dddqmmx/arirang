package asia.nana7mi.arirang.hook

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class FuckProcess : BaseHookModule(matchSystem = false, matchClient = true) {

    // Match all target packages where we want to spoof getprop (client app included for testing)
    override fun matches(packageName: String): Boolean {
        // Apply this broadly to apps that might run getprop, similar to other hooks.
        // For simplicity, we just return true for non-system apps if they are hooked.
        // Actually, let's use the BaseHookModule's matches but allow any app where this module is loaded.
        return true
    }

    override fun isEnabled(): Boolean = true // Always active if module is loaded, rely on underlying SystemProperties

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return // Don't hook system_server ProcessBuilder

        runCatching {
            val processBuilderClass = XposedHelpers.findClass("java.lang.ProcessBuilder", lpparam.classLoader)
            
            XposedBridge.hookAllMethods(processBuilderClass, "start", beforeHookedMethod {
                val pb = this.thisObject as ProcessBuilder
                val cmd = pb.command()
                
                if (cmd.isNotEmpty() && (cmd[0] == "getprop" || cmd[0] == "/system/bin/getprop")) {
                    val key = cmd.getOrNull(1)
                    if (key != null) {
                        // Let's call the Java SystemProperties.get() which is ALREADY SPOOFED by our native zygisk module.
                        val spoofedValue = runCatching {
                            val spClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader)
                            XposedHelpers.callStaticMethod(spClass, "get", key, "") as String
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
