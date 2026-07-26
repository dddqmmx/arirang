package asia.nana7mi.arirang.data.datastore.schema

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class LocationConfigSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("latitude") val latitude: Double = 39.019444,
    @SerializedName("longitude") val longitude: Double = 125.738052,
    @SerializedName("altitude") val altitude: Double = 27.0,
    @SerializedName("accuracy") val accuracy: Float = 5.0f,
    @SerializedName("speed") val speed: Float = 0.0f,
    @SerializedName("bearing") val bearing: Float = 0.0f,
    @SerializedName("satellites") val satellites: Int = 12,
    @SerializedName("perPackage") val perPackage: Map<String, LocationProfileSchema> = emptyMap(),
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val lastModified: Long = 0L
) : ConfigSchema() {

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Declared defaults, used as the decode fallback for absent fields, so
         * the two cannot drift apart. schemaVersion/lastModified are excluded
         * deliberately: an absent schemaVersion must stay 0 ("unversioned") so
         * ManagedConfig still rejects legacy payloads.
         */
        private val DEFAULTS = LocationConfigSchema()
        private val PROFILE_DEFAULTS = LocationProfileSchema()

        fun fromJson(json: String): LocationConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val perPackage = mutableMapOf<String, LocationProfileSchema>()
            root.get("perPackage")?.asJsonObject?.entrySet()?.forEach { (pkg, value) ->
                val profileJson = value.asJsonObject
                perPackage[pkg] = LocationProfileSchema(
                    enabled = profileJson.get("enabled")?.asBoolean ?: PROFILE_DEFAULTS.enabled,
                    latitude = profileJson.get("latitude")?.asDouble ?: PROFILE_DEFAULTS.latitude,
                    longitude = profileJson.get("longitude")?.asDouble ?: PROFILE_DEFAULTS.longitude,
                    altitude = profileJson.get("altitude")?.asDouble ?: PROFILE_DEFAULTS.altitude,
                    accuracy = profileJson.get("accuracy")?.asFloat ?: PROFILE_DEFAULTS.accuracy,
                    speed = profileJson.get("speed")?.asFloat ?: PROFILE_DEFAULTS.speed,
                    bearing = profileJson.get("bearing")?.asFloat ?: PROFILE_DEFAULTS.bearing,
                    satellites = profileJson.get("satellites")?.asInt ?: PROFILE_DEFAULTS.satellites
                )
            }
            return LocationConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: DEFAULTS.enabled,
                latitude = root.get("latitude")?.asDouble ?: DEFAULTS.latitude,
                longitude = root.get("longitude")?.asDouble ?: DEFAULTS.longitude,
                altitude = root.get("altitude")?.asDouble ?: DEFAULTS.altitude,
                accuracy = root.get("accuracy")?.asFloat ?: DEFAULTS.accuracy,
                speed = root.get("speed")?.asFloat ?: DEFAULTS.speed,
                bearing = root.get("bearing")?.asFloat ?: DEFAULTS.bearing,
                satellites = root.get("satellites")?.asInt ?: DEFAULTS.satellites,
                perPackage = perPackage,
                schemaVersion = root.get("schemaVersion")?.asInt ?: 0,
                lastModified = root.get("lastModified")?.asLong ?: 0L
            )
        }
    }

    override fun toJson(): String {
        val obj = baseJson()
        obj.addProperty("enabled", enabled)
        obj.addProperty("latitude", latitude)
        obj.addProperty("longitude", longitude)
        obj.addProperty("altitude", altitude)
        obj.addProperty("accuracy", accuracy)
        obj.addProperty("speed", speed)
        obj.addProperty("bearing", bearing)
        obj.addProperty("satellites", satellites)
        val perPkgObj = JsonObject()
        for ((pkg, profile) in perPackage) {
            val pObj = JsonObject()
            pObj.addProperty("enabled", profile.enabled)
            pObj.addProperty("latitude", profile.latitude)
            pObj.addProperty("longitude", profile.longitude)
            pObj.addProperty("altitude", profile.altitude)
            pObj.addProperty("accuracy", profile.accuracy)
            pObj.addProperty("speed", profile.speed)
            pObj.addProperty("bearing", profile.bearing)
            pObj.addProperty("satellites", profile.satellites)
            perPkgObj.add(pkg, pObj)
        }
        obj.add("perPackage", perPkgObj)
        return GSON.toJson(obj)
    }
}

data class LocationProfileSchema(
    @SerializedName("enabled") val enabled: Boolean = false,
    // Must match LocationConfigSchema's own defaults (and LocationConfigPrefs.
    // DEFAULT_LATITUDE/LONGITUDE); these were rounded to 39.0/125.0 while the
    // decoder below fell back to 0.0, i.e. a per-package profile with no
    // coordinates resolved to Null Island rather than the configured default.
    @SerializedName("latitude") val latitude: Double = 39.019444,
    @SerializedName("longitude") val longitude: Double = 125.738052,
    @SerializedName("altitude") val altitude: Double = 27.0,
    @SerializedName("accuracy") val accuracy: Float = 5.0f,
    @SerializedName("speed") val speed: Float = 0.0f,
    @SerializedName("bearing") val bearing: Float = 0.0f,
    @SerializedName("satellites") val satellites: Int = 12
)
