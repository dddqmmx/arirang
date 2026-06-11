package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object SensorConfigPrefs {
    const val PREFS_NAME = "sensor_config_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_HIDE_ALL = "hide_all"

    private const val KEY_DISABLE_MIC = "disable_mic"
    private const val KEY_DISABLE_CAMERA_FRONT = "disable_camera_front"
    private const val KEY_DISABLE_CAMERA_REAR = "disable_camera_rear"

    private const val KEY_DISABLE_ACCEL = "disable_accel"
    private const val KEY_DISABLE_GYRO = "disable_gyro"
    private const val KEY_DISABLE_MAGNETIC = "disable_magnetic"

    private const val KEY_PRECISION_LEVEL = "precision_level"
    private const val KEY_SENSOR_ENTRIES = "sensor_entries"
    private const val KEY_VENDOR_REPLACEMENT = "vendor_replacement"
    private const val KEY_VENDOR_KEYWORDS = "vendor_keywords"

    const val PRECISION_ORIGINAL = 0
    const val PRECISION_LOW = 1
    const val PRECISION_MEDIUM = 2
    const val PRECISION_HIGH = 3

    /**
     * A single sensor entry in the managed list.
     * [isCustom] marks sensors added by the user (not from the device).
     */
    data class SensorEntry(
        val name: String,
        val vendor: String,
        val type: Int,
        val hidden: Boolean = false,
        val isCustom: Boolean = false,
        val id: String = java.util.UUID.randomUUID().toString()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("vendor", vendor)
            .put("type", type)
            .put("hidden", hidden)
            .put("isCustom", isCustom)
            .put("id", id)

        companion object {
            fun fromJson(json: JSONObject): SensorEntry = SensorEntry(
                name = json.optString("name", ""),
                vendor = json.optString("vendor", ""),
                type = json.optInt("type", 0),
                hidden = json.optBoolean("hidden", false),
                isCustom = json.optBoolean("isCustom", false),
                id = json.optString("id", java.util.UUID.randomUUID().toString())
            )
        }
    }

    data class Config(
        val enabled: Boolean = false,
        val hideAll: Boolean = false,
        val disableMic: Boolean = false,
        val disableCameraFront: Boolean = false,
        val disableCameraRear: Boolean = false,
        val disableAccel: Boolean = false,
        val disableGyro: Boolean = false,
        val disableMagnetic: Boolean = false,
        val precisionLevel: Int = PRECISION_ORIGINAL,
        val sensorEntries: List<SensorEntry> = emptyList(),
        val vendorReplacement: String = "",
        val vendorKeywords: String = "xiaomi, qti, qualcomm, samsung, huawei, oppo, vivo, oneplus, meizu, motorola, lenovo, asus, sony, mediatek, mtk, spreadtrum, unisoc, realme, nothing, google"
    )

    fun loadConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entriesJson = prefs.getString(KEY_SENSOR_ENTRIES, null)
        val entries = if (entriesJson != null) {
            val arr = JSONArray(entriesJson)
            (0 until arr.length()).map { SensorEntry.fromJson(arr.getJSONObject(it)) }
        } else {
            emptyList()
        }
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hideAll = prefs.getBoolean(KEY_HIDE_ALL, false),
            disableMic = prefs.getBoolean(KEY_DISABLE_MIC, false),
            disableCameraFront = prefs.getBoolean(KEY_DISABLE_CAMERA_FRONT, false),
            disableCameraRear = prefs.getBoolean(KEY_DISABLE_CAMERA_REAR, false),
            disableAccel = prefs.getBoolean(KEY_DISABLE_ACCEL, false),
            disableGyro = prefs.getBoolean(KEY_DISABLE_GYRO, false),
            disableMagnetic = prefs.getBoolean(KEY_DISABLE_MAGNETIC, false),
            precisionLevel = prefs.getInt(KEY_PRECISION_LEVEL, PRECISION_ORIGINAL),
            sensorEntries = entries,
            vendorReplacement = prefs.getString(KEY_VENDOR_REPLACEMENT, null) ?: "",
            vendorKeywords = prefs.getString(KEY_VENDOR_KEYWORDS, null) ?: "xiaomi, qti, qualcomm, samsung, huawei, oppo, vivo, oneplus, meizu, motorola, lenovo, asus, sony, mediatek, mtk, spreadtrum, unisoc, realme, nothing, google"
        )
    }

    fun saveConfig(context: Context, config: Config) {
        val entriesArray = JSONArray()
        config.sensorEntries.forEach { entriesArray.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putBoolean(KEY_HIDE_ALL, config.hideAll)
            putBoolean(KEY_DISABLE_MIC, config.disableMic)
            putBoolean(KEY_DISABLE_CAMERA_FRONT, config.disableCameraFront)
            putBoolean(KEY_DISABLE_CAMERA_REAR, config.disableCameraRear)
            putBoolean(KEY_DISABLE_ACCEL, config.disableAccel)
            putBoolean(KEY_DISABLE_GYRO, config.disableGyro)
            putBoolean(KEY_DISABLE_MAGNETIC, config.disableMagnetic)
            putInt(KEY_PRECISION_LEVEL, config.precisionLevel)
            putString(KEY_SENSOR_ENTRIES, entriesArray.toString())
            putString(KEY_VENDOR_REPLACEMENT, config.vendorReplacement)
            putString(KEY_VENDOR_KEYWORDS, config.vendorKeywords)
        }
    }

    /**
     * Replace vendor keywords case-aware.
     * Matches known vendor keywords and replaces with [replacement],
     * preserving the original casing pattern:
     *   "xiaomi" + "Google" → "google"
     *   "XIAOMI" + "Google" → "GOOGLE"
     *   "Xiaomi" + "Google" → "Google"
     */
    fun applyCaseAwareReplace(original: String, replacement: String, keywords: List<String>): String {
        var result = original
        for (keyword in keywords) {
            if (keyword.isBlank()) continue
            val regex = Regex(Regex.escape(keyword.trim()), RegexOption.IGNORE_CASE)
            result = regex.replace(result) { match ->
                matchCase(match.value, replacement)
            }
        }
        return result
    }

    private fun matchCase(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        return when {
            original.all { it.isUpperCase() || !it.isLetter() } -> replacement.uppercase()
            original.all { it.isLowerCase() || !it.isLetter() } -> replacement.lowercase()
            original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement
        }
    }
}
