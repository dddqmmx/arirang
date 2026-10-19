package asia.nana7mi.arirang.hook.systemserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.hook.ipc.ArirangClient
import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.afterHookedMethod
import asia.nana7mi.arirang.hook.api.HookPackageParam
import asia.nana7mi.arirang.hook.target.Targets
import asia.nana7mi.arirang.hook.bluetooth.BluetoothConfig
import asia.nana7mi.arirang.hook.bluetooth.SPOOFED_LOCAL_MAC

private const val BLUETOOTH_MANAGER_SERVICE_NAME = "bluetooth_manager"
private const val BLUETOOTH_MANAGER_SERVICE_CLASS =
    "com.android.server.bluetooth.BluetoothManagerService"

class SystemServerHook : BaseHookModule(matchSystem = true) {
    override fun onHook(param: HookPackageParam) {
        try {
            val amsClass = HookBridge.findClass("com.android.server.am.ActivityManagerService", param.classLoader)
            
            // Search for systemReady method that takes a Runnable as its first parameter
            // Newer Android versions (14+) often have systemReady(Runnable, TimingsTraceAndSlog)
            val systemReady = amsClass.declaredMethods.find { 
                it.name == "systemReady" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Runnable::class.java
            } ?: throw NoSuchMethodError("systemReady(Runnable, ...) not found in ActivityManagerService")

            HookLog.i(HookLog.Module.CORE, "found systemReady with parameters: ${systemReady.parameterTypes.joinToString { it.name }}")

            HookBridge.hookMethod(
                systemReady,
                afterHookedMethod {
                    HookLog.i(HookLog.Module.CORE, "AMS systemReady, starting auto-bind to ArirangService")
                    
                    val ctx = ArirangClient.getSystemContext()
                    if (ctx != null) {
                        ArirangClient.autoBind(ctx)

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
                                    ArirangClient.autoBind(context)
                                }
                            }
                        }, filter, null, null)
                    }
                }
            )

            // Hook the APP-FACING Bluetooth name/address path.
            //
            // Verified on Android 16 / Pixel by decompiling framework-bluetooth +
            // service-bluetooth: BluetoothAdapter.getName() does NOT reach AdapterService in the
            // bluetooth process. It calls IBluetoothManager.getName() ->
            // BluetoothServiceBinder.getName() -> BluetoothManagerService.getName() in
            // system_server, returning cached mName (seeded from Settings.Secure.bluetooth_name).
            //
            // That class lives in the bluetooth APEX (service-bluetooth.jar) and is loaded by a
            // DEDICATED classloader, NOT the system_server boot classloader LSPosed hands us, so
            // findClass(..., lpparam.classLoader) fails ("class not found"). We instead wait for
            // BluetoothService to publish its "bluetooth_manager" binder via
            // ServiceManager.addService and take the APEX classloader from that binder.
            hookBluetoothManagerWhenPublished()

            // Hook SystemProperties for Java-level property spoofing
            val systemPropertiesClass = HookBridge.findClass("android.os.SystemProperties", param.classLoader)
            hookSystemProperties(systemPropertiesClass)

        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.CORE, "systemReady hook failed", t)
        }
    }

    /**
     * Waits for BluetoothService to publish the "bluetooth_manager" binder. That binder belongs
     * to the bluetooth APEX classloader, which is the only way to reach
     * [com.android.server.bluetooth.BluetoothManagerService] from here — the class is not in the
     * boot classloader LSPosed hands us, so `findClass(..., lpparam.classLoader)` cannot find it.
     */
    private fun hookBluetoothManagerWhenPublished() {
        Targets.onServiceClassPublished(BLUETOOTH_MANAGER_SERVICE_NAME) { binder ->
            val bmsClass = HookBridge.findClassIfExists(
                BLUETOOTH_MANAGER_SERVICE_CLASS,
                binder.javaClass.classLoader
            ) ?: throw NoClassDefFoundError(
                // Thrown rather than returned so Targets counts the install as failed and
                // retries on the next discovery; a class that is not reachable yet must not
                // make "installed" permanent.
                "BluetoothManagerService not found via APEX classloader (binder=${binder.javaClass.name})"
            )
            HookLog.i(HookLog.Module.BLUETOOTH, "Found ${bmsClass.name} via ServiceManager, hooking name/address...")
            hookBluetoothManagerService(bmsClass)
        }
    }

    private fun hookBluetoothManagerService(bmsClass: Class<*>) {
        // getName() — the leaf the app-facing IBluetoothManager.getName() funnels through.
        HookBridge.findAndHookMethod(
            bmsClass,
            "getName",
            BluetoothConfig.deviceName.after { config ->
                result = config.deviceName
                HookLog.d(HookLog.Module.BLUETOOTH, "spoof BluetoothManagerService.getName()")
            }
        )

        // getAddress()
        HookBridge.findAndHookMethod(
            bmsClass,
            "getAddress",
            BluetoothConfig.deviceName.after {
                // Gated on the name because that is the only switch this fallback has: the
                // module is on if it has a name to report, and the address follows it.
                result = SPOOFED_LOCAL_MAC
                HookLog.d(HookLog.Module.BLUETOOTH, "spoof BluetoothManagerService.getAddress()")
            }
        )
    }

    private fun hookSystemProperties(spClass: Class<*>) {
        // Spoof the Bluetooth factory default name. NOTE: this only affects Java-level reads
        // of SystemProperties; the native BT stack reads the property directly via
        // __system_property_get, which would require the native property spoofer. The user's
        // configured name is normally served by AdapterProperties.getName() (FuckBluetooth),
        // so this is a best-effort cover for the pre-set-name / early-boot path.
        val hook = BluetoothConfig.deviceName.after { config ->
            val key = args[0] as? String ?: return@after
            if (key != "bluetooth.device.default_name") return@after
            result = config.deviceName
            HookLog.d(HookLog.Module.BLUETOOTH, "spoof SystemProperties bluetooth.device.default_name")
        }

        HookBridge.findAndHookMethod(spClass, "get", String::class.java, hook)
        HookBridge.findAndHookMethod(spClass, "get", String::class.java, String::class.java, hook)
    }

}
