package asia.nana7mi.arirang.hook.gms

import android.content.Context
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.orFalse
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject

internal data class GmsIdentifierConfig(
    val enabled: Boolean = false,
    val gaid: String = "",
    val appSetId: String = ""
)

internal class GmsIdentifierConfigStore(
    private val contextProvider: () -> Context?
) {
    private val configFile = HookConfigFile(
        configName = "unique_identifier",
        prefsName = UniqueIdentifierPrefs.PREFS_NAME,
        defaultValue = GmsIdentifierConfig(),
        refreshIntervalMs = CONFIG_REFRESH_INTERVAL_MS,
        readRealtimeSnapshot = { force ->
            val context = contextProvider()
            ArirangClient.readConfigSnapshot(
                configName = "unique_identifier",
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
        if (DEBUG_HARDCODE_IDS) {
            return GmsIdentifierConfig(
                enabled = true,
                gaid = DEBUG_GAID,
                appSetId = DEBUG_APP_SET_ID
            )
        }
        return configFile.current()
    }

    private fun parseSnapshot(snapshot: String): GmsIdentifierConfig? {
        return runCatching {
            val root = JSONObject(snapshot)
            if (!root.optString(KEY_ENABLED).toBooleanStrictOrNull().orFalse()) {
                return@runCatching GmsIdentifierConfig()
            }
            GmsIdentifierConfig(
                enabled = true,
                gaid = root.optString(KEY_GAID),
                appSetId = root.optString(KEY_APP_SET_ID)
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

        private const val DEBUG_HARDCODE_IDS = false
        private const val DEBUG_GAID = "00000000-0000-4000-8000-000000000001"
        private const val DEBUG_APP_SET_ID = "00000000-0000-4000-8000-000000000002"
    }
}
