package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.core.BaseHookModule
import asia.nana7mi.arirang.hook.core.HookBridge
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Rewrites the local Bluetooth identity and Bluetooth stack results from inside
 * the Bluetooth process, so apps see spoofed values through the normal Binder IPC
 * path without per-app injection.
 */
class FuckBluetooth : BaseHookModule(
    targetPackages = setOf("com.android.bluetooth", "com.google.android.bluetooth")
) {
    private val configStore = BluetoothConfigStore()
    private val adapterHooks = BluetoothAdapterHooks(::currentConfig)
    private val scanHooks = BluetoothScanHooks(::currentConfig)

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            HookLog.i(
                HookLog.Module.BLUETOOTH,
                "installing Bluetooth hooks for package: ${lpparam.packageName}, process: ${lpparam.processName}"
            )

            val classLoader = lpparam.classLoader
            adapterHooks.hookAdapterService(classLoader)
            adapterHooks.hookAdapterProperties(classLoader)
            scanHooks.hookScanController(classLoader)
            scanHooks.hookGattService(classLoader)
        }.onFailure {
            HookLog.e(
                HookLog.Module.BLUETOOTH,
                "Bluetooth privacy hook failed for ${lpparam.packageName}",
                it
            )
        }
    }

    private fun currentConfig(): BluetoothHookConfig {
        return configStore.current()
    }
}
