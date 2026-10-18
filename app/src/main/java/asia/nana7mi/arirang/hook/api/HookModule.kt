package asia.nana7mi.arirang.hook.api

data class HookPackageParam(
    val packageName: String,
    val classLoader: ClassLoader,
    val processName: String = packageName
)

interface HookModule {
    fun matches(packageName: String): Boolean
    fun onHook(param: HookPackageParam)
    fun isEnabled(): Boolean

    /**
     * Whether [onHook] must run even while [isEnabled] reports false.
     *
     * True only for a module whose config this process cannot read without the service — see
     * the override sites. For those, [isEnabled] cannot be answered before the hook is
     * installed and the bind completes, so skipping them here would make the
     * "not yet known" default permanent whenever hot-switch restriction is on.
     */
    val installsBeforeConfigIsKnown: Boolean get() = false
}
