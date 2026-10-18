package asia.nana7mi.arirang.hook.sim

import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.hook.config.Arbitrations
import asia.nana7mi.arirang.hook.config.ConfigSpec
import asia.nana7mi.arirang.hook.config.ConfigValues
import asia.nana7mi.arirang.hook.config.RemotePrefsSource
import asia.nana7mi.arirang.hook.config.ServiceSnapshotSource
import asia.nana7mi.arirang.hook.gms.UniqueIdentifierConfig
import asia.nana7mi.arirang.hook.xposed.HookLog

private const val REFRESH_INTERVAL_MS = 300L

/**
 * The SIM config: the profiles to present, and the per-slot identifiers to report.
 *
 * Read inline, unlike every other config: the phone process writes telephony properties from this
 * config and must see a change as soon as it is made, so the caller's thread does the read and
 * decode under a lock rather than waiting for the background refresh. That is what the
 * hand-written store's `synchronized` block did, kept as a declared property of the config.
 *
 * Being inline also means the values are live the instant the first hook runs — earlier than the
 * phone process's own `Application.onCreate`, which builds its RCS state out of the subscription
 * list and crashes on a rewritten one. The inline read stays; the *earliest moment* it may be
 * acted on is [asia.nana7mi.arirang.hook.sim.FuckSim]'s per-process activation gate.
 *
 * The per-slot IMEI and TAC are **fields of the unique-identifier config**, read through that
 * config's shared cache rather than by a second reader of it — which is what the SIM module used
 * to be, along with a third copy of the "pick the fresher source by `last_modified`" rule.
 *
 * One decoder serves both sources. They disagree on shape as well as spelling: the snapshot and
 * the legacy prefs key carry a *list* of profiles under `simProfiles`/`sim_info_list`, while the
 * current prefs key carries a slot-keyed *object* under `sim_info_map`. Both used to have their
 * own parse function, and the two were free to drift; they are read by one function now.
 */
internal object SimConfig : ConfigSpec<SimHookConfig>(
    id = ConfigIds.SIM,
    typeName = "SIM",
    logModule = HookLog.Module.SIM,
    fallback = SimHookConfig.disabled(),
    sources = listOf(
        ServiceSnapshotSource(ConfigIds.SIM, logName = "SIM"),
        RemotePrefsSource(SimConfigPrefs.PREFS_NAME),
    ),
    arbitration = Arbitrations.FreshestVersion,
    refreshIntervalMs = REFRESH_INTERVAL_MS,
    readInline = true,
    decode = ::decode,
) {
    /** Any SIM spoofing at all, for the surfaces that only need the switch. */
    val enabled = guard("enabled") { it.enabled }
}

private fun decode(values: ConfigValues): SimHookConfig? {
    if (values.isEmpty()) return null
    val profiles = decodeProfiles(values)
    return SimHookConfig(
        enabled = values.boolean("enabled") ?: false,
        hideSim = values.boolean("hideSim", "hide_sim") ?: false,
        profilesBySlot = normalizeProfiles(
            profiles.ifEmpty { SimHookDefaults.PROFILES_BY_SLOT }
        ),
        uniqueIdentifiers = UniqueIdentifierHookConfig(
            enabled = values.boolean("enabled") != false,
            imeiBySlot = UniqueIdentifierConfig.imeiBySlot.current(),
            tacBySlot = UniqueIdentifierConfig.tacBySlot.current(),
        ),
    )
}

private fun decodeProfiles(values: ConfigValues): Map<Int, SimProfile> {
    val fromMap = values.jsonObject("sim_info_map")?.let { map ->
        buildMap {
            val names = map.keys()
            while (names.hasNext()) {
                val name = names.next()
                val entry = map.optJSONObject(name) ?: continue
                val item = ConfigValues.ofJson(entry.toString()) ?: continue
                val slotIndex = name.toIntOrNull() ?: item.int("simSlotIndex") ?: size
                put(slotIndex, decodeProfile(item, slotIndex))
            }
        }
    }
    if (!fromMap.isNullOrEmpty()) return fromMap

    val entries = values.objects("simProfiles", "sim_info_list")
    if (entries.isEmpty()) return emptyMap()
    return entries.mapIndexedNotNull { index, item ->
        val slotIndex = item.int("simSlotIndex") ?: index
        slotIndex to decodeProfile(item, slotIndex)
    }.toMap()
}

private fun decodeProfile(item: ConfigValues, slotIndex: Int): SimProfile {
    val fallback = SimHookDefaults.PROFILES_BY_SLOT[slotIndex]
        ?: SimHookDefaults.PROFILES_BY_SLOT.values.first().copy(
            slotIndex = slotIndex,
            subId = slotIndex + 1,
            cardId = slotIndex,
            portIndex = slotIndex
        )
    val mcc = item.string("mcc") ?: fallback.mcc
    val mnc = item.string("mnc") ?: fallback.mnc
    val carrierName = item.string("carrierName")
        ?: item.string("displayName")
        ?: fallback.alphaLong
    val iccId = item.string("iccId") ?: fallback.iccId

    return fallback.copy(
        slotIndex = slotIndex,
        subId = item.int("id")?.takeIf { it > 0 } ?: fallback.subId,
        iccId = iccId,
        countryIso = item.string("countryIso") ?: fallback.countryIso,
        mcc = mcc,
        mnc = mnc,
        alphaLong = carrierName,
        alphaShort = carrierName,
        phoneNumber = item.string("number") ?: fallback.phoneNumber,
        imei = item.string("imei") ?: fallback.imei,
        carrierId = item.int("carrierId") ?: fallback.carrierId,
        cardId = item.int("cardId") ?: fallback.cardId,
        cardString = item.string("cardString") ?: iccId,
        displayNameSource = item.int("nameSource") ?: fallback.displayNameSource,
        iconTint = item.int("iconTint") ?: fallback.iconTint,
        roaming = item.int("roaming") ?: fallback.roaming,
        isEmbedded = item.boolean("isEmbedded") ?: fallback.isEmbedded,
        isOpportunistic = item.boolean("isOpportunistic") ?: fallback.isOpportunistic,
        isGroupDisabled = item.boolean("isGroupDisabled") ?: fallback.isGroupDisabled,
        profileClass = item.int("profileClass") ?: fallback.profileClass,
        subType = item.int("subType") ?: fallback.subType,
        groupOwner = item.string("groupOwner") ?: fallback.groupOwner,
        areUiccApplicationsEnabled = item.boolean("areUiccApplicationsEnabled")
            ?: fallback.areUiccApplicationsEnabled,
        portIndex = item.int("portIndex") ?: fallback.portIndex,
        usageSetting = item.int("usageSetting") ?: fallback.usageSetting,
    )
}

private fun normalizeProfiles(profilesBySlot: Map<Int, SimProfile>): Map<Int, SimProfile> {
    return profilesBySlot.toSortedMap()
        .entries
        .filter { (slotIndex, _) -> slotIndex >= 0 }
        .associate { (slotIndex, profile) ->
            slotIndex to profile.copy(
                slotIndex = slotIndex,
                subId = profile.subId.takeIf { it > 0 } ?: slotIndex + 1,
                cardId = profile.cardId.takeIf { it >= 0 } ?: slotIndex,
                portIndex = profile.portIndex.takeIf { it >= 0 } ?: slotIndex
            )
        }
        .toSortedMap()
}
