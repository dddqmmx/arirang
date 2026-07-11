package asia.nana7mi.arirang.data.config

import android.content.Context
import asia.nana7mi.arirang.data.datastore.schema.ConfigSchema
import org.json.JSONObject

data class ConfigValidation(
    val valid: Boolean,
    val errors: List<String> = emptyList()
) {
    fun requireValid(configId: String) {
        if (!valid) throw ConfigValidationException(configId, errors)
    }

    companion object {
        val VALID = ConfigValidation(valid = true)
    }
}

class ConfigValidationException(configId: String, errors: List<String>) :
    IllegalArgumentException("Invalid config '$configId': ${errors.joinToString()}")

data class ManagedConfigSnapshot(val version: Long, val payload: String)

class ManagedConfig<T : ConfigSchema>(
    val id: String,
    val currentSchemaVersion: Int,
    val realtimeAvailable: Boolean = false,
    private val requiredFields: Set<String> = emptySet(),
    private val versionReader: (Context) -> Long,
    private val snapshotReader: (Context) -> String,
    private val decoder: (String) -> T,
    private val importer: (Context, T) -> Unit,
    private val validator: (T) -> List<String> = { emptyList() }
) {
    fun version(context: Context): Long = versionReader(context)

    fun read(context: Context): ManagedConfigSnapshot {
        val snapshot = snapshotReader(context)
        validate(snapshot).requireValid(id)
        val version = JSONObject(snapshot).optLong("lastModified", versionReader(context))
        return ManagedConfigSnapshot(version, snapshot)
    }

    fun snapshot(context: Context): String = read(context).payload

    fun validate(snapshot: String): ConfigValidation {
        if (snapshot.isBlank()) return ConfigValidation(false, listOf("snapshot is empty"))
        if (snapshot.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) {
            return ConfigValidation(false, listOf("snapshot exceeds $MAX_SNAPSHOT_BYTES bytes"))
        }
        return runCatching {
            val root = JSONObject(snapshot)
            val missingFields = requiredFields.filter { field ->
                !root.has(field) || root.isNull(field)
            }
            require(missingFields.isEmpty()) {
                "missing required fields: ${missingFields.sorted().joinToString()}"
            }
            val schemaVersion = root.optInt("schemaVersion", 0)
            require(schemaVersion == currentSchemaVersion) {
                "unsupported schemaVersion=$schemaVersion (expected=$currentSchemaVersion)"
            }
            val decoded = decoder(snapshot)
            val errors = validator(decoded)
            if (errors.isEmpty()) ConfigValidation.VALID else ConfigValidation(false, errors)
        }.getOrElse { ConfigValidation(false, listOf(it.message ?: "malformed JSON")) }
    }

    fun import(context: Context, snapshot: String) {
        validate(snapshot).requireValid(id)
        importer(context, decoder(snapshot))
    }

    private companion object {
        private const val MAX_SNAPSHOT_BYTES = 512 * 1024
    }
}
