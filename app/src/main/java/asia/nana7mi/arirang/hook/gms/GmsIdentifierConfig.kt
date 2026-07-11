package asia.nana7mi.arirang.hook.gms

import android.content.Context
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.schema.IdentifierConfigSchema
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import de.robv.android.xposed.XSharedPreferences

internal data class GmsIdentifierConfig(
    val enabled: Boolean = false,
    val gaid: String = "",
    val appSetId: String = ""
)

internal class GmsIdentifierConfigStore(
    private val contextProvider: () -> Context?
) {
    private val configFile = HookConfigFile(
        configName = ConfigIds.UNIQUE_IDENTIFIER,
        prefsName = UniqueIdentifierPrefs.PREFS_NAME,
        defaultValue = GmsIdentifierConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            val context = contextProvider()
            ArirangClient.readConfigSnapshot(
                configName = ConfigIds.UNIQUE_IDENTIFIER,
                force = force,
                allowBind = context != null,
                bindContext = context,
                bindCurrentUser = true,
                logName = "unique identifier"
            )
        },
        parseRealtimeSnapshot = ::parseSnapshot,
        readStoredConfig = ::readStored
    )

    fun current(): GmsIdentifierConfig {
        return configFile.current()
    }

    private fun parseSnapshot(snapshot: String): GmsIdentifierConfig? {
        return runCatching {
            val schema = IdentifierConfigSchema.fromJson(snapshot)
            if (!schema.enabled) {
                return@runCatching GmsIdentifierConfig()
            }
            GmsIdentifierConfig(
                enabled = true,
                gaid = schema.gaid,
                appSetId = schema.appSetId
            )
        }.onFailure {
            HookLog.i(HookLog.Module.GMS, "failed to parse GMS identifier config: ${it.message}")
        }.getOrNull()
    }

    private fun readStored(prefs: XSharedPreferences): GmsIdentifierConfig? {
        if (!prefs.file.canRead()) return null
        if (!prefs.getBoolean(KEY_ENABLED, false)) return GmsIdentifierConfig()
        return GmsIdentifierConfig(
            enabled = true,
            gaid = prefs.getString(KEY_GAID, null).orEmpty(),
            appSetId = prefs.getString(KEY_APP_SET_ID, null).orEmpty()
        )
    }

    private companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_GAID = "gaid"
        private const val KEY_APP_SET_ID = "app_set_id"
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L

    }
}
