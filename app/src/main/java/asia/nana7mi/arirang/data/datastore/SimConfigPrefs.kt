package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.model.SimInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.util.Date

object SimConfigPrefs {
    const val PREFS_NAME = "sim_config_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MODIFIED = "last_modified"
    private const val KEY_SIM_INFO_LIST = "sim_info_list"
    private const val KEY_SIM_INFO_MAP = "sim_info_map"
    private const val KEY_HIDE_SIM = "hide_sim"

    private val gson = Gson()

    data class Config(
        val enabled: Boolean = false,
        val hideSim: Boolean = false,
        val simInfoBySlot: Map<Int, SimInfo> = emptyMap()
    ) {
        val simInfoList: List<SimInfo>
            get() = simInfoBySlot.toSortedMap().values.toList()

        companion object {
            fun fromList(
                enabled: Boolean,
                hideSim: Boolean,
                simInfoList: List<SimInfo>
            ): Config {
                return Config(
                    enabled = enabled,
                    hideSim = hideSim,
                    simInfoBySlot = simInfoList.toSlotMap()
                )
            }
        }
    }

    fun loadConfig(context: Context): Config {
        val prefs = prefs(context)
        return limitToConfiguredSlots(
            context,
            Config(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                hideSim = prefs.getBoolean(KEY_HIDE_SIM, false),
                simInfoBySlot = loadSimInfoMap(prefs)
            )
        )
    }

    fun saveConfig(context: Context, config: Config) {
        val boundedConfig = limitToConfiguredSlots(context, config)
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, boundedConfig.enabled)
            putBoolean(KEY_HIDE_SIM, boundedConfig.hideSim)
            putLong(KEY_LAST_MODIFIED, Date().time)
            remove(KEY_SIM_INFO_LIST)
            putString(KEY_SIM_INFO_MAP, gson.toJson(boundedConfig.simInfoBySlot.toSortedMap()))
        }
        SubmoduleConfigFiles.write(context, boundedConfig)
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return JSONObject()
            .put(KEY_ENABLED, config.enabled.toString())
            .put(KEY_HIDE_SIM, config.hideSim.toString())
            .put(KEY_LAST_MODIFIED, lastModified(context).toString())
            .put(KEY_SIM_INFO_MAP, gson.toJson(config.simInfoBySlot.toSortedMap()))
            .toString()
    }

    private fun loadSimInfoMap(prefs: SharedPreferences): Map<Int, SimInfo> {
        prefs.getString(KEY_SIM_INFO_MAP, null)?.let { json ->
            val type = object : TypeToken<Map<Int, SimInfo>>() {}.type
            val parsed = runCatching { gson.fromJson<Map<Int, SimInfo>>(json, type) }
                .getOrNull()
                .orEmpty()
            if (parsed.isNotEmpty()) return parsed.toSortedMap()
        }

        return loadLegacySimInfoList(prefs).toSlotMap()
    }

    private fun loadLegacySimInfoList(prefs: SharedPreferences): List<SimInfo> {
        val json = prefs.getString(KEY_SIM_INFO_LIST, null) ?: return emptyList()
        val type = object : TypeToken<List<SimInfo>>() {}.type
        return runCatching { gson.fromJson<List<SimInfo>>(json, type) }.getOrNull().orEmpty()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun limitToConfiguredSlots(context: Context, config: Config): Config {
        val slotLimit = UniqueIdentifierPrefs.configuredSlotCount(context)
        return config.copy(
            simInfoBySlot = config.simInfoBySlot.toSortedMap()
                .entries
                .take(slotLimit)
                .mapIndexed { index, (_, simInfo) ->
                    val normalizedSlot = index.coerceAtLeast(0)
                    normalizedSlot to simInfo.copy(
                        simSlotIndex = normalizedSlot,
                        id = simInfo.id?.takeIf { it > 0 } ?: normalizedSlot + 1,
                        cardId = simInfo.cardId?.takeIf { it >= 0 } ?: normalizedSlot
                    )
                }
                .toMap()
                .toSortedMap()
        )
    }

    private fun List<SimInfo>.toSlotMap(): Map<Int, SimInfo> {
        return mapIndexed { index, simInfo ->
            (simInfo.simSlotIndex ?: index) to simInfo
        }.toMap().toSortedMap()
    }
}
