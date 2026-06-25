package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object SensorConfigPrefs {
    const val PREFS_NAME = "sensor_config_prefs"

    private const val KEY_LAST_MODIFIED = "last_modified"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HIDE_ALL = "hide_all"

    private const val KEY_PRECISION_BY_SENSOR_TYPE = "precision_by_sensor_type"
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
        val precisionBySensorType: Map<Int, Int> = emptyMap(),
        val sensorEntries: List<SensorEntry> = emptyList(),
        val vendorReplacement: String = "",
        val vendorKeywords: String = ""
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
        
        val precisionJsonStr = prefs.getString(KEY_PRECISION_BY_SENSOR_TYPE, null)
        val precisionMap = mutableMapOf<Int, Int>()
        if (precisionJsonStr != null) {
            try {
                val json = JSONObject(precisionJsonStr)
                json.keys().forEach { key ->
                    precisionMap[key.toInt()] = json.getInt(key)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hideAll = prefs.getBoolean(KEY_HIDE_ALL, false),
            precisionBySensorType = precisionMap,
            sensorEntries = entries,
            vendorReplacement = prefs.getString(KEY_VENDOR_REPLACEMENT, null) ?: "",
            vendorKeywords = prefs.getString(KEY_VENDOR_KEYWORDS, null) ?: ""
        )
    }

    fun saveConfig(context: Context, config: Config) {
        val entriesArray = JSONArray()
        config.sensorEntries.forEach { entriesArray.put(it.toJson()) }

        val precisionJson = JSONObject()
        config.precisionBySensorType.forEach { (type, level) ->
            precisionJson.put(type.toString(), level)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(KEY_LAST_MODIFIED, Date().time)
            putBoolean(KEY_ENABLED, config.enabled)
            putBoolean(KEY_HIDE_ALL, config.hideAll)
            putString(KEY_PRECISION_BY_SENSOR_TYPE, precisionJson.toString())
            putString(KEY_SENSOR_ENTRIES, entriesArray.toString())
            putString(KEY_VENDOR_REPLACEMENT, config.vendorReplacement)
            putString(KEY_VENDOR_KEYWORDS, config.vendorKeywords)
        }
        SubmoduleConfigFiles.write(context)
    }

    fun lastModified(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return JSONObject().apply {
            put("version", lastModified(context))
            put(KEY_ENABLED, config.enabled)
            put(KEY_HIDE_ALL, config.hideAll)
            put("blacklistSize", config.sensorEntries.count { it.hidden })
            put("injectionSize", config.sensorEntries.count { it.isCustom })
        }.toString()
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
