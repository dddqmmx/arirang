package asia.nana7mi.arirang.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

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
            hookBluetoothManagerWhenPublished(lpparam.classLoader)

            // Hook SystemProperties for Java-level property spoofing
            val systemPropertiesClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader)
            hookSystemProperties(systemPropertiesClass)

        } catch (t: Throwable) {
            HookLog.e(HookLog.Module.CORE, "systemReady hook failed", t)
        }
    }

    private val bmsHooked = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Installs a hook on [android.os.ServiceManager.addService] (which IS in the system_server
     * boot classloader) and waits for BluetoothService to publish the "bluetooth_manager"
     * binder. The published binder belongs to the bluetooth APEX classloader, which is the only
     * way to reach [com.android.server.bluetooth.BluetoothManagerService] from here.
     */
    private fun hookBluetoothManagerWhenPublished(systemClassLoader: ClassLoader) {
        val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", systemClassLoader)
            ?: run {
                HookLog.w(HookLog.Module.BLUETOOTH, "ServiceManager not found; cannot reach BluetoothManagerService")
                return
            }
        smClass.declaredMethods
            .filter {
                it.name == "addService" &&
                    it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == String::class.java
            }
            .forEach { method ->
                XposedBridge.hookMethod(method, beforeHookedMethod {
                    if (args.getOrNull(0) != "bluetooth_manager") return@beforeHookedMethod
                    val binder = args.getOrNull(1) ?: return@beforeHookedMethod
                    onBluetoothManagerPublished(binder)
                })
            }
    }

    private fun onBluetoothManagerPublished(binder: Any) {
        if (!bmsHooked.compareAndSet(false, true)) return
        runCatching {
            val cl = binder.javaClass.classLoader
            val bmsClass = XposedHelpers.findClassIfExists(
                "com.android.server.bluetooth.BluetoothManagerService", cl
            ) ?: run {
                HookLog.w(
                    HookLog.Module.BLUETOOTH,
                    "BluetoothManagerService not found via APEX classloader (binder=${binder.javaClass.name})"
                )
                bmsHooked.set(false)
                return
            }
            HookLog.i(HookLog.Module.BLUETOOTH, "Found ${bmsClass.name} via ServiceManager, hooking name/address...")
            hookBluetoothManagerService(bmsClass)
        }.onFailure {
            HookLog.e(HookLog.Module.BLUETOOTH, "hook BluetoothManagerService via ServiceManager failed", it)
            bmsHooked.set(false)
        }
    }

    private fun hookBluetoothManagerService(bmsClass: Class<*>) {
        // getName() — the leaf the app-facing IBluetoothManager.getName() funnels through.
        XposedHelpers.findAndHookMethod(bmsClass, "getName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = param.result as? String
                val name = readBluetoothNameFromConfig(param.thisObject)
                if (name == null) {
                    HookLog.i(HookLog.Module.BLUETOOTH, "BluetoothManagerService.getName() hit but config disabled/unreadable (original=$original)")
                    return
                }
                param.result = name
                HookLog.i(HookLog.Module.BLUETOOTH, "spoof BluetoothManagerService.getName(): $original -> $name")
            }
        })

        // getAddress()
        XposedHelpers.findAndHookMethod(bmsClass, "getAddress", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (readBluetoothNameFromConfig(param.thisObject) == null) return
                param.result = "02:00:00:AA:BB:CC"
                HookLog.i(HookLog.Module.BLUETOOTH, "spoof BluetoothManagerService.getAddress() -> (spoofed)")
            }
        })
    }

    private fun hookSystemProperties(spClass: Class<*>) {
        // Spoof the Bluetooth factory default name. NOTE: this only affects Java-level reads
        // of SystemProperties; the native BT stack reads the property directly via
        // __system_property_get, which would require the native property spoofer. The user's
        // configured name is normally served by AdapterProperties.getName() (FuckBluetooth),
        // so this is a best-effort cover for the pre-set-name / early-boot path.
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                if (key != "bluetooth.device.default_name") return
                val name = readBluetoothNameFromConfig(null) ?: return
                param.result = name
                HookLog.i(HookLog.Module.BLUETOOTH, "spoof SystemProperties bluetooth.device.default_name -> $name")
            }
        }

        XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, hook)
        XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, String::class.java, hook)
    }

    /**
     * Reads the spoofed local Bluetooth name from config, or null when the feature is
     * disabled. Prefers the realtime service snapshot and falls back to the
     * world-readable XSharedPreferences. Used by the system_server-side BT-off fallback
     * hooks (BluetoothManagerService / SystemProperties).
     */
    private fun readBluetoothNameFromConfig(service: Any?): String? {
        val context = runCatching {
            XposedHelpers.getObjectField(service, "mContext") as? Context
        }.getOrNull()
        val snapshot = ArirangClient.readConfigSnapshot(
            configName = "bluetooth",
            allowBind = true,
            bindContext = context,
            logName = "Bluetooth"
        )
        val json = runCatching { snapshot?.let { JSONObject(it) } }.getOrNull()
        if (json?.optBoolean(BluetoothConfigPrefs.KEY_ENABLED) == true) {
            return json.optString(BluetoothConfigPrefs.KEY_DEVICE_NAME)
                .takeIf { it.isNotBlank() }
        }

        // Fallback to XSharedPreferences if the snapshot was unavailable.
        return runCatching {
            val prefs = HookConfigFile.xSharedPreferences(BluetoothConfigPrefs.PREFS_NAME)
            if (prefs.getBoolean(BluetoothConfigPrefs.KEY_ENABLED, false)) {
                prefs.getString(BluetoothConfigPrefs.KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }
}
