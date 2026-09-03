package asia.nana7mi.arirang.hook.core

import asia.nana7mi.arirang.BuildConfig

/**
 * Package-matching policy for a [HookModule].
 */
abstract class BaseHookModule(
    private val targetPackages: Set<String> = emptySet(),
    private val matchSystem: Boolean = false,
    private val matchClient: Boolean = false
) : HookModule {

    override fun matches(packageName: String): Boolean {
        if (matchSystem && packageName == "android") return true
        if (matchClient && BuildConfig.APPLICATION_ID == packageName) return true
        return packageName in targetPackages
    }

    override fun isEnabled(): Boolean = true
}
