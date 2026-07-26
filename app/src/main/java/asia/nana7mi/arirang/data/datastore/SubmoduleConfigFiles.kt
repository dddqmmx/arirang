package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.util.Log
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.data.config.ConfigRegistry
import asia.nana7mi.arirang.data.config.ConfigIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date

object SubmoduleConfigFiles {
    private const val TAG = "SubmoduleConfigFiles"

    /**
     * Maps a managed config id to the `<prefix>Version` key it is written
     * under, so a config that fails validation can keep its last written
     * version instead of regressing to 0. Kept in step with the `.put(...)`
     * calls in [write] by SubmoduleConfigFilesTest.
     */
    internal val CONFIG_KEY_PREFIX = mapOf(
        ConfigIds.GLOBAL to "globalConfig",
        ConfigIds.UNIQUE_IDENTIFIER to "uniqueIdentifierConfig",
        ConfigIds.SIM to "simConfig",
        ConfigIds.HOOK_LOG to "hookLogConfig",
        ConfigIds.WIFI to "wifiConfig",
        ConfigIds.BLUETOOTH to "bluetoothConfig",
        ConfigIds.LOCATION to "locationConfig",
        ConfigIds.PACKAGE_LIST to "packageListConfig",
        ConfigIds.SENSOR to "sensorConfig"
    )

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
        val versions = HashMap<String, Long>()
        versions[ConfigIds.UNIQUE_IDENTIFIER] = UniqueIdentifierPrefs.lastModified(context)
        val lastWritten: JSONObject? by lazy { readLastWritten(configFileDe) }

        /**
         * Last-modified version for [id], degrading to the last written value
         * when that config currently fails validation.
         *
         * [ConfigRegistry] reads throw on an invalid config, and this runs for
         * nine configs while building one file. Since `write()` is the last
         * statement of every `*Prefs.saveConfig` -- and runs *after* the value
         * has already been committed -- letting that throw escape meant one bad
         * field (say a BSSID a digit short) made every subsequent save in the
         * app fail, permanently, and crashed the activity that triggered it.
         *
         * Degrading keeps the blast radius at the config that is actually
         * broken: the other eight still reach the native layer.
         */
        fun configVersion(id: String): Long = versions.getOrPut(id) {
            runCatching { ConfigRegistry.require(id).read(context).version }.getOrElse { failure ->
                val salvaged = CONFIG_KEY_PREFIX[id]
                    ?.let { prefix -> lastWritten?.optLong("${prefix}Version") }
                    ?.takeIf { it > 0L }
                Log.e(
                    TAG,
                    "config '$id' failed validation; " +
                        if (salvaged != null) "reusing its last written version"
                        else "reporting version 0 for it",
                    failure
                )
                salvaged ?: 0L
            }
        }

        val simProperties = buildSimProperties(simConfig)
        val configJson = JSONObject()
            .put("version", Date().time)
            .put("enabled", true)
            .put("globalConfigVersion", configVersion(ConfigIds.GLOBAL))
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
            .put("gsmSimOperatorIsoCountry", simProperties.countryIso)
            .put("gsmOperatorIsoCountry", simProperties.countryIso)
            .put("gsmSimOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmSimOperatorAlpha", simProperties.alpha)
            .put("gsmOperatorAlpha", simProperties.alpha)
            .put("simConfigVersion", configVersion(ConfigIds.SIM))
            .put("hookLogConfigVersion", configVersion(ConfigIds.HOOK_LOG))
            .put("wifiConfigVersion", configVersion(ConfigIds.WIFI))
            .put("bluetoothConfigVersion", configVersion(ConfigIds.BLUETOOTH))
            .put("locationConfigVersion", configVersion(ConfigIds.LOCATION))
            .put("packageListConfigVersion", configVersion(ConfigIds.PACKAGE_LIST))
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
        val json = configJson.toString()
        warnIfOverConsumerLimit(configJson, json)

        val configFileCe = ceConfigFile(context)
        writeConfigPair(configFileCe, configFileDe, json)
    }

    /**
     * The size ceiling both consumers of config.json enforce.
     *
     * `submodule_config.cpp`'s `kMaxConfigSize` makes `apply_json_config` return
     * false above this, so `ArirangZygisk` keeps its compiled-in defaults — where
     * `enabled`, `device_info_enabled` and `sensor_config_enabled` are all false.
     * `module/lib/common.sh` skips the candidate, leaving `ARIRANG_CONFIG_PATH`
     * empty so `resetprop.sh` sets no properties and DRM ID staging is skipped.
     *
     * Exceeding it therefore disables the whole native and shell layer, silently.
     * The producer has no matching budget of its own: `ManagedConfig` allows
     * 512 KiB *per config* and `ConfigRegistry` permits 4096 app rules each with
     * up to 4096 packages, so a large package-visibility config can be many times
     * over this while the manager reports everything as valid.
     */
    private const val CONSUMER_MAX_CONFIG_BYTES = 65_536

    private fun warnIfOverConsumerLimit(configJson: JSONObject, json: String) {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size <= CONSUMER_MAX_CONFIG_BYTES) return

        val biggest = configJson.keys().asSequence()
            .map { key -> key to configJson.optString(key).length }
            .sortedByDescending { it.second }
            .take(3)
            .joinToString { "${it.first}=${it.second}B" }
        Log.e(
            TAG,
            "config.json is $size bytes, over the $CONSUMER_MAX_CONFIG_BYTES-byte limit both the " +
                "native module and post-fs-data.sh enforce. Both will ignore the file and fall back " +
                "to defaults, disabling native spoofing entirely. Largest keys: $biggest"
        )
    }

    private fun readLastWritten(file: File): JSONObject? = runCatching {
        JSONObject(file.takeIf(File::isFile)?.readText(Charsets.UTF_8) ?: return@runCatching null)
    }.getOrNull()

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
