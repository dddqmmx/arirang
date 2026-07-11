package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.SimConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.SimProfileSchema
import asia.nana7mi.arirang.model.SimInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date
import java.util.Locale

object SimConfigPrefs {
    const val PREFS_NAME = "sim_config_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MODIFIED = "last_modified"
    private const val KEY_SIM_INFO_LIST = "sim_info_list"
    private const val KEY_SIM_INFO_MAP = "sim_info_map"
    private const val KEY_HIDE_SIM = "hide_sim"
    private const val MAX_IMPORTED_SLOT_INDEX = 31

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
        return preserveConfiguredSlots(
            Config(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                hideSim = prefs.getBoolean(KEY_HIDE_SIM, false),
                simInfoBySlot = loadSimInfoMap(prefs)
            )
        )
    }

    fun saveConfig(context: Context, config: Config) {
        val boundedConfig = preserveConfiguredSlots(config)
        prefs(context).edit(commit = true) {
            putBoolean(KEY_ENABLED, boundedConfig.enabled)
            putBoolean(KEY_HIDE_SIM, boundedConfig.hideSim)
            putLong(KEY_LAST_MODIFIED, Date().time)
            remove(KEY_SIM_INFO_LIST)
            putString(KEY_SIM_INFO_MAP, gson.toJson(boundedConfig.simInfoBySlot.toSortedMap()))
        }
        SubmoduleConfigFiles.write(context, boundedConfig)
    }

    fun importSchema(context: Context, schema: SimConfigSchema) {
        require(schema.schemaVersion in 1..SimConfigSchema.SCHEMA_VERSION) {
            "Unsupported SIM config schema version: ${schema.schemaVersion}"
        }

        val profilesBySlot = schema.simProfiles
            .asSequence()
            .filter { it.simSlotIndex in 0..MAX_IMPORTED_SLOT_INDEX }
            .associate { profile ->
                profile.simSlotIndex to SimInfo(
                    id = profile.id.takeIf { it > 0 },
                    iccId = profile.iccId.normalizedText(128),
                    simSlotIndex = profile.simSlotIndex,
                    displayName = profile.displayName.normalizedText(256),
                    carrierName = profile.carrierName.normalizedText(256),
                    nameSource = profile.nameSource,
                    iconTint = profile.iconTint,
                    number = profile.number.normalizedText(64),
                    imei = profile.imei.filter(Char::isDigit).take(15),
                    roaming = profile.roaming,
                    icon = null,
                    mcc = profile.mcc.filter(Char::isDigit).take(3),
                    mnc = profile.mnc.filter(Char::isDigit).take(3),
                    countryIso = profile.countryIso.trim().lowercase(Locale.ROOT)
                        .filter { it in 'a'..'z' }
                        .take(2),
                    isEmbedded = profile.isEmbedded,
                    nativeAccessRules = null,
                    cardString = profile.cardString.normalizedText(128),
                    cardId = profile.cardId.takeIf { it >= 0 },
                    isOpportunistic = profile.isOpportunistic,
                    groupUuid = null,
                    isGroupDisabled = profile.isGroupDisabled,
                    carrierId = profile.carrierId,
                    profileClass = profile.profileClass,
                    subType = profile.subType,
                    groupOwner = profile.groupOwner.normalizedText(256),
                    carrierConfigAccessRules = null,
                    areUiccApplicationsEnabled = profile.areUiccApplicationsEnabled,
                    portIndex = profile.portIndex.coerceAtLeast(0),
                    usageSetting = profile.usageSetting,
                    isExpanded = false
                )
            }
            .toSortedMap()

        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                hideSim = schema.hideSim,
                simInfoBySlot = profilesBySlot
            )
        )
    }

    fun lastModified(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return SimConfigSchema(
            enabled = config.enabled,
            hideSim = config.hideSim,
            simProfiles = config.simInfoBySlot.map { (slot, info) ->
                SimProfileSchema(
                    id = info.id ?: -1,
                    iccId = info.iccId.orEmpty(),
                    simSlotIndex = info.simSlotIndex ?: slot,
                    displayName = info.displayName.orEmpty(),
                    carrierName = info.carrierName.orEmpty(),
                    countryIso = info.countryIso.orEmpty(),
                    mcc = info.mcc.orEmpty(),
                    mnc = info.mnc.orEmpty(),
                    imei = info.imei.orEmpty(),
                    number = info.number.orEmpty(),
                    cardId = info.cardId ?: -1,
                    cardString = info.cardString.orEmpty(),
                    nameSource = info.nameSource ?: 0,
                    iconTint = info.iconTint ?: 0,
                    roaming = info.roaming ?: 0,
                    isEmbedded = info.isEmbedded ?: false,
                    isOpportunistic = info.isOpportunistic ?: false,
                    isGroupDisabled = info.isGroupDisabled ?: false,
                    profileClass = info.profileClass ?: -1,
                    subType = info.subType ?: 0,
                    groupOwner = info.groupOwner.orEmpty(),
                    areUiccApplicationsEnabled = info.areUiccApplicationsEnabled ?: true,
                    portIndex = info.portIndex ?: 0,
                    usageSetting = info.usageSetting ?: 0,
                    carrierId = info.carrierId ?: -1
                )
            },
            lastModified = lastModified(context)
        ).toJson()
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

    private fun preserveConfiguredSlots(config: Config): Config {
        return config.copy(
            simInfoBySlot = config.simInfoBySlot.toSortedMap()
                .entries
                .filter { (slotIndex, _) -> slotIndex >= 0 }
                .associate { (slotIndex, simInfo) ->
                    slotIndex to simInfo.copy(
                        simSlotIndex = slotIndex,
                        id = simInfo.id?.takeIf { it > 0 } ?: slotIndex + 1,
                        cardId = simInfo.cardId?.takeIf { it >= 0 } ?: slotIndex
                    )
                }
                .toSortedMap()
        )
    }

    private fun List<SimInfo>.toSlotMap(): Map<Int, SimInfo> {
        return mapIndexed { index, simInfo ->
            (simInfo.simSlotIndex ?: index) to simInfo
        }.toMap().toSortedMap()
    }

    private fun String.normalizedText(maxLength: Int): String {
        return trim().filterNot(Char::isISOControl).take(maxLength)
    }
}
