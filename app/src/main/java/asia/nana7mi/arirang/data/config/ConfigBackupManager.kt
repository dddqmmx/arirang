package asia.nana7mi.arirang.data.config

import asia.nana7mi.arirang.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class ConfigBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

data class ConfigBackupImportResult(val importedConfigIds: List<String>)

/**
 * Versioned, bounded backup transport for [ConfigRegistry].
 *
 * Import never trusts ZIP paths or metadata: all entries are read into bounded memory, the
 * manifest and every payload are verified, and only then are configuration stores changed.
 */
object ConfigBackupManager {
    private const val FORMAT = "arirang-config-backup"
    private const val FORMAT_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val MAX_ENTRY_COUNT = 32
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private const val MAX_CONFIG_BYTES = 512 * 1024
    private const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
    private const val MAX_JSON_DEPTH = 64
    private val SAFE_ENTRY_NAME = Regex("[a-z0-9_./-]+")
    private val SAFE_CONFIG_ID = Regex("[a-z0-9_]+")
    private val SHA_256 = Regex("[0-9a-f]{64}")

    @Synchronized
    fun export(context: android.content.Context, output: OutputStream) {
        val snapshots = ConfigRegistry.all.map { config ->
            val managedSnapshot = config.read(context)
            val snapshot = managedSnapshot.payload
            val bytes = snapshot.toByteArray(Charsets.UTF_8)
            requireBackup(bytes.size <= MAX_CONFIG_BYTES) {
                "Config '${config.id}' exceeds $MAX_CONFIG_BYTES bytes"
            }
            val snapshotRoot = parseObject(bytes, "config '${config.id}'")
            ExportedConfig(
                id = config.id,
                schemaVersion = snapshotRoot.optInt("schemaVersion", config.currentSchemaVersion),
                lastModified = managedSnapshot.version,
                path = configPath(config.id),
                bytes = bytes,
                sha256 = sha256(bytes)
            )
        }
        requireBackup(snapshots.sumOf { it.bytes.size } <= MAX_TOTAL_BYTES) {
            "Backup payload exceeds $MAX_TOTAL_BYTES bytes"
        }

        val configManifest = JSONArray()
        snapshots.forEach { item ->
            configManifest.put(
                JSONObject()
                    .put("id", item.id)
                    .put("path", item.path)
                    .put("schemaVersion", item.schemaVersion)
                    .put("lastModified", item.lastModified)
                    .put("size", item.bytes.size)
                    .put("sha256", item.sha256)
            )
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("configs", configManifest)
            .toString()
            .toByteArray(Charsets.UTF_8)
        requireBackup(manifest.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }

        ZipOutputStream(output).use { zip ->
            writeEntry(zip, MANIFEST_ENTRY, manifest)
            snapshots.forEach { writeEntry(zip, it.path, it.bytes) }
            zip.finish()
        }
    }

    @Synchronized
    fun import(context: android.content.Context, input: InputStream): ConfigBackupImportResult {
        val entries = readEntries(input)
        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw ConfigBackupException("Missing $MANIFEST_ENTRY")
        requireBackup(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }

        val manifest = parseObject(manifestBytes, "manifest")
        requireBackup(manifest.optString("format") == FORMAT) { "Unsupported backup format" }
        requireBackup(manifest.optInt("formatVersion", -1) == FORMAT_VERSION) {
            "Unsupported backup format version"
        }
        requireBackup(manifest.optString("applicationId") == BuildConfig.APPLICATION_ID) {
            "Backup belongs to a different application"
        }
        val configs = manifest.optJSONArray("configs")
            ?: throw ConfigBackupException("Manifest configs must be an array")
        requireBackup(configs.length() in 1..ConfigRegistry.all.size) {
            "Invalid manifest config count"
        }

        val seenIds = HashSet<String>()
        val seenPaths = HashSet<String>()
        val validated = ArrayList<ValidatedConfig>(configs.length())
        repeat(configs.length()) { index ->
            val item = configs.optJSONObject(index)
                ?: throw ConfigBackupException("Manifest config[$index] must be an object")
            val id = item.optString("id")
            requireBackup(SAFE_CONFIG_ID.matches(id) && seenIds.add(id)) {
                "Invalid or duplicate config id: $id"
            }
            val config = ConfigRegistry.find(id)
                ?: throw ConfigBackupException("Unknown config id: $id")
            val path = item.optString("path")
            requireBackup(path == configPath(id) && seenPaths.add(path)) {
                "Invalid or duplicate config path for '$id'"
            }
            val schemaVersion = item.optInt("schemaVersion", -1)
            requireBackup(schemaVersion in 1..config.currentSchemaVersion) {
                "Unsupported schema version for '$id': $schemaVersion"
            }
            val declaredSize = item.optLong("size", -1L)
            requireBackup(declaredSize in 0..MAX_CONFIG_BYTES.toLong()) {
                "Invalid size for '$id'"
            }
            val expectedHash = item.optString("sha256")
            requireBackup(SHA_256.matches(expectedHash)) { "Invalid SHA-256 for '$id'" }
            val bytes = entries[path] ?: throw ConfigBackupException("Missing config entry: $path")
            requireBackup(bytes.size.toLong() == declaredSize) { "Size mismatch for '$id'" }
            requireBackup(MessageDigest.isEqual(expectedHash.hexBytes(), sha256Bytes(bytes))) {
                "SHA-256 mismatch for '$id'"
            }
            val snapshot = bytes.toString(Charsets.UTF_8)
            val payloadRoot = parseObject(bytes, "config '$id'")
            val payloadVersion = payloadRoot.optInt("schemaVersion", -1)
            requireBackup(payloadVersion == schemaVersion) { "Schema version mismatch for '$id'" }
            requireBackup(payloadRoot.optLong("lastModified", Long.MIN_VALUE) == item.optLong("lastModified", Long.MIN_VALUE)) {
                "Last-modified mismatch for '$id'"
            }
            config.validate(snapshot).requireValid(id)
            validated += ValidatedConfig(config, snapshot)
        }

        val expectedEntries = seenPaths + MANIFEST_ENTRY
        requireBackup(entries.keys == expectedEntries) { "Backup contains undeclared entries" }

        // Capture every previous value before the first write. Rollback uses the same validated
        // import path and runs in reverse application order.
        val previous = validated.associate { it.config.id to it.config.snapshot(context) }
        val attempted = ArrayList<ValidatedConfig>(validated.size)
        try {
            validated.forEach {
                attempted += it
                it.config.import(context, it.snapshot)
            }
        } catch (failure: Throwable) {
            val rollbackFailures = ArrayList<String>()
            attempted.asReversed().forEach { item ->
                runCatching { item.config.import(context, previous.getValue(item.config.id)) }
                    .exceptionOrNull()
                    ?.let { rollbackFailure ->
                        rollbackFailures += item.config.id
                        failure.addSuppressed(rollbackFailure)
                    }
            }
            val rollbackResult = if (rollbackFailures.isEmpty()) {
                "previous config values were reapplied"
            } else {
                "rollback also failed for: ${rollbackFailures.joinToString()}"
            }
            throw ConfigBackupException("Unable to apply backup; $rollbackResult", failure)
        }
        return ConfigBackupImportResult(validated.map { it.config.id })
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0
        ZipInputStream(SizeLimitedInputStream(input, MAX_ARCHIVE_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireBackup(entries.size < MAX_ENTRY_COUNT) { "Backup has too many entries" }
                val name = entry.name
                requireBackup(!entry.isDirectory && isSafeEntryName(name)) { "Unsafe ZIP entry: $name" }
                requireBackup(!entries.containsKey(name)) { "Duplicate ZIP entry: $name" }
                val limit = if (name == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_CONFIG_BYTES
                val bytes = readBounded(zip, limit, name)
                totalBytes += bytes.size
                requireBackup(totalBytes <= MAX_TOTAL_BYTES) { "Backup exceeds $MAX_TOTAL_BYTES bytes" }
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun readBounded(input: InputStream, limit: Int, name: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            requireBackup(total <= limit) { "ZIP entry '$name' exceeds $limit bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= 128 &&
            SAFE_ENTRY_NAME.matches(name) &&
            !name.startsWith('/') &&
            !name.contains("//") &&
            name.split('/').none { it == "." || it == ".." }

    private fun configPath(id: String): String = "configs/$id.json"

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun parseObject(bytes: ByteArray, label: String): JSONObject = try {
        requireJsonDepth(bytes, label)
        JSONObject(bytes.toString(Charsets.UTF_8))
    } catch (error: Exception) {
        throw ConfigBackupException("Malformed $label JSON", error)
    }

    private fun sha256(bytes: ByteArray): String =
        sha256Bytes(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256Bytes(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private inline fun requireBackup(condition: Boolean, message: () -> String) {
        if (!condition) throw ConfigBackupException(message())
    }

    private fun requireJsonDepth(bytes: ByteArray, label: String) {
        var depth = 0
        var inString = false
        var escaped = false
        bytes.toString(Charsets.UTF_8).forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        requireBackup(depth <= MAX_JSON_DEPTH) {
                            "$label JSON exceeds maximum nesting depth"
                        }
                    }
                    '}', ']' -> {
                        depth--
                        requireBackup(depth >= 0) { "Malformed $label JSON nesting" }
                    }
                }
            }
        }
        requireBackup(!inString && depth == 0) { "Malformed $label JSON nesting" }
    }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val limit: Int
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read)
            return read
        }

        private fun account(bytes: Int) {
            count += bytes
            requireBackup(count <= limit) { "Backup archive exceeds $limit bytes" }
        }
    }

    private data class ExportedConfig(
        val id: String,
        val schemaVersion: Int,
        val lastModified: Long,
        val path: String,
        val bytes: ByteArray,
        val sha256: String
    )

    private data class ValidatedConfig(
        val config: ManagedConfig<*>,
        val snapshot: String
    )
}
