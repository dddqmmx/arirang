package asia.nana7mi.arirang.hook

import android.bluetooth.BluetoothDevice
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * Feasibility hook for rewriting the local Bluetooth device identity and the
 * bonded (connected) device list returned to applications.
 *
 * Hook points are based on the AOSP Bluetooth stack:
 * - AdapterService.getName()     -> returns the local adapter name
 * - AdapterService.getAddress()  -> returns the local adapter MAC address
 * - AdapterService.getBondedDevices() -> returns the set of paired devices
 * - AdapterService.getRemoteName(BluetoothDevice) -> resolves a remote name
 *
 * All hooks are installed inside the `com.android.bluetooth` process so that
 * every app that talks to the Bluetooth stack through the normal Binder IPC
 * path sees the spoofed values — no per-app injection needed.
 *
 * Scan-result spoofing (nearby devices / discovery) is not yet implemented
 * because the internal BLE scan dispatch path varies significantly across
 * Android versions and is difficult to hook reliably without per-app side
 * effects.
 */
class FuckBluetooth : BaseHookModule(
    targetPackages = setOf("com.android.bluetooth")
) {

    private data class BluetoothConfig(
        val enabled: Boolean = false,
        val deviceName: String = "Arirang",
        val connectedDevices: List<Device> = listOf(Device()),
        val hideConnectedDevices: Boolean = false,
        val hideScanResults: Boolean = false,
        val scanResults: List<Device> = listOf(Device(name = "Nearby-BT", address = "02:00:00:DD:EE:FF"))
    )

    private data class Device(
        val name: String = BluetoothConfigPrefs.DEFAULT_DEVICE_NAME,
        val address: String = BluetoothConfigPrefs.DEFAULT_DEVICE_ADDRESS
    )

    private val configFile = HookConfigFile(
        configName = "bluetooth",
        prefsName = BluetoothConfigPrefs.PREFS_NAME,
        defaultValue = BluetoothConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            HookNotifyClient.readConfigSnapshot(
                configName = "bluetooth",
                force = force,
                allowBind = true,
                logName = "Bluetooth"
            )
        },
        parseRealtimeSnapshot = ::parseConfigSnapshot,
        readStoredConfig = ::readConfigFromPrefs
    )

    private val hookedAdapterServiceClasses =
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    /**
     * Fast lookup from spoofed address to [Device] so that [getRemoteName]
     * can resolve the name without iterating the config list on every call.
     */
    @Volatile
    private var spoofedDeviceMap: Map<String, Device> = emptyMap()

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            HookLog.i(
                HookLog.Module.BLUETOOTH,
                "installing Bluetooth hooks for ${lpparam.packageName} classLoader=${lpparam.classLoader}"
            )
            hookAdapterService(lpparam.classLoader)
            HookLog.i(
                HookLog.Module.BLUETOOTH,
                "Bluetooth privacy hook installed for ${lpparam.packageName}"
            )
        }.onFailure {
            HookLog.e(
                HookLog.Module.BLUETOOTH,
                "Bluetooth privacy hook failed for ${lpparam.packageName}",
                it
            )
        }
    }

    // ------------------------------------------------------------------ hooks

    private fun hookAdapterService(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            ADAPTER_SERVICE_CLASS, classLoader
        ) ?: run {
            HookLog.w(
                HookLog.Module.BLUETOOTH,
                "$ADAPTER_SERVICE_CLASS not found in $classLoader"
            )
            return
        }

        synchronized(hookedAdapterServiceClasses) {
            if (!hookedAdapterServiceClasses.add(serviceClass)) {
                HookLog.d(
                    HookLog.Module.BLUETOOTH,
                    "AdapterService already hooked: ${serviceClass.name}"
                )
                return
            }
        }

        val candidates = serviceClass.declaredMethods
        HookLog.i(
            HookLog.Module.BLUETOOTH,
            "AdapterService ${serviceClass.name} candidates=${
                candidates.joinToString { it.signature(serviceClass) }
            }"
        )

        var hookedGetName = 0
        var hookedGetAddress = 0
        var hookedGetBondedDevices = 0
        var hookedGetRemoteName = 0

        candidates.forEach { method ->
            when {
                method.name == "getName" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        HookLog.i(
                            HookLog.Module.BLUETOOTH,
                            "spoof getName via ${method.signature(serviceClass)}"
                        )
                        result = config.deviceName
                    })
                    hookedGetName++
                }

                method.name == "getAddress" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        HookLog.i(
                            HookLog.Module.BLUETOOTH,
                            "spoof getAddress via ${method.signature(serviceClass)}"
                        )
                        result = SPOOFED_LOCAL_MAC
                    })
                    hookedGetAddress++
                }

                method.name == "getBondedDevices" &&
                    method.returnsListOfBluetoothDevice() -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        if (config.hideConnectedDevices) {
                            HookLog.i(
                                HookLog.Module.BLUETOOTH,
                                "hiding all bonded devices"
                            )
                            result = emptyList<BluetoothDevice>()
                            return@beforeHookedMethod
                        }
                        HookLog.i(
                            HookLog.Module.BLUETOOTH,
                            "spoof getBondedDevices via ${method.signature(serviceClass)}"
                        )
                        refreshSpoofedDeviceMap(config)
                        result = config.connectedDevices
                            .mapNotNull { createFakeBluetoothDevice(it) }
                    })
                    hookedGetBondedDevices++
                }

                method.name == "getRemoteName" &&
                    method.parameterTypes.size == 1 &&
                    BluetoothDevice::class.java.isAssignableFrom(
                        method.parameterTypes[0]
                    ) -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        val device = args[0] as? BluetoothDevice
                            ?: return@beforeHookedMethod
                        val address = runCatching { device.address }
                            .getOrNull() ?: return@beforeHookedMethod
                        val spoofed = spoofedDeviceMap[address]
                            ?: return@beforeHookedMethod
                        HookLog.i(
                            HookLog.Module.BLUETOOTH,
                            "spoof getRemoteName($address) -> ${spoofed.name}"
                        )
                        result = spoofed.name
                    })
                    hookedGetRemoteName++
                }
            }
        }

        HookLog.i(
            HookLog.Module.BLUETOOTH,
            "hooked AdapterService ${serviceClass.name} " +
                "getName=$hookedGetName getAddress=$hookedGetAddress " +
                "bondedDevices=$hookedGetBondedDevices getRemoteName=$hookedGetRemoteName"
        )
    }

    // ---------------------------------------------------------- fake devices

    /**
     * Creates a [BluetoothDevice] with a spoofed address.
     *
     * AOSP [BluetoothDevice] has a package-private `BluetoothDevice(String)`
     * constructor on Android 12–16.  On builds where the constructor signature
     * differs (e.g. `BluetoothDevice(String, int)`), we fall back to reflective
     * field injection on an uninitialised instance.
     */
    private fun createFakeBluetoothDevice(device: Device): BluetoothDevice? {
        val bt = runCatching {
            XposedHelpers.newInstance(
                BluetoothDevice::class.java,
                device.address
            )
        }.getOrNull() ?: runCatching {
            // Fallback: BluetoothDevice(String, int) constructor on some
            // newer Android builds.
            XposedHelpers.newInstance(
                BluetoothDevice::class.java,
                device.address,
                0 // ADDRESS_TYPE_PUBLIC
            )
        }.getOrNull() ?: return null

        // Pre-populate name-cache fields so that getName() / getAlias()
        // return the spoofed value without a Binder round-trip, even on
        // ROMs where the AOSP behaviour differs.
        runCatching { XposedHelpers.setObjectField(bt, "mName", device.name) }
        runCatching { XposedHelpers.setObjectField(bt, "mAlias", device.name) }

        return bt as BluetoothDevice?
    }

    private fun refreshSpoofedDeviceMap(config: BluetoothConfig) {
        spoofedDeviceMap = config.connectedDevices.associateBy { it.address }
            .ifEmpty { emptyMap() }
    }

    // -------------------------------------------------------- config loading

    private fun currentConfig(): BluetoothConfig = configFile.current()

    private fun parseConfigSnapshot(snapshot: String): BluetoothConfig? {
        return runCatching {
            val json = JSONObject(snapshot)
            BluetoothConfig(
                enabled = json.optBoolean(
                    BluetoothConfigPrefs.KEY_ENABLED, false
                ),
                deviceName = json.optString(
                    BluetoothConfigPrefs.KEY_DEVICE_NAME, ""
                ).takeIf { it.isNotBlank() } ?: "Arirang",
                connectedDevices = parseDevices(
                    json.optJSONArray(
                        BluetoothConfigPrefs.KEY_CONNECTED_DEVICES
                    )?.toString()
                ),
                hideConnectedDevices = json.optBoolean(
                    BluetoothConfigPrefs.KEY_HIDE_CONNECTED_DEVICES, false
                ),
                hideScanResults = json.optBoolean(
                    BluetoothConfigPrefs.KEY_HIDE_SCAN_RESULTS, false
                ),
                scanResults = parseDevices(
                    json.optJSONArray(
                        BluetoothConfigPrefs.KEY_SCAN_RESULTS
                    )?.toString()
                )
            )
        }.onFailure {
            HookLog.w(
                HookLog.Module.BLUETOOTH,
                "failed to parse Bluetooth config snapshot: ${it.message}"
            )
        }.getOrNull()
    }

    private fun readConfigFromPrefs(
        prefs: de.robv.android.xposed.XSharedPreferences
    ): BluetoothConfig {
        return BluetoothConfig(
            enabled = prefs.getBoolean(BluetoothConfigPrefs.KEY_ENABLED, false),
            deviceName = prefs.getString(BluetoothConfigPrefs.KEY_DEVICE_NAME, null)
                ?.takeIf { it.isNotBlank() } ?: "Arirang",
            connectedDevices = parseDevices(
                prefs.getString(BluetoothConfigPrefs.KEY_CONNECTED_DEVICES, null)
            ),
            hideConnectedDevices = prefs.getBoolean(
                BluetoothConfigPrefs.KEY_HIDE_CONNECTED_DEVICES, false
            ),
            hideScanResults = prefs.getBoolean(
                BluetoothConfigPrefs.KEY_HIDE_SCAN_RESULTS, false
            ),
            scanResults = parseDevices(
                prefs.getString(BluetoothConfigPrefs.KEY_SCAN_RESULTS, null)
            )
        )
    }

    private fun parseDevices(json: String?): List<Device> {
        if (json.isNullOrBlank()) return listOf(Device())
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Device(
                    name = item.optString(
                        "name", BluetoothConfigPrefs.DEFAULT_DEVICE_NAME
                    ),
                    address = item.optString(
                        "address", BluetoothConfigPrefs.DEFAULT_DEVICE_ADDRESS
                    )
                )
            }
        }.getOrDefault(listOf(Device()))
            .filter { it.name.isNotBlank() || it.address.isNotBlank() }
            .ifEmpty { listOf(Device()) }
    }

    // ------------------------------------------------------------ utilities

    private fun java.lang.reflect.Method.signature(
        declaringClass: Class<*>
    ): String {
        return "${
            returnType.name
        } ${
            declaringClass.name
        }.$name(${
            parameterTypes.joinToString { it.name }
        })"
    }

    // Matches List<BluetoothDevice> (and Set<BluetoothDevice> on older AOSP).
    // Java type erasure means we cannot introspect the generic parameter, so we
    // rely on the method name together with a coarse return-type check.
    private fun java.lang.reflect.Method.returnsListOfBluetoothDevice(): Boolean {
        return List::class.java.isAssignableFrom(returnType) ||
            Set::class.java.isAssignableFrom(returnType)
    }

    // -------------------------------------------------------------- config

    private companion object {
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
        private const val ADAPTER_SERVICE_CLASS =
            "com.android.bluetooth.btservice.AdapterService"
        private const val SPOOFED_LOCAL_MAC = "02:00:00:AA:BB:CC"
    }
}
