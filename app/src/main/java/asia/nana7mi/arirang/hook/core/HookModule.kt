package asia.nana7mi.arirang.hook.core

data class HookPackageParam(
    val packageName: String,
    val classLoader: ClassLoader,
    val processName: String = packageName
)

interface HookModule {
    fun matches(packageName: String): Boolean
    fun onHook(param: HookPackageParam)
    fun isEnabled(): Boolean
}
