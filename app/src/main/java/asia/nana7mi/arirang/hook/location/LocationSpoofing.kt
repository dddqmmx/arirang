package asia.nana7mi.arirang.hook.location

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import java.util.Locale
import kotlin.math.abs

private const val MOCK_VERTICAL_ACCURACY = 2.0f
private const val MOCK_SPEED_ACCURACY = 0.1f
private const val MOCK_BEARING_ACCURACY = 0.5f
private const val MOCK_HDOP = 0.9f

internal fun Location.spoofed(profile: LocationProfile): Location {
    return fakeLocation(profile, provider ?: LocationManager.GPS_PROVIDER, this)
}

internal fun Location.spoofInPlace(profile: LocationProfile) {
    applyProfile(profile, provider ?: LocationManager.GPS_PROVIDER)
}

internal fun fakeLocation(
    profile: LocationProfile,
    provider: String? = LocationManager.GPS_PROVIDER,
    source: Location? = null
): Location {
    val location = source?.let(::Location) ?: Location(provider ?: LocationManager.GPS_PROVIDER)
    location.applyProfile(profile, provider ?: LocationManager.GPS_PROVIDER)
    return location
}

internal fun rewriteLocationResult(locationResult: Any, profile: LocationProfile): Boolean {
    runCatching { XposedHelpers.callMethod(locationResult, "getLocations") }
        .getOrNull()
        ?.takeIf { it.containsLocation() }
        ?.let {
            rewriteLocationContainer(it, profile)
            return true
        }

    var changed = false
    locationResult.javaClass.declaredFields.forEach { field ->
        runCatching {
            field.isAccessible = true
            val value = field.get(locationResult)
            if (value.containsLocation()) {
                rewriteLocationContainer(value, profile)
                changed = true
            }
        }
    }
    return changed
}

internal fun rewriteLocationContainer(value: Any?, profile: LocationProfile): Any? {
    if (value == null) return null
    return when (value) {
        is Location -> {
            value.spoofInPlace(profile)
            value
        }
        is Array<*> -> {
            value.forEach { item ->
                if (item is Location) item.spoofInPlace(profile)
            }
            value
        }
        is List<*> -> {
            value.forEach { item ->
                if (item is Location) item.spoofInPlace(profile)
            }
            value
        }
        else -> {
            if (value.javaClass.name.endsWith(".LocationResult")) {
                rewriteLocationResult(value, profile)
            }
            value
        }
    }
}

internal fun spoofNmea(nmea: String, profile: LocationProfile): String {
    val header = nmea.substringBefore(',').uppercase()
    if (!header.endsWith("GGA") && !header.endsWith("RMC")) return nmea

    val originalParts = nmea.substringBefore('*').split(',').toMutableList()
    if (originalParts.size < 7) return nmea

    val lat = latitudeToNmea(profile.latitude)
    val lon = longitudeToNmea(profile.longitude)
    originalParts[2] = lat.first
    originalParts[3] = lat.second
    originalParts[4] = lon.first
    originalParts[5] = lon.second

    if (header.endsWith("GGA") && originalParts.size > 9) {
        originalParts[6] = "1"
        originalParts[7] = profile.satellites.toString().padStart(2, '0')
        originalParts[8] = "%.1f".format(Locale.US, MOCK_HDOP)
        originalParts[9] = "%.1f".format(Locale.US, profile.altitude)
    }

    if (header.endsWith("RMC") && originalParts.size > 8) {
        originalParts[2] = "A"
        originalParts[7] = "0.0"
        originalParts[8] = "0.0"
    }

    return withChecksum(originalParts.joinToString(","))
}

private fun Location.applyProfile(profile: LocationProfile, providerName: String) {
    provider = providerName
    latitude = profile.latitude
    longitude = profile.longitude
    altitude = profile.altitude
    speed = profile.speed
    bearing = profile.bearing
    accuracy = profile.accuracy
    time = System.currentTimeMillis()
    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

    verticalAccuracyMeters = MOCK_VERTICAL_ACCURACY
    speedAccuracyMetersPerSecond = MOCK_SPEED_ACCURACY
    bearingAccuracyDegrees = MOCK_BEARING_ACCURACY

    runCatching {
        XposedHelpers.callMethod(this, "setIsFromMockProvider", false)
    }

    extras = Bundle(extras ?: Bundle()).apply {
        listOf("mockLocation", "is_mock", "portal.enable").forEach(::remove)
        putBoolean("mockLocation", false)
        putInt("satellites", profile.satellites)
        putInt("visible_satellites", (profile.satellites + 4).coerceAtLeast(profile.satellites))
        putFloat("hdop", MOCK_HDOP)
        putDouble("altitude", profile.altitude)
    }
}

private fun Any?.containsLocation(): Boolean = when (this) {
    is List<*> -> any { it is Location }
    is Array<*> -> any { it is Location }
    else -> false
}

private fun latitudeToNmea(latitude: Double): Pair<String, String> {
    val hemisphere = if (latitude >= 0) "N" else "S"
    val absoluteValue = abs(latitude)
    val degrees = absoluteValue.toInt()
    val minutes = (absoluteValue - degrees) * 60.0
    return "%02d%07.4f".format(Locale.US, degrees, minutes) to hemisphere
}

private fun longitudeToNmea(longitude: Double): Pair<String, String> {
    val hemisphere = if (longitude >= 0) "E" else "W"
    val absoluteValue = abs(longitude)
    val degrees = absoluteValue.toInt()
    val minutes = (absoluteValue - degrees) * 60.0
    return "%03d%07.4f".format(Locale.US, degrees, minutes) to hemisphere
}

private fun withChecksum(sentence: String): String {
    val body = sentence.removePrefix("$")
    val checksum = body.fold(0) { acc, char -> acc xor char.code }
    return "$$body*%02X".format(Locale.US, checksum)
}
