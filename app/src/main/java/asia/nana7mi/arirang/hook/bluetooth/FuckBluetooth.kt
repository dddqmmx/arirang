package asia.nana7mi.arirang.hook.bluetooth

import asia.nana7mi.arirang.hook.api.BaseHookModule
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.api.HookPackageParam

/**
 * Rewrites the local Bluetooth identity and Bluetooth stack results from inside
 * the Bluetooth process, so apps see spoofed values through the normal Binder IPC
 * path without per-app injection.
 */
class FuckBluetooth : BaseHookModule(
    targetPackages = setOf("com.android.bluetooth", "com.google.android.bluetooth")
) {
    private val adapterHooks = BluetoothAdapterHooks()
    private val scanHooks = BluetoothScanHooks()

    override fun isEnabled(): Boolean = currentConfig().enabled

    override fun onHook(param: HookPackageParam) {
        runCatching {
            HookLog.i(
                HookLog.Module.BLUETOOTH,
                "installing Bluetooth hooks for package: ${param.packageName}, process: ${param.processName}"
            )

            val classLoader = param.classLoader
            adapterHooks.hookAdapterService(classLoader)
            adapterHooks.hookAdapterProperties(classLoader)
            scanHooks.hookScanController(classLoader)
            scanHooks.hookGattService(classLoader)
        }.onFailure {
            HookLog.e(
                HookLog.Module.BLUETOOTH,
                "Bluetooth privacy hook failed for ${param.packageName}",
                it
            )
        }
    }

    private fun currentConfig(): BluetoothHookConfig = BluetoothConfig.current()
}
