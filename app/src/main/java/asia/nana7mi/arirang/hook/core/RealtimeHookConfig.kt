package asia.nana7mi.arirang.hook.core

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RealtimeHookConfig<T>(
    private val defaultValue: T,
    private val refreshIntervalMs: Long,
    private val readSnapshot: (force: Boolean) -> String?,
    private val parseSnapshot: (String) -> T?,
    private val readFallback: () -> T
) {
    @Volatile
    private var cachedValue: T = runCatching(readFallback).getOrDefault(defaultValue)

    @Volatile
    private var cachedAt: Long = -refreshIntervalMs

    private val refreshInFlight = AtomicBoolean(false)

    fun current(force: Boolean = false): T {
        val now = SystemClock.uptimeMillis()
        if (force || now - cachedAt >= refreshIntervalMs) {
            refreshAsync(force)
        }
        return cachedValue
    }

    private fun refreshAsync(force: Boolean) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        REFRESH_EXECUTOR.execute {
            try {
                runCatching {
                    readSnapshot(force)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(parseSnapshot)
                        ?: readFallback()
                }.getOrNull()?.let { cachedValue = it }
            } finally {
                cachedAt = SystemClock.uptimeMillis()
                refreshInFlight.set(false)
            }
        }
    }

    fun invalidate() {
        cachedAt = -refreshIntervalMs
        cachedValue = defaultValue
    }

    private companion object {
        val REFRESH_EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ArirangConfigParser").apply { isDaemon = true }
        }
    }
}
