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
 * Hook points are based on the actual on-device Bluetooth stack (verified against
 * the decompiled APK, Android 16):
 * - AdapterProperties.getName()  -> the single leaf for the local adapter name.
 *     AdapterService.getName() and getNameLengthForAdvertise() both delegate here,
 *     so hooking this one method covers BluetoothAdapter.getName() for every app.
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
    targetPackages = setOf("com.android.bluetooth", "com.google.android.bluetooth")
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
            ArirangClient.readConfigSnapshot(
                configName = "bluetooth",
                force = force,
                allowBind = true,
                logName = "Bluetooth"
            )
        },
        parseRealtimeSnapshot = ::parseConfigSnapshot,
        readStoredConfig = ::readConfigFromPrefs
    )

    private val hookedClasses =
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    /**
     * Fast lookup from spoofed address to [Device] so that [getRemoteName]
     * can resolve the name without iterating the config list on every call.
     */
    @Volatile
    private var spoofedDeviceMap: Map<String, Device> = emptyMap()

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // matches() restricts loading to the Bluetooth stack process, so every app
        // that talks to Bluetooth over Binder inherits the spoofed values without any
        // per-app injection.
        val classLoader = lpparam.classLoader

        runCatching {
            HookLog.i(
                HookLog.Module.BLUETOOTH,
                "installing Bluetooth hooks for package: ${lpparam.packageName}, process: ${lpparam.processName}"
            )

            hookAdapterService(classLoader)
            hookAdapterProperties(classLoader)
            hookScanController(classLoader)
            hookGattService(classLoader)

        }.onFailure {
            HookLog.e(
                HookLog.Module.BLUETOOTH,
                "Bluetooth privacy hook failed for ${lpparam.packageName}",
                it
            )
        }
    }

    // ------------------------------------------------------------------ hooks

    private fun hookAdapterService(classLoader: ClassLoader): Boolean {
        val serviceClass = XposedHelpers.findClassIfExists(
            ADAPTER_SERVICE_CLASS, classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.BLUETOOTH, "Class not found: $ADAPTER_SERVICE_CLASS")
            return false
        }

        if (!hookedClasses.add(serviceClass)) return true

        HookLog.i(HookLog.Module.BLUETOOTH, "Found $ADAPTER_SERVICE_CLASS, hooking methods...")

        // The local adapter name is intentionally NOT hooked here. AdapterService.getName()
        // and getNameLengthForAdvertise() both delegate to AdapterProperties.getName(), which
        // is the single chokepoint hooked in hookAdapterProperties(). The methods below cover
        // the separate address / bonded-device / remote-name features.
        serviceClass.declaredMethods.forEach { method ->
            when {
                method.name == "getAddress" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getAddress()")
                    XposedBridge.hookMethod(method, afterHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        result = SPOOFED_LOCAL_MAC
                    })
                }

                method.name == "getBondedDevices" &&
                    method.returnsListOfBluetoothDevice() -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getBondedDevices()")
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        if (config.hideConnectedDevices) {
                            result = emptyList<BluetoothDevice>()
                            return@beforeHookedMethod
                        }
                        refreshSpoofedDeviceMap(config)
                        result = config.connectedDevices
                            .mapNotNull { createFakeBluetoothDevice(it) }
                    })
                }

                method.name == "getRemoteName" &&
                    method.parameterTypes.size == 1 &&
                    BluetoothDevice::class.java.isAssignableFrom(
                        method.parameterTypes[0]
                    ) -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking AdapterService.getRemoteName()")
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        val device = args[0] as? BluetoothDevice
                            ?: return@beforeHookedMethod
                        val address = runCatching { device.address }
                            .getOrNull() ?: return@beforeHookedMethod
                        val spoofed = spoofedDeviceMap[address]
                            ?: return@beforeHookedMethod
                        result = spoofed.name
                    })
                }
            }
        }
        return true
    }

    private fun hookAdapterProperties(classLoader: ClassLoader): Boolean {
        val propertiesClass = XposedHelpers.findClassIfExists(
            ADAPTER_PROPERTIES_CLASS, classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.bluetooth.btservice.AdapterProperties", classLoader
        ) ?: run {
            HookLog.w(HookLog.Module.BLUETOOTH, "Class not found: $ADAPTER_PROPERTIES_CLASS")
            return false
        }

        if (!hookedClasses.add(propertiesClass)) return true

        HookLog.i(HookLog.Module.BLUETOOTH, "Found ${propertiesClass.name}, hooking methods...")
        propertiesClass.declaredMethods.forEach { method ->
            when {
                // Single chokepoint for the local adapter name. AdapterService.getName() and
                // getNameLengthForAdvertise() both route here (return mName), so every app
                // reading BluetoothAdapter.getName() over Binder sees the spoofed value.
                method.name == "getName" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking ${propertiesClass.name}.getName()")
                    XposedBridge.hookMethod(method, afterHookedMethod {
                        val config = currentConfig()
                        val original = result as? String
                        if (!config.enabled) {
                            // Diagnostic: the hook fired, but config resolved to disabled.
                            // If the bluetooth process cannot read the config, this is where
                            // spoofing silently no-ops.
                            HookLog.i(
                                HookLog.Module.BLUETOOTH,
                                "AdapterProperties.getName() hit but config.enabled=false (original=$original)"
                            )
                            return@afterHookedMethod
                        }
                        result = config.deviceName
                        HookLog.i(
                            HookLog.Module.BLUETOOTH,
                            "AdapterProperties.getName(): $original -> ${config.deviceName}"
                        )
                    })
                }

                method.name == "getAddress" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == ByteArray::class.java -> {
                    HookLog.i(HookLog.Module.BLUETOOTH, "Hooking ${propertiesClass.name}.getAddress()")
                    XposedBridge.hookMethod(method, afterHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@afterHookedMethod
                        result = macToBytes(SPOOFED_LOCAL_MAC)
                    })
                }

                method.name == "getBondedDevices" &&
                    (method.returnType.isArray &&
                    BluetoothDevice::class.java.isAssignableFrom(method.returnType.componentType)) ||
                    method.returnsListOfBluetoothDevice() -> {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled) return@beforeHookedMethod
                        if (config.hideConnectedDevices) {
                            result = if (method.returnType.isArray) {
                                java.lang.reflect.Array.newInstance(BluetoothDevice::class.java, 0)
                            } else {
                                emptyList<BluetoothDevice>()
                            }
                            return@beforeHookedMethod
                        }
                        refreshSpoofedDeviceMap(config)
                        val devices = config.connectedDevices.mapNotNull { createFakeBluetoothDevice(it) }
                        if (method.returnType.isArray) {
                            val array = java.lang.reflect.Array.newInstance(BluetoothDevice::class.java, devices.size)
                            devices.forEachIndexed { index, device ->
                                java.lang.reflect.Array.set(array, index, device)
                            }
                            result = array
                        } else {
                            result = devices
                        }
                    })
                }
            }
        }
        return true
    }

    private fun hookScanController(classLoader: ClassLoader): Boolean {
        val names = listOf(
            SCAN_CONTROLLER_CLASS,
            "com.android.bluetooth.gatt.ScanController",
            "com.android.bluetooth.le_scan.ScanController"
        )
        var anyHooked = false
        names.distinct().forEach { className ->
            val scanControllerClass = XposedHelpers.findClassIfExists(className, classLoader)
                ?: return@forEach

            if (!hookedClasses.add(scanControllerClass)) {
                anyHooked = true
                return@forEach
            }

            scanControllerClass.declaredMethods.forEach { method ->
                if ((method.name == "onScanResult" || method.name == "onScanResultInternal") &&
                    method.parameterTypes.size >= 5) {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled || !config.hideScanResults) return@beforeHookedMethod
                        result = null
                    })
                }

                if ((method.name == "onBatchScanReports" || method.name == "onBatchScanReportsInternal") &&
                    method.parameterTypes.size >= 3) {
                    XposedBridge.hookMethod(method, beforeHookedMethod {
                        val config = currentConfig()
                        if (!config.enabled || !config.hideScanResults) return@beforeHookedMethod
                        result = null
                    })
                }
            }
            anyHooked = true
        }
        return anyHooked
    }

    private fun hookGattService(classLoader: ClassLoader): Boolean {
        val gattServiceClass = XposedHelpers.findClassIfExists(
            GATT_SERVICE_CLASS, classLoader
        ) ?: return false

        if (!hookedClasses.add(gattServiceClass)) return true

        gattServiceClass.declaredMethods.forEach { method ->
            if (method.name == "onScanResult" && method.parameterTypes.size == 2) {
                XposedBridge.hookMethod(method, beforeHookedMethod {
                    val config = currentConfig()
                    if (!config.enabled || !config.hideScanResults) return@beforeHookedMethod
                    result = null
                })
            }
        }
        return true
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

    private fun macToBytes(mac: String): ByteArray {
        val bytes = ByteArray(6)
        val hex = mac.replace(":", "")
        if (hex.length != 12) return bytes
        for (i in 0 until 6) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------ utilities

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
        private const val ADAPTER_PROPERTIES_CLASS =
            "com.android.bluetooth.btservice.AdapterProperties"
        private const val SCAN_CONTROLLER_CLASS =
            "com.android.bluetooth.le_scan.ScanController"
        private const val GATT_SERVICE_CLASS =
            "com.android.bluetooth.gatt.GattService"

        private const val SPOOFED_LOCAL_MAC = "02:00:00:AA:BB:CC"
    }
}
