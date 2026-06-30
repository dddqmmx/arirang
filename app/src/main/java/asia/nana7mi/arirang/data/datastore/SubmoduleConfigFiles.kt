package asia.nana7mi.arirang.data.datastore

import android.content.Context
import asia.nana7mi.arirang.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

object SubmoduleConfigFiles {
    fun configFile(context: Context): File {
        val deContext = context.createDeviceProtectedStorageContext()
        return File(File(deContext.filesDir, BuildConfig.SUBMODULE_CONFIG_DIR), BuildConfig.SUBMODULE_CONFIG_FILE)
    }

    fun write(
        context: Context,
        simConfig: SimConfigPrefs.Config = SimConfigPrefs.loadConfig(context),
        deviceConfig: DeviceInfoPrefs.Config = DeviceInfoPrefs.loadConfig(context),
        uniqueIdentifierConfig: UniqueIdentifierPrefs.Config = UniqueIdentifierPrefs.loadConfig(context),
        sensorConfig: SensorConfigPrefs.Config = SensorConfigPrefs.loadConfig(context)
    ) {
        val configFileDe = configFile(context)

        val simProperties = buildSimProperties(simConfig)
        val json = JSONObject()
            .put("version", Date().time)
            .put("enabled", true)
            .put("globalConfigVersion", GlobalConfigPrefs.lastModified(context))
            .put("globalConfigSnapshot", GlobalConfigPrefs.buildHookSnapshot(context))
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
            .put("uniqueIdentifierConfigVersion", UniqueIdentifierPrefs.lastModified(context))
            .put("uniqueIdentifierConfigSnapshot", UniqueIdentifierPrefs.buildHookSnapshot(context))
            .put("gsmSimOperatorIsoCountry", simProperties.countryIso)
            .put("gsmOperatorIsoCountry", simProperties.countryIso)
            .put("gsmSimOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmSimOperatorAlpha", simProperties.alpha)
            .put("gsmOperatorAlpha", simProperties.alpha)
            .put("simConfigVersion", SimConfigPrefs.lastModified(context))
            .put("simConfigSnapshot", SimConfigPrefs.buildHookSnapshot(context))
            .put("hookLogConfigVersion", HookLogSettings.lastModified(context))
            .put("hookLogConfigSnapshot", HookLogSettings.buildHookSnapshot(context))
            .put("wifiConfigVersion", WifiConfigPrefs.lastModified(context))
            .put("wifiConfigSnapshot", WifiConfigPrefs.buildHookSnapshot(context))
            .put("bluetoothConfigVersion", BluetoothConfigPrefs.lastModified(context))
            .put("bluetoothConfigSnapshot", BluetoothConfigPrefs.buildHookSnapshot(context))
            .put("locationConfigVersion", LocationConfigPrefs.lastModified(context))
            .put("locationConfigSnapshot", LocationConfigPrefs.buildHookSnapshot(context))
            .put("packageListConfigVersion", PackageVisibilityPrefs.lastModified(context))
            .put("packageListConfigSnapshot", PackageVisibilityPrefs.buildHookSnapshot(context))
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
            .put("sensorConfigVersion", SensorConfigPrefs.lastModified(context))
            .put("sensorConfigSnapshot", SensorConfigPrefs.buildHookSnapshot(context))
            .toString()

        writeConfigFile(configFileDe, json)
        val configFileCe = ceConfigFile(context)
        writeConfigFile(configFileCe, json)
    }

    private fun writeConfigFile(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
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
