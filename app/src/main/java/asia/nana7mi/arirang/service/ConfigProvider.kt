package asia.nana7mi.arirang.service

import android.content.Context
import asia.nana7mi.arirang.data.config.ConfigRegistry
import asia.nana7mi.arirang.data.config.ManagedConfigSnapshot

/** Realtime configuration facade backed by the same registry used for persistence and backup. */
class ConfigProvider(private val context: Context) {
    fun readConfigVersion(configName: String): Long {
        val config = ConfigRegistry.find(configName)?.takeIf { it.realtimeAvailable } ?: return 0L
        return config.version(context)
    }

    fun readConfigSnapshot(configName: String): String {
        val config = ConfigRegistry.find(configName)?.takeIf { it.realtimeAvailable } ?: return ""
        return config.snapshot(context)
    }

    fun readConfig(configName: String): ManagedConfigSnapshot? {
        val config = ConfigRegistry.find(configName)?.takeIf { it.realtimeAvailable } ?: return null
        return config.read(context)
    }
}
