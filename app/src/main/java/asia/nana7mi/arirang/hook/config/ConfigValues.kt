package asia.nana7mi.arirang.hook.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * One source's answer, normalised so that **one decoder can read either source**.
 *
 * The two sources spell the same config differently and encode it differently:
 *
 * | | service snapshot | prefs |
 * |---|---|---|
 * | key spelling | `currentSsid` (the wire format) | `current_ssid` (legacy) |
 * | nested array | a JSON array | Gson text in a single string |
 * | string set | a JSON array | `\n`-joined |
 * | numbers | JSON numbers | decimal strings |
 *
 * Rather than have every decoder know both spellings — which is how `parseSnapshot` and
 * `readStored` drifted apart for six configs — the accessors take the spellings as aliases and
 * coerce the encodings. A decoder written against this type reads both sources identically, so
 * its validation exists once.
 *
 * Values are held as strings because that is what both sources can produce losslessly; nested
 * structures stay as their JSON text, which both sources also produce (`JSONObject.optString`
 * stringifies a non-string, and the prefs side stores Gson output).
 */
internal class ConfigValues(private val values: Map<String, String>) {

    /** The first of [keys] that is present and not blank. */
    fun string(vararg keys: String): String? = raw(*keys)?.takeIf { it.isNotBlank() }

    fun boolean(vararg keys: String): Boolean? = raw(*keys)?.trim()?.lowercase()?.let {
        when (it) {
            "true", "1", "y", "yes", "on", "enable", "enabled" -> true
            "false", "0", "n", "no", "off", "disable", "disabled" -> false
            else -> null
        }
    }

    fun int(vararg keys: String): Int? = raw(*keys)?.let { value ->
        value.trim().toIntOrNull() ?: value.trim().toDoubleOrNull()?.toInt()
    }

    fun long(vararg keys: String): Long? = raw(*keys)?.let { value ->
        value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong()
    }

    fun double(vararg keys: String): Double? = raw(*keys)?.let { value ->
        value.trim().toDoubleOrNull()
    }

    fun float(vararg keys: String): Float? = double(*keys)?.toFloat()

    /** The raw JSON text of a nested array or object, identical from either source. */
    fun json(vararg keys: String): String? = raw(*keys)?.takeIf { it.isNotBlank() }

    fun jsonArray(vararg keys: String): JSONArray? =
        json(*keys)?.let { runCatching { JSONArray(it) }.getOrNull() }

    fun jsonObject(vararg keys: String): JSONObject? =
        json(*keys)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Child values, so alias resolution works at every nesting level.
     *
     * The nested field names differ between sources too — `PackageVisibilityPrefs.Template` and
     * `PackageListTemplateSchema` agree on `visiblePackages` but disagree on the value's type —
     * so children are handed back as [ConfigValues] rather than being extracted here.
     */
    fun objects(vararg keys: String): List<ConfigValues> {
        val array = jsonArray(*keys) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { child ->
                ConfigValues(buildMap {
                    val names = child.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        if (!child.isNull(name)) put(name, child.optString(name))
                    }
                })
            }
        }
    }

    /**
     * A string collection, however the source encodes it: a JSON array or a `\n`-joined set.
     */
    fun strings(vararg keys: String): List<String> {
        val value = raw(*keys)?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (value.trimStart().startsWith("[")) {
            val array = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }
        return value.split('\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    }

    fun contains(key: String): Boolean = values.containsKey(key)

    /** A view of every value, for overlay arbitration and for tests. */
    fun asMap(): Map<String, String> = values

    /**
     * The same values with [aliases] renamed to their canonical key.
     *
     * Needed before arbitration combines sources, because sources spell one logical key
     * differently: the log config's `package_list` switch is `packageList` in the snapshot,
     * `enabled_package_list` in prefs and `package_list` in a system property. An overlay that
     * merged those as three unrelated keys would let the lowest-priority source win simply by
     * being spelled differently. Once canonicalised, precedence is the arbitration's to decide.
     */
    fun canonicalised(aliases: Map<String, String>): ConfigValues {
        if (aliases.isEmpty() || values.isEmpty()) return this
        // Deliberately not buildMap: inside its receiver, `values` would resolve to the empty
        // map's own values rather than this instance's.
        val renamed = HashMap<String, String>(values.size)
        values.forEach { (key, value) -> renamed[aliases[key] ?: key] = value }
        return ConfigValues(renamed)
    }

    /** True when the source produced nothing at all, which is how "unavailable" is spelled. */
    fun isEmpty(): Boolean = values.isEmpty()

    /**
     * The source's own freshness stamp, for arbitration between sources.
     *
     * Zero when the origin does not carry one, which compares as "older than anything stamped".
     */
    fun version(): Long = long("lastModified", "last_modified") ?: 0L

    private fun raw(vararg keys: String): String? = keys.firstNotNullOfOrNull { values[it] }

    companion object {
        val EMPTY = ConfigValues(emptyMap())

        /** From an already-flat map, as the prefs readers produce. */
        fun of(values: Map<String, String>): ConfigValues = ConfigValues(values)

        /**
         * From a JSON document, flattened to one level.
         *
         * `JSONObject.optString` is what stringifies a nested array or object into its JSON text;
         * `isNull` is checked first so a JSON null becomes an absent key rather than the literal
         * string `"null"`, which differs between the two `org.json` implementations in play
         * (Android's and the desktop one the unit tests use).
         */
        fun ofJson(json: String): ConfigValues? = runCatching {
            val root = JSONObject(json)
            ConfigValues(buildMap {
                val names = root.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (!root.isNull(name)) put(name, root.optString(name))
                }
            })
        }.getOrNull()

        /** From a `SharedPreferences` view, whose values are scalars and string sets. */
        fun ofPreferences(preferences: Map<String, Any?>): ConfigValues = ConfigValues(buildMap {
            preferences.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    null -> Unit
                    is Set<*> -> put(key, (value as Set<String>).joinToString("\n"))
                    else -> put(key, value.toString())
                }
            }
        })
    }
}
