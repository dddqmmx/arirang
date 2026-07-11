package asia.nana7mi.arirang.data.datastore

import android.content.Context
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.schema.SensorConfigSchema
import asia.nana7mi.arirang.data.datastore.schema.SensorEntrySchema
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

    fun importSchema(context: Context, schema: SensorConfigSchema) {
        val seenIds = mutableSetOf<String>()
        val legacy = loadConfig(context).takeIf {
            schema.schemaVersion < SensorConfigSchema.SCHEMA_VERSION
        }
        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                hideAll = schema.hideAll,
                precisionBySensorType = (legacy?.precisionBySensorType ?: schema.precisionBySensorType)
                    .entries.asSequence()
                    .filter { (type, level) -> type > 0 && level in PRECISION_ORIGINAL..PRECISION_HIGH }
                    .take(MAX_SENSOR_TYPES)
                    .associate { it.toPair() },
                sensorEntries = (legacy?.sensorEntries?.map { entry ->
                    SensorEntrySchema(
                        name = entry.name,
                        vendor = entry.vendor,
                        type = entry.type,
                        hidden = entry.hidden,
                        isCustom = entry.isCustom,
                        id = entry.id
                    )
                } ?: schema.sensorEntries).asSequence().take(MAX_SENSOR_ENTRIES)
                    .mapNotNull { entry -> entry.toEntryOrNull(seenIds) }
                    .toList(),
                vendorReplacement = (legacy?.vendorReplacement ?: schema.vendorReplacement).take(MAX_TEXT_LENGTH),
                vendorKeywords = (legacy?.vendorKeywords ?: schema.vendorKeywords).take(MAX_TEXT_LENGTH)
            )
        )
    }

    fun lastModified(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return SensorConfigSchema(
            enabled = config.enabled,
            hideAll = config.hideAll,
            precisionBySensorType = config.precisionBySensorType,
            sensorEntries = config.sensorEntries.map { entry ->
                SensorEntrySchema(
                    name = entry.name,
                    vendor = entry.vendor,
                    type = entry.type,
                    hidden = entry.hidden,
                    isCustom = entry.isCustom,
                    id = entry.id
                )
            },
            vendorReplacement = config.vendorReplacement,
            vendorKeywords = config.vendorKeywords,
            blacklistSize = config.sensorEntries.count { it.hidden },
            injectionSize = config.sensorEntries.count { it.isCustom },
            lastModified = lastModified(context)
        ).toJson()
    }

    private fun SensorEntrySchema.toEntryOrNull(seenIds: MutableSet<String>): SensorEntry? {
        if (type <= 0) return null
        val normalizedId = id.trim().take(MAX_ID_LENGTH)
        if (normalizedId.isBlank() || !seenIds.add(normalizedId)) return null
        return SensorEntry(
            name = name.take(MAX_TEXT_LENGTH),
            vendor = vendor.take(MAX_TEXT_LENGTH),
            type = type,
            hidden = hidden,
            isCustom = isCustom,
            id = normalizedId
        )
    }

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

    private const val MAX_SENSOR_TYPES = 256
    private const val MAX_SENSOR_ENTRIES = 1_024
    private const val MAX_TEXT_LENGTH = 1_024
    private const val MAX_ID_LENGTH = 128
}
