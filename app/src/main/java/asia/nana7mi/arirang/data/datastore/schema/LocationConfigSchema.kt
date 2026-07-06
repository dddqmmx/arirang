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

        fun fromJson(json: String): LocationConfigSchema {
            val root = JSON_PARSER.parse(json).asJsonObject
            val perPackage = mutableMapOf<String, LocationProfileSchema>()
            root.get("perPackage")?.asJsonObject?.entrySet()?.forEach { (pkg, value) ->
                val profileJson = value.asJsonObject
                perPackage[pkg] = LocationProfileSchema(
                    enabled = profileJson.get("enabled")?.asBoolean ?: false,
                    latitude = profileJson.get("latitude")?.asDouble ?: 0.0,
                    longitude = profileJson.get("longitude")?.asDouble ?: 0.0,
                    altitude = profileJson.get("altitude")?.asDouble ?: 0.0,
                    accuracy = profileJson.get("accuracy")?.asFloat ?: 5.0f,
                    speed = profileJson.get("speed")?.asFloat ?: 0.0f,
                    bearing = profileJson.get("bearing")?.asFloat ?: 0.0f,
                    satellites = profileJson.get("satellites")?.asInt ?: 12
                )
            }
            return LocationConfigSchema(
                enabled = root.get("enabled")?.asBoolean ?: false,
                latitude = root.get("latitude")?.asDouble ?: 39.019444,
                longitude = root.get("longitude")?.asDouble ?: 125.738052,
                altitude = root.get("altitude")?.asDouble ?: 27.0,
                accuracy = root.get("accuracy")?.asFloat ?: 5.0f,
                speed = root.get("speed")?.asFloat ?: 0.0f,
                bearing = root.get("bearing")?.asFloat ?: 0.0f,
                satellites = root.get("satellites")?.asInt ?: 12,
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
    @SerializedName("latitude") val latitude: Double = 39.0,
    @SerializedName("longitude") val longitude: Double = 125.0,
    @SerializedName("altitude") val altitude: Double = 27.0,
    @SerializedName("accuracy") val accuracy: Float = 5.0f,
    @SerializedName("speed") val speed: Float = 0.0f,
    @SerializedName("bearing") val bearing: Float = 0.0f,
    @SerializedName("satellites") val satellites: Int = 12
)
