package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

abstract class ConfigSchema {

    abstract val schemaVersion: Int
    abstract val lastModified: Long

    abstract fun toJson(): String

    protected fun baseJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("schemaVersion", schemaVersion)
        obj.addProperty("lastModified", lastModified)
        return obj
    }

    companion object {
        val GSON: Gson = GsonBuilder().setLenient().create()
        val JSON_PARSER = JsonParser()

        inline fun <reified T : ConfigSchema> fromJson(json: String, builder: (JsonObject) -> T): T {
            val root = JSON_PARSER.parse(json).asJsonObject
            return builder(root)
        }

        fun readSchemaVersion(json: String): Int {
            return try {
                JSON_PARSER.parse(json).asJsonObject.get("schemaVersion")?.asInt ?: 0
            } catch (_: Exception) {
                0
            }
        }

        fun readLastModified(json: String): Long {
            return try {
                JSON_PARSER.parse(json).asJsonObject.get("lastModified")?.asLong ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
