package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import asia.nana7mi.arirang.BuildConfig
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
        bluetoothConfig: BluetoothConfigPrefs.Config = BluetoothConfigPrefs.loadConfig(context)
    ) {
        val configFile = configFile(context)
        configFile.parentFile?.mkdirs()

        val simProperties = buildSimProperties(simConfig)
        val json = JSONObject()
            .put("version", Date().time)
            .put("enabled", true)
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
            .toString()

        configFile.writeText(json)
        configFile.setReadable(false, false)
        configFile.setWritable(false, false)
        configFile.setExecutable(false, false)
        configFile.setReadable(true, true)
        configFile.setWritable(true, true)
        configFile.parentFile?.setExecutable(true, true)
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
