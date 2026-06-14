package asia.nana7mi.arirang.data.datastore

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Date
import java.util.Locale

object UniqueIdentifierPrefs {
    const val PREFS_NAME = "unique_identifier_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MODIFIED = "last_modified"
    private const val KEY_ANDROID_ID = "android_id"
    private const val KEY_GAID = "gaid"
    private const val KEY_WIDEVINE_DRM_ID = "widevine_drm_id"
    private const val KEY_APP_SET_ID = "app_set_id"
    private const val KEY_SERIAL = "serial"
    private const val KEY_IMEI_BY_SLOT = "imei_by_slot"
    private const val KEY_TAC_BY_SLOT = "tac_by_slot"

    private val gson = Gson()
    private val random = SecureRandom()

    data class Config(
        val enabled: Boolean = false,
        val androidId: String = "",
        val gaid: String = "",
        val widevineDrmId: String = "",
        val appSetId: String = "",
        val serial: String = "",
        val imeiBySlot: Map<Int, String> = emptyMap(),
        val tacBySlot: Map<Int, String> = emptyMap()
    ) {
        fun imeiList(): List<Pair<Int, String>> {
            return imeiBySlot.toSortedMap().toList()
        }

        fun tacForSlot(slotIndex: Int, imei: String): String {
            return tacBySlot[slotIndex]?.takeIf { it.isNotBlank() } ?: imei.take(8)
        }
    }

    fun loadConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            androidId = prefs.getString(KEY_ANDROID_ID, null) ?: defaultAndroidId(context),
            gaid = prefs.getString(KEY_GAID, null) ?: randomGaid(),
            widevineDrmId = prefs.getString(KEY_WIDEVINE_DRM_ID, null) ?: randomWidevineDrmId(),
            appSetId = prefs.getString(KEY_APP_SET_ID, null) ?: randomAppSetId(),
            serial = prefs.getString(KEY_SERIAL, null) ?: defaultSerial(),
            imeiBySlot = loadImeiBySlot(prefs.getString(KEY_IMEI_BY_SLOT, null)),
            tacBySlot = loadSlotStringMap(prefs.getString(KEY_TAC_BY_SLOT, null))
        )
    }

    fun saveConfig(context: Context, config: Config) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_LAST_MODIFIED, Date().time)
            putString(KEY_ANDROID_ID, config.androidId)
            putString(KEY_GAID, config.gaid)
            putString(KEY_WIDEVINE_DRM_ID, config.widevineDrmId)
            putString(KEY_APP_SET_ID, config.appSetId)
            putString(KEY_SERIAL, config.serial)
            putString(KEY_IMEI_BY_SLOT, gson.toJson(config.imeiBySlot.toSortedMap()))
            putString(KEY_TAC_BY_SLOT, gson.toJson(config.tacBySlot.toSortedMap()))
        }
        SubmoduleConfigFiles.write(context, uniqueIdentifierConfig = config)
    }

    fun lastModified(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun configuredSlotCount(context: Context): Int {
        return loadConfig(context).imeiBySlot.size
    }

    fun buildHookSnapshot(context: Context): String {
        val config = loadConfig(context)
        return JSONObject()
            .put(KEY_ENABLED, config.enabled.toString())
            .put(KEY_LAST_MODIFIED, lastModified(context).toString())
            .put(KEY_ANDROID_ID, config.androidId)
            .put(KEY_GAID, config.gaid)
            .put(KEY_WIDEVINE_DRM_ID, config.widevineDrmId)
            .put(KEY_APP_SET_ID, config.appSetId)
            .put(KEY_SERIAL, config.serial)
            .put(KEY_IMEI_BY_SLOT, gson.toJson(config.imeiBySlot.toSortedMap()))
            .put(KEY_TAC_BY_SLOT, gson.toJson(config.tacBySlot.toSortedMap()))
            .toString()
    }

    fun defaultImeiForSlot(slotIndex: Int): String {
        return randomImeiForSlot(slotIndex)
    }

    fun randomAndroidId(): String {
        return randomHex(16)
    }

    fun randomGaid(): String {
        return randomUuidLike()
    }

    fun randomWidevineDrmId(): String {
        return randomHex(32)
    }

    fun randomAppSetId(): String {
        return randomUuidLike()
    }

    fun randomSerial(): String {
        return randomHex(12).uppercase(Locale.US)
    }

    fun randomImeiForSlot(slotIndex: Int): String {
        return randomImeiForSlot(slotIndex, randomTac())
    }

    fun randomImeiForSlot(slotIndex: Int, tac: String): String {
        val normalizedTac = tac.filter(Char::isDigit).padEnd(8, '0').take(8)
        val serialBase = random.nextInt(900000) + ((slotIndex + 1) * 1000)
        val serial = serialBase.toString().padStart(6, '0').takeLast(6)
        val body = normalizedTac + serial
        return body + luhnCheckDigit(body)
    }

    fun randomTac(): String {
        return randomDigits(8)
    }

    private fun defaultAndroidId(context: Context): String {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().takeUnless { it.isNullOrBlank() } ?: randomAndroidId()
    }

    private fun defaultSerial(): String {
        return runCatching { Build.getSerial() }.getOrNull()
            .takeUnless { it.isNullOrBlank() || it == Build.UNKNOWN }
            ?: randomSerial()
    }

    private fun loadImeiBySlot(json: String?): Map<Int, String> {
        val map = loadSlotStringMap(json)
        if (map.isEmpty()) {
            return mapOf(
                0 to defaultImeiForSlot(0),
                1 to defaultImeiForSlot(1)
            )
        }
        return map
    }

    private fun loadSlotStringMap(json: String?): Map<Int, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val type = object : TypeToken<Map<Int, String>>() {}.type
        return runCatching { gson.fromJson<Map<Int, String>>(json, type) }
            .getOrNull()
            .orEmpty()
            .filterValues { it.isNotBlank() }
            .toSortedMap()
    }

    private fun randomUuidLike(): String {
        return "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"
    }

    private fun randomHex(length: Int): String {
        val chars = CharArray(length)
        repeat(length) { index ->
            chars[index] = "0123456789abcdef"[random.nextInt(16)]
        }
        return String(chars)
    }

    private fun randomDigits(length: Int): String {
        val chars = CharArray(length)
        repeat(length) { index ->
            chars[index] = "0123456789"[random.nextInt(10)]
        }
        return String(chars)
    }

    private fun luhnCheckDigit(body: String): Int {
        val sum = body.reversed().mapIndexed { index, char ->
            val digit = char.digitToIntOrNull() ?: 0
            if (index % 2 == 0) {
                val doubled = digit * 2
                if (doubled > 9) doubled - 9 else doubled
            } else {
                digit
            }
        }.sum()
        return (10 - (sum % 10)) % 10
    }
}
