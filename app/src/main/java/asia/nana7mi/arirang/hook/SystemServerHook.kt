package asia.nana7mi.arirang.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class SystemServerHook : BaseHookModule(matchSystem = true) {
    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", lpparam.classLoader)
            
            // Search for systemReady method that takes a Runnable as its first parameter
            // Newer Android versions (14+) often have systemReady(Runnable, TimingsTraceAndSlog)
            val systemReady = amsClass.declaredMethods.find { 
                it.name == "systemReady" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Runnable::class.java
            } ?: throw NoSuchMethodError("systemReady(Runnable, ...) not found in ActivityManagerService")

            HookLog.i(HookLog.Module.CORE, "found systemReady with parameters: ${systemReady.parameterTypes.joinToString { it.name }}")

            XposedBridge.hookMethod(
                systemReady,
                afterHookedMethod {
                    HookLog.i(HookLog.Module.CORE, "AMS systemReady, starting auto-bind to HookNotifyService")

                    val ctx = HookNotifyClient.getSystemContext()
                    if (ctx != null) {
                        HookNotifyClient.autoBind(ctx)

                        val filter = IntentFilter().apply {
                            addAction(Intent.ACTION_PACKAGE_REPLACED)
                            addAction(Intent.ACTION_PACKAGE_RESTARTED)
                            addAction(Intent.ACTION_PACKAGE_ADDED)
                            addAction(Intent.ACTION_PACKAGE_CHANGED)
                            addDataScheme("package")
                        }

                        ctx.registerReceiver(object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                val pkgName = intent.data?.schemeSpecificPart
                                if (pkgName == BuildConfig.APPLICATION_ID) {
                                    HookLog.i(HookLog.Module.CORE, "package $pkgName updated/restarted, trying to bind service")
                                    HookNotifyClient.autoBind(context)
                                }
                            }
                        }, filter, null, null)
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.CORE, "systemReady hook failed", t)
        }
    }
}
