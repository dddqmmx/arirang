package asia.nana7mi.arirang.hook

import android.util.Xml
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class HookConfigFile<T>(
    val configName: String,
    val prefsName: String,
    private val defaultValue: T,
    refreshIntervalMs: Long,
    private val readRealtimeSnapshot: (force: Boolean) -> String?,
    private val parseRealtimeSnapshot: (String) -> T?,
    private val readStoredConfig: (XSharedPreferences) -> T?
) {
    private val realtimeConfig = RealtimeHookConfig(
        defaultValue = defaultValue,
        refreshIntervalMs = refreshIntervalMs,
        readSnapshot = readRealtimeSnapshot,
        parseSnapshot = parseRealtimeSnapshot,
        readFallback = {
            readStoredConfig(readXSharedPreferences()) ?: defaultValue
        }
    )

    fun current(force: Boolean = false): T {
        return realtimeConfig.current(force)
    }

    fun readXSharedPreferences(): XSharedPreferences {
        return xSharedPreferences(prefsName)
    }

    fun readStored(): T {
        return readStoredConfig(readXSharedPreferences()) ?: defaultValue
    }

    fun invalidate() {
        realtimeConfig.invalidate()
    }

    companion object {
        fun xSharedPreferences(prefsName: String): XSharedPreferences {
            return XSharedPreferences(BuildConfig.APPLICATION_ID, prefsName).apply {
                makeWorldReadable()
                reload()
            }
        }

        fun readSharedPrefsValues(
            prefsName: String,
            logModule: HookLog.Module,
            logName: String = prefsName
        ): Map<String, String>? {
            val prefsFile = sharedPrefsFiles(prefsName).firstOrNull { it.isFile && it.canRead() }
                ?: return null

            return runCatching {
                val values = linkedMapOf<String, String>()
                FileInputStream(prefsFile).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, Charsets.UTF_8.name())

                    var event = parser.eventType
                    while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                            val tagName = parser.name
                            val key = parser.getAttributeValue(null, "name")
                            if (!key.isNullOrBlank()) {
                                when (tagName) {
                                    "boolean", "int", "long", "float" -> {
                                        parser.getAttributeValue(null, "value")?.let { values[key] = it }
                                    }
                                    "string" -> values[key] = parser.nextText()
                                    "set" -> values[key] = readStringSet(parser).joinToString("\n")
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
                values
            }.onFailure {
                HookLog.w(logModule, "failed to read $logName config: ${it.message}")
            }.getOrNull()
        }

        fun readSnapshotValues(
            snapshot: String,
            logModule: HookLog.Module,
            logName: String
        ): Map<String, String> {
            return runCatching {
                val root = JSONObject(snapshot)
                buildMap {
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!root.isNull(key)) {
                            put(key, root.optString(key))
                        }
                    }
                }
            }.onFailure {
                HookLog.w(logModule, "failed to parse $logName config snapshot: ${it.message}")
            }.getOrDefault(emptyMap())
        }

        private fun sharedPrefsFiles(prefsName: String): List<File> {
            return listOf(
                File("/data/user/0/${BuildConfig.APPLICATION_ID}/shared_prefs/$prefsName.xml"),
                File("/data/data/${BuildConfig.APPLICATION_ID}/shared_prefs/$prefsName.xml")
            )
        }

        private fun readStringSet(parser: org.xmlpull.v1.XmlPullParser): List<String> {
            val values = mutableListOf<String>()
            var event = parser.next()
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string") {
                    values += parser.nextText()
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "set") {
                    break
                }
                event = parser.next()
            }
            return values
        }
    }
}
