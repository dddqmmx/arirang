package asia.nana7mi.arirang.data.datastore

import android.content.Context
import asia.nana7mi.arirang.data.datastore.schema.DeviceInfoConfigSchema
import asia.nana7mi.arirang.model.DevicePresetCatalog
import org.json.JSONObject

object DeviceInfoPrefs {
    private const val PREFS_NAME = "device_info_prefs"
    private val DEFAULT_PRESET = DevicePresetCatalog.defaultPreset()

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MODIFIED = "last_modified"
    private const val KEY_PRESET_ID = "preset_id"
    private const val KEY_BRAND = "brand"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_DEVICE = "device"
    private const val KEY_PRODUCT = "product"
    private const val KEY_BOARD = "board"
    private const val KEY_HARDWARE = "hardware"
    private const val KEY_DISPLAY = "display"
    private const val KEY_HOST = "host"
    private const val KEY_ID = "id"
    private const val KEY_TAGS = "tags"
    private const val KEY_TYPE = "type"
    private const val KEY_USER = "user"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_TIME = "time"

    data class Config(
        val enabled: Boolean = false,
        val presetId: String = DEFAULT_PRESET.id,
        val brand: String = DEFAULT_PRESET.brand,
        val manufacturer: String = DEFAULT_PRESET.manufacturer,
        val model: String = DEFAULT_PRESET.model,
        val device: String = DEFAULT_PRESET.device,
        val product: String = DEFAULT_PRESET.product,
        val board: String = DEFAULT_PRESET.board,
        val hardware: String = DEFAULT_PRESET.hardware,
        val display: String = DEFAULT_PRESET.display,
        val host: String = DEFAULT_PRESET.host,
        val id: String = DEFAULT_PRESET.buildId,
        val tags: String = DEFAULT_PRESET.tags,
        val type: String = DEFAULT_PRESET.type,
        val user: String = DEFAULT_PRESET.user,
        val fingerprint: String = DEFAULT_PRESET.fingerprint,
        val time: Long = DEFAULT_PRESET.time
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put(KEY_ENABLED, enabled)
                .put(KEY_PRESET_ID, presetId)
                .put(KEY_BRAND, brand)
                .put(KEY_MANUFACTURER, manufacturer)
                .put(KEY_MODEL, model)
                .put(KEY_DEVICE, device)
                .put(KEY_PRODUCT, product)
                .put(KEY_BOARD, board)
                .put(KEY_HARDWARE, hardware)
                .put(KEY_DISPLAY, display)
                .put(KEY_HOST, host)
                .put(KEY_ID, id)
                .put(KEY_TAGS, tags)
                .put(KEY_TYPE, type)
                .put(KEY_USER, user)
                .put(KEY_FINGERPRINT, fingerprint)
                .put(KEY_TIME, time)
        }
    }

    fun loadConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            presetId = prefs.getString(KEY_PRESET_ID, DEFAULT_PRESET.id) ?: DEFAULT_PRESET.id,
            brand = prefs.getString(KEY_BRAND, null) ?: DEFAULT_PRESET.brand,
            manufacturer = prefs.getString(KEY_MANUFACTURER, null) ?: DEFAULT_PRESET.manufacturer,
            model = prefs.getString(KEY_MODEL, null) ?: DEFAULT_PRESET.model,
            device = prefs.getString(KEY_DEVICE, null) ?: DEFAULT_PRESET.device,
            product = prefs.getString(KEY_PRODUCT, null) ?: DEFAULT_PRESET.product,
            board = prefs.getString(KEY_BOARD, null) ?: DEFAULT_PRESET.board,
            hardware = prefs.getString(KEY_HARDWARE, null) ?: DEFAULT_PRESET.hardware,
            display = prefs.getString(KEY_DISPLAY, null) ?: DEFAULT_PRESET.display,
            host = prefs.getString(KEY_HOST, null) ?: DEFAULT_PRESET.host,
            id = prefs.getString(KEY_ID, null) ?: DEFAULT_PRESET.buildId,
            tags = prefs.getString(KEY_TAGS, null) ?: DEFAULT_PRESET.tags,
            type = prefs.getString(KEY_TYPE, null) ?: DEFAULT_PRESET.type,
            user = prefs.getString(KEY_USER, null) ?: DEFAULT_PRESET.user,
            fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: DEFAULT_PRESET.fingerprint,
            time = prefs.getLong(KEY_TIME, DEFAULT_PRESET.time)
        )
    }

    fun saveConfig(context: Context, config: Config) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putLong(KEY_LAST_MODIFIED, System.currentTimeMillis())
            .putString(KEY_PRESET_ID, config.presetId)
            .putString(KEY_BRAND, config.brand)
            .putString(KEY_MANUFACTURER, config.manufacturer)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_DEVICE, config.device)
            .putString(KEY_PRODUCT, config.product)
            .putString(KEY_BOARD, config.board)
            .putString(KEY_HARDWARE, config.hardware)
            .putString(KEY_DISPLAY, config.display)
            .putString(KEY_HOST, config.host)
            .putString(KEY_ID, config.id)
            .putString(KEY_TAGS, config.tags)
            .putString(KEY_TYPE, config.type)
            .putString(KEY_USER, config.user)
            .putString(KEY_FINGERPRINT, config.fingerprint)
            .putLong(KEY_TIME, config.time)
            .commit()
        check(saved) { "Unable to persist device info config" }
        SubmoduleConfigFiles.write(context, deviceConfig = config)
    }

    fun lastModified(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MODIFIED, 0L)

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return DeviceInfoConfigSchema(
            enabled = config.enabled,
            presetId = config.presetId,
            brand = config.brand,
            manufacturer = config.manufacturer,
            model = config.model,
            device = config.device,
            product = config.product,
            board = config.board,
            hardware = config.hardware,
            display = config.display,
            host = config.host,
            id = config.id,
            tags = config.tags,
            type = config.type,
            user = config.user,
            fingerprint = config.fingerprint,
            time = config.time,
            lastModified = lastModified(context)
        ).toJson()
    }

    fun importSchema(context: Context, schema: DeviceInfoConfigSchema) {
        require(schema.schemaVersion in 1..DeviceInfoConfigSchema.SCHEMA_VERSION) {
            "Unsupported device info config schema version: ${schema.schemaVersion}"
        }
        val errors = validateSchema(schema)
        require(errors.isEmpty()) { errors.joinToString() }
        saveConfig(
            context,
            Config(
                enabled = schema.enabled,
                presetId = schema.presetId,
                brand = schema.brand,
                manufacturer = schema.manufacturer,
                model = schema.model,
                device = schema.device,
                product = schema.product,
                board = schema.board,
                hardware = schema.hardware,
                display = schema.display,
                host = schema.host,
                id = schema.id,
                tags = schema.tags,
                type = schema.type,
                user = schema.user,
                fingerprint = schema.fingerprint,
                time = schema.time
            )
        )
    }

    fun validateSchema(schema: DeviceInfoConfigSchema): List<String> = buildList {
        validateText("presetId", schema.presetId, MAX_SHORT_TEXT_LENGTH)
        validateText("brand", schema.brand, MAX_SHORT_TEXT_LENGTH)
        validateText("manufacturer", schema.manufacturer, MAX_SHORT_TEXT_LENGTH)
        validateText("model", schema.model, MAX_SHORT_TEXT_LENGTH)
        validateText("device", schema.device, MAX_SHORT_TEXT_LENGTH)
        validateText("product", schema.product, MAX_SHORT_TEXT_LENGTH)
        validateText("board", schema.board, MAX_SHORT_TEXT_LENGTH)
        validateText("hardware", schema.hardware, MAX_SHORT_TEXT_LENGTH)
        validateText("host", schema.host, MAX_SHORT_TEXT_LENGTH)
        validateText("id", schema.id, MAX_SHORT_TEXT_LENGTH)
        validateText("tags", schema.tags, MAX_SHORT_TEXT_LENGTH)
        validateText("type", schema.type, MAX_SHORT_TEXT_LENGTH)
        validateText("user", schema.user, MAX_SHORT_TEXT_LENGTH)
        validateText("display", schema.display, MAX_LONG_TEXT_LENGTH)
        validateText("fingerprint", schema.fingerprint, MAX_LONG_TEXT_LENGTH)
        if (schema.time < 0L) add("time must not be negative")
    }

    private fun MutableList<String>.validateText(name: String, value: String, maxLength: Int) {
        if (value.length > maxLength) add("$name exceeds $maxLength characters")
        if (value.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            add("$name contains unsupported control characters")
        }
    }

    private const val MAX_SHORT_TEXT_LENGTH = 256
    private const val MAX_LONG_TEXT_LENGTH = 2_048
}
