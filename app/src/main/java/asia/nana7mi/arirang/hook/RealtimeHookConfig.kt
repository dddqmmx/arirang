package asia.nana7mi.arirang.hook

import android.os.SystemClock

class RealtimeHookConfig<T>(
    private val defaultValue: T,
    private val refreshIntervalMs: Long,
    private val readSnapshot: (force: Boolean) -> String?,
    private val parseSnapshot: (String) -> T?,
    private val readFallback: () -> T
) {
    @Volatile
    private var cachedValue: T = defaultValue

    @Volatile
    private var cachedAt: Long = 0L

    fun current(force: Boolean = false): T {
        val now = SystemClock.uptimeMillis()
        if (!force && now - cachedAt < refreshIntervalMs) return cachedValue

        return synchronized(this) {
            val checkedAt = SystemClock.uptimeMillis()
            if (!force && checkedAt - cachedAt < refreshIntervalMs) {
                return@synchronized cachedValue
            }

            cachedValue = readSnapshot(force)
                ?.takeIf { it.isNotBlank() }
                ?.let(parseSnapshot)
                ?: readFallback()
            cachedAt = checkedAt
            cachedValue
        }
    }

    fun invalidate() {
        cachedAt = 0L
        cachedValue = defaultValue
    }
}
