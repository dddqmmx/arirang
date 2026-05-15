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
        deviceConfig: DeviceInfoPrefs.Config = DeviceInfoPrefs.loadConfig(context)
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
            .put("gsmSimOperatorIsoCountry", simProperties.countryIso)
            .put("gsmOperatorIsoCountry", simProperties.countryIso)
            .put("gsmSimOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmOperatorNumeric", simProperties.operatorNumeric)
            .put("gsmSimOperatorAlpha", simProperties.alpha)
            .put("gsmOperatorAlpha", simProperties.alpha)
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

        val profiles = config.simInfoList
        if (profiles.isEmpty()) return SimProperties()

        fun joined(value: (asia.nana7mi.arirang.model.SimInfo) -> String?): String {
            return profiles.joinToString(",") { value(it).orEmpty() }
        }

        return SimProperties(
            countryIso = joined { it.countryIso },
            operatorNumeric = joined {
                val mcc = it.mcc.orEmpty()
                val mnc = it.mnc.orEmpty()
                (mcc + mnc).takeIf { numeric -> numeric.isNotBlank() }
            },
            alpha = joined { it.carrierName ?: it.displayName }
        )
    }

    private data class SimProperties(
        val countryIso: String = "",
        val operatorNumeric: String = "",
        val alpha: String = ""
    )
}
