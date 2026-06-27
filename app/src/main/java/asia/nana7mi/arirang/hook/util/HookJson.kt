package asia.nana7mi.arirang.hook.util

import org.json.JSONObject

internal fun Boolean?.orFalse(): Boolean = this ?: false

internal fun JSONObject.optCleanString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getInt(name) }.getOrNull()
        ?: optString(name).toIntOrNull()
}

internal fun JSONObject.optBooleanOrNull(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getBoolean(name) }.getOrNull()
        ?: optString(name).toBooleanStrictOrNull()
}
