package asia.nana7mi.arirang.data.config

import android.content.Context
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.data.datastore.AppPreferences
import asia.nana7mi.arirang.data.datastore.ClipboardPromptPrefs
import asia.nana7mi.arirang.data.datastore.DeviceInfoPrefs
import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.data.datastore.schema.BluetoothConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.AppConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.ClipboardConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.ConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.DeviceInfoConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.GlobalConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.HookLogConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.IdentifierConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.LocationConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.PackageListConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.SensorConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.SimConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.WifiConfigSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Single source of truth for persisted and realtime-exportable configuration. */
object ConfigRegistry {
    val all: List<ManagedConfig<*>> = listOf(
        ManagedConfig(
            id = ConfigIds.GLOBAL,
            currentSchemaVersion = GlobalConfigSchema.SCHEMA_VERSION,
            requiredFields = requiredFields("restrictHotSwitching"),
            versionReader = GlobalConfigPrefs::lastModified,
            snapshotReader = GlobalConfigPrefs::buildHookSnapshot,
            decoder = GlobalConfigSchema::fromJson,
            importer = GlobalConfigPrefs::importSchema,
            validator = ::validateBase
        ),
        ManagedConfig(
            id = ConfigIds.APP,
            currentSchemaVersion = AppConfigSchema.SCHEMA_VERSION,
            requiredFields = requiredFields("setupCompleted", "language"),
            versionReader = AppPreferences::lastModified,
            snapshotReader = AppPreferences::buildHookSnapshot,
            decoder = AppConfigSchema::fromJson,
            importer = AppPreferences::importSchema,
            validator = ::validateApp
        ),
        ManagedConfig(
            id = ConfigIds.UNIQUE_IDENTIFIER,
            currentSchemaVersion = IdentifierConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields(
                "enabled", "androidId", "gaid", "widevineDrmId", "appSetId", "serial",
                "imeiBySlot", "tacBySlot"
            ),
            versionReader = UniqueIdentifierPrefs::lastModified,
            snapshotReader = UniqueIdentifierPrefs::buildHookSnapshot,
            decoder = IdentifierConfigSchema::fromJson,
            importer = UniqueIdentifierPrefs::importSchema,
            validator = ::validateIdentifier
        ),
        ManagedConfig(
            id = ConfigIds.SIM,
            currentSchemaVersion = SimConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields("enabled", "hideSim", "simProfiles"),
            versionReader = SimConfigPrefs::lastModified,
            snapshotReader = SimConfigPrefs::buildHookSnapshot,
            decoder = SimConfigSchema::fromJson,
            importer = SimConfigPrefs::importSchema,
            validator = ::validateSim
        ),
        ManagedConfig(
            id = ConfigIds.HOOK_LOG,
            currentSchemaVersion = HookLogConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields(
                "core", "clipboard", "gms", "location", "packageList", "settings", "sim",
                "wifi", "bluetooth", "uniqueId", "notify"
            ),
            versionReader = HookLogSettings::lastModified,
            snapshotReader = HookLogSettings::buildHookSnapshot,
            decoder = HookLogConfigSchema::fromJson,
            importer = HookLogSettings::importSchema,
            validator = ::validateBase
        ),
        ManagedConfig(
            id = ConfigIds.WIFI,
            currentSchemaVersion = WifiConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields(
                "enabled", "currentSsid", "currentBssid", "hideScanResults", "scanResults"
            ),
            versionReader = WifiConfigPrefs::lastModified,
            snapshotReader = WifiConfigPrefs::buildHookSnapshot,
            decoder = WifiConfigSchema::fromJson,
            importer = WifiConfigPrefs::importSchema,
            validator = ::validateWifi
        ),
        ManagedConfig(
            id = ConfigIds.BLUETOOTH,
            currentSchemaVersion = BluetoothConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields(
                "enabled", "deviceName", "connectedDevices", "hideConnectedDevices",
                "hideScanResults", "scanResults"
            ),
            versionReader = BluetoothConfigPrefs::lastModified,
            snapshotReader = BluetoothConfigPrefs::buildHookSnapshot,
            decoder = BluetoothConfigSchema::fromJson,
            importer = BluetoothConfigPrefs::importSchema,
            validator = ::validateBluetooth
        ),
        ManagedConfig(
            id = ConfigIds.LOCATION,
            currentSchemaVersion = LocationConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields(
                "enabled", "latitude", "longitude", "altitude", "accuracy", "speed",
                "bearing", "satellites", "perPackage"
            ),
            versionReader = LocationConfigPrefs::lastModified,
            snapshotReader = LocationConfigPrefs::buildHookSnapshot,
            decoder = LocationConfigSchema::fromJson,
            importer = LocationConfigPrefs::importSchema,
            validator = ::validateLocation
        ),
        ManagedConfig(
            id = ConfigIds.PACKAGE_LIST,
            currentSchemaVersion = PackageListConfigSchema.SCHEMA_VERSION,
            realtimeAvailable = true,
            requiredFields = requiredFields("enabled", "defaultMode", "templates", "appRules"),
            versionReader = PackageVisibilityPrefs::lastModified,
            snapshotReader = PackageVisibilityPrefs::buildHookSnapshot,
            decoder = PackageListConfigSchema::fromJson,
            importer = PackageVisibilityPrefs::importSchema,
            validator = ::validatePackageList
        ),
        ManagedConfig(
            id = ConfigIds.SENSOR,
            currentSchemaVersion = SensorConfigSchema.SCHEMA_VERSION,
            requiredFields = requiredFields(
                "enabled", "hideAll", "precisionBySensorType", "sensorEntries",
                "vendorReplacement", "vendorKeywords", "blacklistSize", "injectionSize"
            ),
            versionReader = SensorConfigPrefs::lastModified,
            snapshotReader = SensorConfigPrefs::buildHookSnapshot,
            decoder = SensorConfigSchema::fromJson,
            importer = SensorConfigPrefs::importSchema,
            validator = ::validateSensor
        ),
        ManagedConfig(
            id = ConfigIds.DEVICE_INFO,
            currentSchemaVersion = DeviceInfoConfigSchema.SCHEMA_VERSION,
            requiredFields = requiredFields(
                "enabled", "presetId", "brand", "manufacturer", "model", "device", "product",
                "board", "hardware", "display", "host", "id", "tags", "type", "user",
                "fingerprint", "time"
            ),
            versionReader = DeviceInfoPrefs::lastModified,
            snapshotReader = DeviceInfoPrefs::buildHookSnapshot,
            decoder = DeviceInfoConfigSchema::fromJson,
            importer = DeviceInfoPrefs::importSchema,
            validator = { schema -> validateBase(schema) + DeviceInfoPrefs.validateSchema(schema) }
        ),
        ManagedConfig(
            id = ConfigIds.CLIPBOARD,
            currentSchemaVersion = ClipboardConfigSchema.SCHEMA_VERSION,
            requiredFields = requiredFields("enabled", "defaultPolicy", "appFilter", "appPolicies"),
            versionReader = { context ->
                runBlocking(Dispatchers.IO) { ClipboardPromptPrefs.lastModified(context) }
            },
            snapshotReader = { context ->
                runBlocking(Dispatchers.IO) { ClipboardPromptPrefs.buildHookSnapshot(context) }
            },
            decoder = ClipboardConfigSchema::fromJson,
            importer = { context, schema ->
                runBlocking(Dispatchers.IO) { ClipboardPromptPrefs.importSchema(context, schema) }
            },
            validator = { schema -> validateBase(schema) + ClipboardPromptPrefs.validateSchema(schema) }
        )
    )

    private fun requiredFields(vararg fields: String): Set<String> =
        setOf("schemaVersion", "lastModified") + fields

    private fun validateBase(schema: ConfigSchema): List<String> = buildList {
        if (schema.lastModified < 0L) add("lastModified must not be negative")
    }

    private fun validateApp(schema: AppConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        if (schema.language !in SUPPORTED_LANGUAGES) {
            add("unsupported language: ${schema.language}")
        }
    }

    private fun validateIdentifier(schema: IdentifierConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        validateText("androidId", schema.androidId, 64)
        validateText("gaid", schema.gaid, 64)
        validateText("widevineDrmId", schema.widevineDrmId, 256)
        validateText("appSetId", schema.appSetId, 256)
        validateText("serial", schema.serial, 128)
        validateSlotMap("imeiBySlot", schema.imeiBySlot, 15)
        validateSlotMap("tacBySlot", schema.tacBySlot, 8)
    }

    private fun validateSim(schema: SimConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        if (schema.simProfiles.size > MAX_SLOT_COUNT) {
            add("simProfiles exceeds $MAX_SLOT_COUNT entries")
        }
        val slots = HashSet<Int>()
        schema.simProfiles.forEachIndexed { index, profile ->
            val prefix = "simProfiles[$index]"
            if (profile.simSlotIndex !in VALID_SLOT_RANGE) add("$prefix has invalid simSlotIndex")
            if (!slots.add(profile.simSlotIndex)) add("$prefix duplicates simSlotIndex")
            validateText("$prefix.iccId", profile.iccId, 128)
            validateText("$prefix.displayName", profile.displayName, 256)
            validateText("$prefix.carrierName", profile.carrierName, 256)
            validateText("$prefix.number", profile.number, 64)
            validateText("$prefix.cardString", profile.cardString, 128)
            validateText("$prefix.groupOwner", profile.groupOwner, 256)
            if (profile.countryIso.isNotEmpty() && !COUNTRY_ISO.matches(profile.countryIso)) {
                add("$prefix has invalid countryIso")
            }
            if (profile.mcc.isNotEmpty() && !MCC.matches(profile.mcc)) add("$prefix has invalid mcc")
            if (profile.mnc.isNotEmpty() && !MNC.matches(profile.mnc)) add("$prefix has invalid mnc")
            if (profile.imei.isNotEmpty() && !IMEI.matches(profile.imei)) add("$prefix has invalid imei")
            if (profile.portIndex < 0) add("$prefix has negative portIndex")
        }
    }

    private fun validateWifi(schema: WifiConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        if (schema.currentSsid.isBlank() || schema.currentSsid.length > MAX_SSID_LENGTH) {
            add("currentSsid must contain 1..$MAX_SSID_LENGTH characters")
        }
        if (!MAC_ADDRESS.matches(schema.currentBssid)) add("currentBssid is invalid")
        if (!IPV4.matches(schema.ipAddress)) add("ipAddress is invalid")
        if (!IPV4.matches(schema.gateway)) add("gateway is invalid")
        if (!IPV4.matches(schema.dns1)) add("dns1 is invalid")
        if (!IPV4.matches(schema.dns2)) add("dns2 is invalid")
        if (schema.scanResults.size > MAX_NETWORKS) add("scanResults exceeds $MAX_NETWORKS entries")
        schema.scanResults.forEachIndexed { index, network ->
            if (network.ssid.length > MAX_SSID_LENGTH) add("scanResults[$index].ssid is too long")
            if (!MAC_ADDRESS.matches(network.bssid)) add("scanResults[$index].bssid is invalid")
        }
    }

    private fun validateBluetooth(schema: BluetoothConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        validateText("deviceName", schema.deviceName, MAX_BLUETOOTH_NAME_LENGTH)
        listOf(
            "connectedDevices" to schema.connectedDevices,
            "scanResults" to schema.scanResults
        ).forEach { (name, devices) ->
            if (devices.size > MAX_BLUETOOTH_DEVICES) {
                add("$name exceeds $MAX_BLUETOOTH_DEVICES entries")
            }
            devices.forEachIndexed { index, device ->
                validateText("$name[$index].name", device.name, MAX_BLUETOOTH_NAME_LENGTH)
                if (!MAC_ADDRESS.matches(device.address)) add("$name[$index].address is invalid")
            }
        }
    }

    private fun validateLocation(schema: LocationConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        validateLocationValues(
            prefix = "location",
            latitude = schema.latitude,
            longitude = schema.longitude,
            altitude = schema.altitude,
            accuracy = schema.accuracy,
            speed = schema.speed,
            bearing = schema.bearing,
            satellites = schema.satellites
        )
        if (schema.perPackage.size > MAX_LOCATION_PROFILES) {
            add("perPackage exceeds $MAX_LOCATION_PROFILES entries")
        }
        schema.perPackage.forEach { (packageName, profile) ->
            if (!PACKAGE_NAME.matches(packageName)) add("perPackage contains invalid packageName")
            validateLocationValues(
                prefix = "perPackage[$packageName]",
                latitude = profile.latitude,
                longitude = profile.longitude,
                altitude = profile.altitude,
                accuracy = profile.accuracy,
                speed = profile.speed,
                bearing = profile.bearing,
                satellites = profile.satellites
            )
        }
    }

    private fun MutableList<String>.validateLocationValues(
        prefix: String,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        speed: Float,
        bearing: Float,
        satellites: Int
    ) {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) add("$prefix.latitude is invalid")
        if (!longitude.isFinite() || longitude !in -180.0..180.0) add("$prefix.longitude is invalid")
        if (!altitude.isFinite() || altitude !in -1_000.0..100_000.0) add("$prefix.altitude is invalid")
        if (!accuracy.isFinite() || accuracy !in 0f..100_000f) add("$prefix.accuracy is invalid")
        if (!speed.isFinite() || speed !in 0f..100_000f) add("$prefix.speed is invalid")
        if (!bearing.isFinite() || bearing !in 0f..360f) add("$prefix.bearing is invalid")
        if (satellites !in 0..MAX_SATELLITES) add("$prefix.satellites is invalid")
    }

    private fun validatePackageList(schema: PackageListConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        if (schema.defaultMode !in DISPLAY_MODES) add("defaultMode is invalid")
        if (schema.templates.size > MAX_TEMPLATES) add("templates exceeds $MAX_TEMPLATES entries")
        if (schema.appRules.size > MAX_APP_RULES) add("appRules exceeds $MAX_APP_RULES entries")

        val templateIds = HashSet<String>()
        schema.templates.forEachIndexed { index, template ->
            val prefix = "templates[$index]"
            if (template.id.isBlank() || template.id.length > MAX_IDENTIFIER_LENGTH) {
                add("$prefix.id is invalid")
            } else if (!templateIds.add(template.id)) {
                add("$prefix.id is duplicated")
            }
            if (template.name.length > MAX_TEMPLATE_NAME_LENGTH) add("$prefix.name is too long")
            if (template.parentId != null && template.parentId.length > MAX_IDENTIFIER_LENGTH) {
                add("$prefix.parentId is too long")
            }
            if (template.parentId == template.id) add("$prefix cannot be its own parent")
            if (template.listMode !in TEMPLATE_LIST_MODES) add("$prefix.listMode is invalid")
            validatePackages("$prefix.visiblePackages", template.visiblePackages)
        }
        schema.templates.forEachIndexed { index, template ->
            if (template.parentId != null && template.parentId !in templateIds) {
                add("templates[$index].parentId does not exist")
            }
        }
        if (schema.defaultTemplateId != null && schema.defaultTemplateId !in templateIds) {
            add("defaultTemplateId does not exist")
        }

        val rulePackages = HashSet<String>()
        schema.appRules.forEachIndexed { index, rule ->
            val prefix = "appRules[$index]"
            if (!PACKAGE_NAME.matches(rule.packageName)) add("$prefix.packageName is invalid")
            if (!rulePackages.add(rule.packageName)) add("$prefix.packageName is duplicated")
            if (rule.mode !in DISPLAY_MODES) add("$prefix.mode is invalid")
            if (rule.templateId != null && rule.templateId !in templateIds) {
                add("$prefix.templateId does not exist")
            }
            validatePackages("$prefix.visiblePackages", rule.visiblePackages)
        }
    }

    private fun MutableList<String>.validatePackages(name: String, packages: List<String>) {
        if (packages.size > MAX_PACKAGES_PER_LIST) add("$name exceeds $MAX_PACKAGES_PER_LIST entries")
        if (packages.any { !PACKAGE_NAME.matches(it) }) add("$name contains an invalid packageName")
        if (packages.size != packages.toSet().size) add("$name contains duplicates")
    }

    private fun validateSensor(schema: SensorConfigSchema): List<String> = buildList {
        addAll(validateBase(schema))
        if (schema.precisionBySensorType.size > MAX_SENSOR_TYPES) {
            add("precisionBySensorType exceeds $MAX_SENSOR_TYPES entries")
        }
        schema.precisionBySensorType.forEach { (type, level) ->
            if (type <= 0) add("precisionBySensorType contains invalid sensor type")
            if (level !in SensorConfigPrefs.PRECISION_ORIGINAL..SensorConfigPrefs.PRECISION_HIGH) {
                add("precisionBySensorType[$type] has invalid precision")
            }
        }
        if (schema.sensorEntries.size > MAX_SENSOR_ENTRIES) {
            add("sensorEntries exceeds $MAX_SENSOR_ENTRIES entries")
        }
        val ids = HashSet<String>()
        schema.sensorEntries.forEachIndexed { index, entry ->
            val prefix = "sensorEntries[$index]"
            if (entry.type <= 0) add("$prefix.type is invalid")
            validateText("$prefix.name", entry.name, MAX_SENSOR_TEXT_LENGTH)
            validateText("$prefix.vendor", entry.vendor, MAX_SENSOR_TEXT_LENGTH)
            if (entry.id.isBlank() || entry.id.length > MAX_SENSOR_ID_LENGTH) {
                add("$prefix.id is invalid")
            } else if (!ids.add(entry.id)) {
                add("$prefix.id is duplicated")
            }
        }
        validateText("vendorReplacement", schema.vendorReplacement, MAX_SENSOR_TEXT_LENGTH)
        validateText("vendorKeywords", schema.vendorKeywords, MAX_SENSOR_TEXT_LENGTH)
        if (schema.blacklistSize != schema.sensorEntries.count { it.hidden }) {
            add("blacklistSize does not match sensorEntries")
        }
        if (schema.injectionSize != schema.sensorEntries.count { it.isCustom }) {
            add("injectionSize does not match sensorEntries")
        }
    }

    private fun MutableList<String>.validateSlotMap(name: String, values: Map<Int, String>, digits: Int) {
        if (values.size > MAX_SLOT_COUNT) add("$name exceeds $MAX_SLOT_COUNT entries")
        values.forEach { (slot, value) ->
            if (slot !in VALID_SLOT_RANGE) add("$name contains invalid slot $slot")
            if (value.length != digits || !value.all(Char::isDigit)) add("$name[$slot] is invalid")
        }
    }

    private fun MutableList<String>.validateText(name: String, value: String, maxLength: Int) {
        if (value.length > maxLength) add("$name exceeds $maxLength characters")
        if (value.any(Char::isISOControl)) add("$name contains control characters")
    }

    private val byId = all.associateBy(ManagedConfig<*>::id).also {
        require(it.size == all.size) { "Duplicate managed config id" }
    }

    fun find(id: String): ManagedConfig<*>? = byId[id]

    fun require(id: String): ManagedConfig<*> =
        find(id) ?: throw IllegalArgumentException("Unknown config id: $id")

    fun versions(context: Context): Map<String, Long> =
        all.associate { it.id to it.version(context) }

    private val SUPPORTED_LANGUAGES = setOf("system", "en", "zh-CN", "ja", "ko")
    private val VALID_SLOT_RANGE = 0..31
    private const val MAX_SLOT_COUNT = 32
    private const val MAX_SSID_LENGTH = 32
    private const val MAX_NETWORKS = 256
    private const val MAX_BLUETOOTH_NAME_LENGTH = 248
    private const val MAX_BLUETOOTH_DEVICES = 256
    private const val MAX_SATELLITES = 255
    private const val MAX_LOCATION_PROFILES = 2_048
    private const val MAX_TEMPLATES = 256
    private const val MAX_APP_RULES = 4_096
    private const val MAX_PACKAGES_PER_LIST = 4_096
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_TEMPLATE_NAME_LENGTH = 256
    private const val MAX_SENSOR_TYPES = 256
    private const val MAX_SENSOR_ENTRIES = 1_024
    private const val MAX_SENSOR_TEXT_LENGTH = 1_024
    private const val MAX_SENSOR_ID_LENGTH = 128
    private val MAC_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex(
        "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$"
    )
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    private val COUNTRY_ISO = Regex("^[A-Za-z]{2}$")
    private val MCC = Regex("^[0-9]{3}$")
    private val MNC = Regex("^[0-9]{2,3}$")
    private val IMEI = Regex("^[0-9]{1,15}$")
    private val DISPLAY_MODES = PackageVisibilityPrefs.DisplayMode.entries.mapTo(HashSet()) { it.name }
    private val TEMPLATE_LIST_MODES =
        PackageVisibilityPrefs.TemplateListMode.entries.mapTo(HashSet()) { it.name }
}
