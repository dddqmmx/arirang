package asia.nana7mi.arirang.hook.core

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface HookModule {
    fun matches(packageName: String): Boolean
    fun onHook(lpparam : XC_LoadPackage.LoadPackageParam)
    fun isEnabled(): Boolean
}
