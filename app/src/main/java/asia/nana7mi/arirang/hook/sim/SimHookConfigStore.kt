package asia.nana7mi.arirang.hook.sim

import asia.nana7mi.arirang.hook.core.ArirangClient
import asia.nana7mi.arirang.hook.core.HookConfigFile
import asia.nana7mi.arirang.hook.core.HookLog
import asia.nana7mi.arirang.hook.util.optBooleanOrNull
import asia.nana7mi.arirang.hook.util.optCleanString
import asia.nana7mi.arirang.hook.util.optIntOrNull

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

internal class SimHookConfigStore {
    var preferHookNotifyConfig: Boolean = false

    @Volatile
    private var cachedHookConfig: SimHookConfig? = null

    @Volatile
    private var lastHookConfigRefreshAt = 0L

    private val resolvingHookConfig = ThreadLocal.withInitial { false }

    fun current(force: Boolean = false): SimHookConfig {
        if (resolvingHookConfig.get() == true) {
            return cachedHookConfig ?: defaultDisabledHookConfig()
        }

        val now = SystemClock.uptimeMillis()
        cachedHookConfig?.let { cached ->
            if (!force && now - lastHookConfigRefreshAt < CONFIG_REFRESH_INTERVAL_MS) {
                return cached
            }
        }

        return synchronized(this) {
            val checkedAt = SystemClock.uptimeMillis()
            cachedHookConfig?.let { cached ->
                if (!force && checkedAt - lastHookConfigRefreshAt < CONFIG_REFRESH_INTERVAL_MS) {
                    return@synchronized cached
                }
            }

            resolvingHookConfig.set(true)
            try {
                val previous = cachedHookConfig
                val updated = loadHookConfig(force)
                if (previous != updated) {
                    logHookConfig(updated)
                }
                cachedHookConfig = updated
                lastHookConfigRefreshAt = checkedAt
                updated
            } finally {
                resolvingHookConfig.set(false)
            }
        }
    }

    private fun defaultDisabledHookConfig(): SimHookConfig {
        return SimHookConfig(
            enabled = false,
            hideSim = false,
            profilesBySlot = SimHookDefaults.PROFILES_BY_SLOT,
            uniqueIdentifiers = UniqueIdentifierHookConfig()
        )
    }

    private fun loadHookConfig(force: Boolean = false): SimHookConfig {
        val values = readConfigValues(force)
        val enabled = values?.get(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false
        val hideSim = values?.get(KEY_HIDE_SIM)?.toBooleanStrictOrNull() ?: false
        val profilesBySlot = values?.get(KEY_SIM_INFO_MAP)
            ?.let(::parseProfileMap)
            ?.takeIf { it.isNotEmpty() }
            ?: values?.get(KEY_SIM_INFO_LIST)
                ?.let(::parseProfileList)
                ?.takeIf { it.isNotEmpty() }
            ?: SimHookDefaults.PROFILES_BY_SLOT
        val uniqueIdentifiers = readUniqueIdentifierConfig(force)
        val normalized = normalizeProfiles(profilesBySlot, uniqueIdentifiers.slotLimit)
        return SimHookConfig(
            enabled = enabled,
            hideSim = hideSim,
            profilesBySlot = normalized,
            uniqueIdentifiers = uniqueIdentifiers
        )
    }

    private fun readConfigValues(force: Boolean): Map<String, String>? {
        if (!preferHookNotifyConfig) return readSharedPrefsValues()

        val hookNotifyValues = readHookNotifyValues(force)
        val sharedPrefsValues = if (force) readSharedPrefsValues() else null

        return freshestConfigValues(hookNotifyValues, sharedPrefsValues)
            ?: hookNotifyValues
            ?: readSharedPrefsValues()
    }

    private fun freshestConfigValues(
        first: Map<String, String>?,
        second: Map<String, String>?
    ): Map<String, String>? {
        if (first == null) return second
        if (second == null) return first

        val firstVersion = first[KEY_LAST_MODIFIED]?.toLongOrNull() ?: Long.MIN_VALUE
        val secondVersion = second[KEY_LAST_MODIFIED]?.toLongOrNull() ?: Long.MIN_VALUE
        return if (secondVersion > firstVersion) second else first
    }

    private fun logHookConfig(config: SimHookConfig) {
        HookLog.i(
            HookLog.Module.SIM,
            "config loaded enabled=${config.enabled} hideSim=${config.hideSim} " +
                "uniqueIds=${config.uniqueIdentifiers.enabled} slots=${config.visibleProfiles.joinToString { "${it.slotIndex}:${it.countryIso}/${it.operatorNumeric}/${it.alphaLong}" }}"
        )
        HookLog.i(
            HookLog.Module.UNIQUE_ID,
            "unique config enabled=${config.uniqueIdentifiers.enabled} " +
                "imeiSlots=${config.uniqueIdentifiers.imeiBySlot.mapValues { it.value.maskIdentifier() }} " +
                "tacSlots=${config.uniqueIdentifiers.tacBySlot}"
        )
    }

    private fun readHookNotifyValues(force: Boolean = false): Map<String, String>? {
        if (!preferHookNotifyConfig) return null
        return ArirangClient.readConfigSnapshot(
            configName = "sim",
            force = force,
            logName = "SIM"
        )
            ?.let(::parseConfigSnapshot)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseConfigSnapshot(json: String): Map<String, String> {
        return HookConfigFile.readSnapshotValues(json, HookLog.Module.SIM, "SIM")
    }

    private fun readSharedPrefsValues(): Map<String, String>? {
        return readPrefsValues(PREFS_NAME)
    }

    private fun readUniqueIdentifierConfig(force: Boolean = false): UniqueIdentifierHookConfig {
        val hookNotifyValues = ArirangClient.readConfigSnapshot(
            configName = "unique_identifier",
            force = force,
            logName = "unique identifier"
        )
            ?.let(::parseConfigSnapshot)
            ?.takeIf { it.isNotEmpty() }
        val sharedPrefsValues = if (hookNotifyValues == null || force) {
            readPrefsValues(UNIQUE_PREFS_NAME)
        } else {
            null
        }
        val values = freshestConfigValues(hookNotifyValues, sharedPrefsValues)
            ?: hookNotifyValues
            ?: sharedPrefsValues
            ?: return UniqueIdentifierHookConfig()
        val enabled = values[KEY_UNIQUE_ENABLED]?.toBooleanStrictOrNull() ?: false
        val imeiBySlot = values[KEY_IMEI_BY_SLOT]
            ?.let(::parseSlotStringMap)
            ?.takeIf { it.isNotEmpty() }
            ?: SimHookDefaults.PROFILES_BY_SLOT.mapValues { it.value.imei }
        val tacBySlot = values[KEY_TAC_BY_SLOT]
            ?.let(::parseSlotStringMap)
            ?.takeIf { it.isNotEmpty() }
            ?: imeiBySlot.mapValues { it.value.take(8) }
        return UniqueIdentifierHookConfig(enabled = enabled, imeiBySlot = imeiBySlot, tacBySlot = tacBySlot)
    }

    private fun parseSlotStringMap(json: String): Map<Int, String> {
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val slot = key.toIntOrNull() ?: continue
                    val value = root.optString(key).takeIf { it.isNotBlank() } ?: continue
                    put(slot, value)
                }
            }.toSortedMap()
        }.onFailure {
            HookLog.w(HookLog.Module.UNIQUE_ID, "failed to parse slot string map: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun readPrefsValues(prefsName: String): Map<String, String>? {
        return HookConfigFile.readSharedPrefsValues(
            prefsName = prefsName,
            logModule = HookLog.Module.SIM,
            logName = prefsName
        )
    }

    private fun parseProfileMap(json: String): Map<Int, SimProfile> {
        return runCatching {
            val root = JSONObject(json)
            val result = linkedMapOf<Int, SimProfile>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val slot = key.toIntOrNull()
                val item = root.optJSONObject(key) ?: continue
                val slotIndex = slot ?: item.optIntOrNull("simSlotIndex") ?: result.size
                result[slotIndex] = profileFromJson(item, slotIndex)
            }
            result
        }.onFailure {
            HookLog.w(HookLog.Module.SIM, "failed to parse SIM profile map: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun parseProfileList(json: String): Map<Int, SimProfile> {
        return runCatching {
            val root = JSONArray(json)
            buildMap {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val slotIndex = item.optIntOrNull("simSlotIndex") ?: index
                    put(slotIndex, profileFromJson(item, slotIndex))
                }
            }
        }.onFailure {
            HookLog.w(HookLog.Module.SIM, "failed to parse legacy SIM profile list: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun profileFromJson(item: JSONObject, slotIndex: Int): SimProfile {
        val fallback = SimHookDefaults.PROFILES_BY_SLOT[slotIndex]
            ?: SimHookDefaults.PROFILES_BY_SLOT.values.first().copy(
                slotIndex = slotIndex,
                subId = slotIndex + 1,
                cardId = slotIndex,
                portIndex = slotIndex
            )
        val mcc = item.optCleanString("mcc") ?: fallback.mcc
        val mnc = item.optCleanString("mnc") ?: fallback.mnc
        val carrierName = item.optCleanString("carrierName")
            ?: item.optCleanString("displayName")
            ?: fallback.alphaLong
        val iccId = item.optCleanString("iccId") ?: fallback.iccId

        return fallback.copy(
            slotIndex = slotIndex,
            subId = item.optIntOrNull("id")?.takeIf { it > 0 } ?: fallback.subId,
            iccId = iccId,
            countryIso = item.optCleanString("countryIso") ?: fallback.countryIso,
            mcc = mcc,
            mnc = mnc,
            alphaLong = carrierName,
            alphaShort = carrierName,
            phoneNumber = item.optCleanString("number") ?: fallback.phoneNumber,
            imei = item.optCleanString("imei") ?: fallback.imei,
            carrierId = item.optIntOrNull("carrierId") ?: fallback.carrierId,
            cardId = item.optIntOrNull("cardId") ?: fallback.cardId,
            cardString = item.optCleanString("cardString") ?: iccId,
            displayNameSource = item.optIntOrNull("nameSource") ?: fallback.displayNameSource,
            iconTint = item.optIntOrNull("iconTint") ?: fallback.iconTint,
            roaming = item.optIntOrNull("roaming") ?: fallback.roaming,
            isEmbedded = item.optBooleanOrNull("isEmbedded") ?: fallback.isEmbedded,
            isOpportunistic = item.optBooleanOrNull("isOpportunistic") ?: fallback.isOpportunistic,
            isGroupDisabled = item.optBooleanOrNull("isGroupDisabled") ?: fallback.isGroupDisabled,
            profileClass = item.optIntOrNull("profileClass") ?: fallback.profileClass,
            subType = item.optIntOrNull("subType") ?: fallback.subType,
            groupOwner = item.optCleanString("groupOwner") ?: fallback.groupOwner,
            areUiccApplicationsEnabled = item.optBooleanOrNull("areUiccApplicationsEnabled")
                ?: fallback.areUiccApplicationsEnabled,
            portIndex = item.optIntOrNull("portIndex") ?: fallback.portIndex,
            usageSetting = item.optIntOrNull("usageSetting") ?: fallback.usageSetting
        )
    }

    private fun normalizeProfiles(profilesBySlot: Map<Int, SimProfile>, slotLimit: Int): Map<Int, SimProfile> {
        return profilesBySlot.toSortedMap()
            .entries
            .take(slotLimit.coerceAtLeast(1))
            .mapIndexed { index, (_, profile) ->
                val normalizedSlot = index.coerceAtLeast(0)
                normalizedSlot to profile.copy(
                    slotIndex = normalizedSlot,
                    subId = profile.subId.takeIf { it > 0 } ?: normalizedSlot + 1,
                    cardId = profile.cardId.takeIf { it >= 0 } ?: normalizedSlot,
                    portIndex = profile.portIndex.takeIf { it >= 0 } ?: normalizedSlot
                )
            }
            .toMap()
            .toSortedMap()
    }

    private fun String.maskIdentifier(): String {
        if (length <= 6) return "***"
        return take(3) + "***" + takeLast(3)
    }

    private companion object {
        private const val PREFS_NAME = "sim_config_prefs"
        private const val UNIQUE_PREFS_NAME = "unique_identifier_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HIDE_SIM = "hide_sim"
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_SIM_INFO_MAP = "sim_info_map"
        private const val KEY_SIM_INFO_LIST = "sim_info_list"
        private const val KEY_UNIQUE_ENABLED = "enabled"
        private const val KEY_IMEI_BY_SLOT = "imei_by_slot"
        private const val KEY_TAC_BY_SLOT = "tac_by_slot"
        private const val CONFIG_REFRESH_INTERVAL_MS = 300L
    }
}
