package asia.nana7mi.arirang.hook.core

import java.util.Collections

/**
 * 提取重复的配置加载逻辑
 */
class HookConfig(private val prefsName: String) {
    var enabled = false
    var isAlternativeMode = false // true 代表 Blacklist/Invisible, false 代表 Whitelist/Visible
    var packageSet: Set<String> = Collections.emptySet()
    private var lastLoadedTimestamp = -1L

    private val pref by lazy {
        HookConfigFile.xSharedPreferences(prefsName)
    }

    fun loadIfUpdated(whiteListKey: String, blackListKey: String) {
        if (!pref.file.canRead()) return

        pref.reload()
        val newTimestamp = pref.getLong("last_modified", -1L)
        if (newTimestamp == lastLoadedTimestamp) return

        lastLoadedTimestamp = newTimestamp
        enabled = pref.getBoolean("enabled", false)
        val modeInt = pref.getInt("mode", 0)
        isAlternativeMode = (modeInt == 1)

        val key = if (isAlternativeMode) blackListKey else whiteListKey
        packageSet = pref.getStringSet(key, null)?.toHashSet() ?: emptySet()
    }

    fun shouldBlock(packageName: String): Boolean {
        if (!enabled) return false
        val contains = packageSet.contains(packageName)
        // Whitelist 模式下，不包含则拦截；Blacklist 模式下，包含则拦截
        return if (!isAlternativeMode) !contains else contains
    }

    // 针对应用过滤的逻辑反转 (Keep)
    fun shouldKeep(packageName: String): Boolean = !shouldBlock(packageName)
}
