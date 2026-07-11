package asia.nana7mi.arirang.data.datastore

import android.content.Context
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.config.ConfigRegistry
import asia.nana7mi.arirang.data.config.ConfigIds
import asia.nana7mi.arirang.data.config.ManagedConfigSnapshot
import asia.nana7mi.arirang.data.datastore.schema.IdentifierConfigSchema
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date

object SubmoduleConfigFiles {
    fun configFile(context: Context): File {
        val deContext = context.createDeviceProtectedStorageContext()
        return File(File(deContext.filesDir, BuildConfig.SUBMODULE_CONFIG_DIR), BuildConfig.SUBMODULE_CONFIG_FILE)
    }

    @Synchronized
    fun write(
        context: Context,
        simConfig: SimConfigPrefs.Config = SimConfigPrefs.loadConfig(context),
        deviceConfig: DeviceInfoPrefs.Config = DeviceInfoPrefs.loadConfig(context),
        uniqueIdentifierConfig: UniqueIdentifierPrefs.Config = UniqueIdentifierPrefs.loadConfig(context),
        sensorConfig: SensorConfigPrefs.Config = SensorConfigPrefs.loadConfig(context)
    ) {
        val configFileDe = configFile(context)
        val snapshots = HashMap<String, ManagedConfigSnapshot>()
        val identifierVersion = UniqueIdentifierPrefs.lastModified(context)
        snapshots[ConfigIds.UNIQUE_IDENTIFIER] = ManagedConfigSnapshot(
            identifierVersion,
            IdentifierConfigSchema(
                enabled = uniqueIdentifierConfig.enabled,
                androidId = uniqueIdentifierConfig.androidId,
                gaid = uniqueIdentifierConfig.gaid,
                widevineDrmId = uniqueIdentifierConfig.widevineDrmId,
                appSetId = uniqueIdentifierConfig.appSetId,
                serial = uniqueIdentifierConfig.serial,
                imeiBySlot = uniqueIdentifierConfig.imeiBySlot,
                tacBySlot = uniqueIdentifierConfig.tacBySlot,
                lastModified = identifierVersion
            ).toJson()
        )
        fun managed(id: String) = snapshots.getOrPut(id) { ConfigRegistry.require(id).read(context) }
        fun configVersion(id: String) = managed(id).version
        fun configSnapshot(id: String) = managed(id).payload

        val simProperties = buildSimProperties(simConfig)
        val json = JSONObject()
            .put("version", Date().time)
            .put("enabled", true)
            .put("globalConfigVersion", configVersion(ConfigIds.GLOBAL))
            .put("globalConfigSnapshot", configSnapshot(ConfigIds.GLOBAL))
            .put("deviceInfoEnabled", deviceConfig.enabled)
            .put("devicePresetId", deviceConfig.presetId)
            .put("buildBrand", deviceConfig.brand)
            .put("buildManufacturer", deviceConfig.manufacturer)
            .put("buildModel", deviceConfig.model)
            .put("buildDevice", deviceConfig.device)
            .put("buildProduct", deviceConfig.product)
            .put("buildBoard", deviceConfig.board)
            .put("buildHardware", deviceConfig.hardware)
            .put("buildDisplay", deviceConfig.display)
            .put("buildHost", deviceConfig.host)
            .put("buildId", deviceConfig.id)
            .put("buildTags", deviceConfig.tags)
            .put("buildType", deviceConfig.type)
            .put("buildUser", deviceConfig.user)
            .put("buildFingerprint", deviceConfig.fingerprint)
            .put("buildTime", deviceConfig.time)
            .put("uniqueIdentifierEnabled", uniqueIdentifierConfig.enabled)
            .put("androidId", uniqueIdentifierConfig.androidId)
            .put("gaid", uniqueIdentifierConfig.gaid)
            .put("widevineDrmId", uniqueIdentifierConfig.widevineDrmId)
            .put("appSetId", uniqueIdentifierConfig.appSetId)
            .put("serial", uniqueIdentifierConfig.serial)
            .put("imeiBySlot", JSONObject(uniqueIdentifierConfig.imeiBySlot.mapKeys { it.key.toString() }))
            .put("tacBySlot", JSONObject(uniqueIdentifierConfig.tacBySlot.mapKeys { it.key.toString() }))
            .put("uniqueIdentifierConfigVersion", configVersion(ConfigIds.UNIQUE_IDENTIFIER))
            .put("uniqueIdentifierConfigSnapshot", configSnapshot(ConfigIds.UNIQUE_IDENTIFIER))
            .put("gsmSimOperatorIsoCountry", simProperties.countryIso)
            .put("gsmOperatorIsoCountry", simProperties.countryIso)
            .put("gsmSimOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmSimOperatorAlpha", simProperties.alpha)
            .put("gsmOperatorAlpha", simProperties.alpha)
            .put("simConfigVersion", configVersion(ConfigIds.SIM))
            .put("simConfigSnapshot", configSnapshot(ConfigIds.SIM))
            .put("hookLogConfigVersion", configVersion(ConfigIds.HOOK_LOG))
            .put("hookLogConfigSnapshot", configSnapshot(ConfigIds.HOOK_LOG))
            .put("wifiConfigVersion", configVersion(ConfigIds.WIFI))
            .put("wifiConfigSnapshot", configSnapshot(ConfigIds.WIFI))
            .put("bluetoothConfigVersion", configVersion(ConfigIds.BLUETOOTH))
            .put("bluetoothConfigSnapshot", configSnapshot(ConfigIds.BLUETOOTH))
            .put("locationConfigVersion", configVersion(ConfigIds.LOCATION))
            .put("locationConfigSnapshot", configSnapshot(ConfigIds.LOCATION))
            .put("packageListConfigVersion", configVersion(ConfigIds.PACKAGE_LIST))
            .put("packageListConfigSnapshot", configSnapshot(ConfigIds.PACKAGE_LIST))
            .put("sensorConfigEnabled", sensorConfig.enabled)
            .put("sensorHideAll", sensorConfig.hideAll)
            .put("sensorGlobalVendorReplacement", sensorConfig.vendorReplacement)
            .put(
                "sensorVendorKeywords",
                JSONArray(sensorConfig.vendorKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            )
            .put("sensorBlacklist", buildSensorBlacklist(sensorConfig))
            .put("sensorOverrides", buildSensorOverrides(sensorConfig))
            .put("sensorInjections", buildSensorInjections(sensorConfig))
            .put("sensorPrecisionRules", buildSensorPrecisionRules(sensorConfig))
            .put("sensorConfigVersion", configVersion(ConfigIds.SENSOR))
            .put("sensorConfigSnapshot", configSnapshot(ConfigIds.SENSOR))
            .toString()

        val configFileCe = ceConfigFile(context)
        writeConfigPair(configFileCe, configFileDe, json)
    }

    private fun writeConfigPair(first: File, second: File, json: String) {
        val firstBefore = first.takeIf(File::isFile)?.readBytes()
        val secondBefore = second.takeIf(File::isFile)?.readBytes()
        try {
            writeConfigFile(first, json)
            writeConfigFile(second, json)
        } catch (failure: Throwable) {
            runCatching { restoreConfigFile(first, firstBefore) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            runCatching { restoreConfigFile(second, secondBefore) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun restoreConfigFile(file: File, previous: ByteArray?) {
        if (previous == null) {
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Unable to remove partially written ${file.name}")
            }
        } else {
            writeConfigFile(file, previous.toString(Charsets.UTF_8))
        }
    }

    private fun writeConfigFile(file: File, json: String) {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                throw IllegalStateException("Unable to atomically replace ${file.name}")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.parentFile?.setExecutable(true, false)
    }

    private fun ceConfigFile(context: Context): File {
        return File(File(context.filesDir, BuildConfig.SUBMODULE_CONFIG_DIR), BuildConfig.SUBMODULE_CONFIG_FILE)
    }

    private fun buildSensorBlacklist(config: SensorConfigPrefs.Config): JSONArray {
        val array = JSONArray()
        if (config.hideAll) return array

        if (config.disableAccel) {
            array.put(JSONObject().put("type", android.hardware.Sensor.TYPE_ACCELEROMETER))
        }
        if (config.disableGyro) {
            array.put(JSONObject().put("type", android.hardware.Sensor.TYPE_GYROSCOPE))
        }
        if (config.disableMagnetic) {
            array.put(JSONObject().put("type", android.hardware.Sensor.TYPE_MAGNETIC_FIELD))
        }

        config.sensorEntries.filter { it.hidden && !it.isCustom }.forEach { entry ->
            array.put(
                JSONObject()
                    .put("type", entry.type)
                    .put("nameContains", entry.name)
            )
        }
        return array
    }

    private fun buildSensorOverrides(config: SensorConfigPrefs.Config): JSONArray {
        val array = JSONArray()
        if (config.hideAll) return array

        config.sensorEntries.filter { !it.hidden && !it.isCustom }.forEach { entry ->
            array.put(
                JSONObject()
                    .put("matchType", entry.type)
                    .put("newName", entry.name)
                    .put("newVendor", entry.vendor)
                    .put("newType", entry.type)
            )
        }
        return array
    }

    private fun buildSensorInjections(config: SensorConfigPrefs.Config): JSONArray {
        val array = JSONArray()
        if (config.hideAll) return array

        config.sensorEntries.filter { it.isCustom && !it.hidden }.forEach { entry ->
            array.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("vendor", entry.vendor)
                    .put("type", entry.type)
                    .put("handle", 0)
            )
        }
        return array
    }

    private fun buildSensorPrecisionRules(config: SensorConfigPrefs.Config): JSONArray {
        val array = JSONArray()
        if (config.hideAll) return array

        config.precisionBySensorType.forEach { (type, level) ->
            if (level != SensorConfigPrefs.PRECISION_ORIGINAL) {
                array.put(
                    JSONObject()
                        .put("type", type)
                        .put("level", level)
                )
            }
        }
        return array
    }

    private fun buildSimProperties(config: SimConfigPrefs.Config): SimProperties {
        if (!config.enabled || config.hideSim) return SimProperties()

        val profilesBySlot = config.simInfoBySlot
        if (profilesBySlot.isEmpty()) return SimProperties()

        fun slotPropertyValue(value: (asia.nana7mi.arirang.model.SimInfo) -> String?): String {
            val lastSlot = profilesBySlot.keys.maxOrNull() ?: return ""
            return (0..lastSlot).joinToString(",") { slot ->
                profilesBySlot[slot]?.let(value).orEmpty()
            }
        }

        return SimProperties(
            countryIso = slotPropertyValue { it.countryIso },
            operatorNumeric = slotPropertyValue {
                val mcc = it.mcc.orEmpty()
                val mnc = it.mnc.orEmpty()
                (mcc + mnc).takeIf { numeric -> numeric.isNotBlank() }
            },
            alpha = slotPropertyValue { it.carrierName ?: it.displayName }
        )
    }

    private data class SimProperties(
        val countryIso: String = "",
        val operatorNumeric: String = "",
        val alpha: String = ""
    )
}
