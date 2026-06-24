package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocationChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_location_title
    override val navChipId: Int = R.id.navLocationChip

    @SuppressLint("MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_location_permission_hint)
            )
        }

        try {
            val locationManager = context.getSystemService(LocationManager::class.java)
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val values = mutableListOf<String>()

            val nativeCache = runCatching {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()
            values.add(formatLocationProbe(context, context.getString(R.string.self_check_location_native_cache), nativeCache))

            val nativeRealtime = awaitNativeCurrentLocation(context, locationManager)
            values.add(formatLocationProbe(context, context.getString(R.string.self_check_location_native_realtime), nativeRealtime))

            val fusedCache = runCatching {
                Tasks.await(fusedLocationClient.lastLocation, 1500L, TimeUnit.MILLISECONDS)
            }.getOrNull()
            values.add(formatLocationProbe(context, context.getString(R.string.self_check_location_fused_cache), fusedCache))

            val tokenSource = CancellationTokenSource()
            val fusedRealtime = runCatching {
                Tasks.await(
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token),
                    2500L,
                    TimeUnit.MILLISECONDS
                )
            }.getOrNull()
            values.add(formatLocationProbe(context, context.getString(R.string.self_check_location_fused_realtime), fusedRealtime))

            val hasLocation = values.any { !it.contains(context.getString(R.string.self_check_location_no_data)) }
            Log.d(CheckDefinitions.PHONE_DIAG_TAG, "Location check: \n" + values.joinToString("\n---\n"))
            CheckResult(
                if (hasLocation) CheckState.VISIBLE else CheckState.BLOCKED,
                if (hasLocation) context.getString(R.string.self_check_status_visible) else context.getString(R.string.self_check_status_not_visible),
                values.joinToString("\n\n")
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }

    @SuppressLint("MissingPermission")
    private fun awaitNativeCurrentLocation(context: Context, locationManager: LocationManager): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null
        runCatching {
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                null,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                result = location
                latch.countDown()
            }
            latch.await(2500L, TimeUnit.MILLISECONDS)
        }
        return result
    }

    private fun formatLocationProbe(context: Context, label: String, location: Location?): String {
        if (location == null) {
            return "$label\n${context.getString(R.string.self_check_location_no_data)}"
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(location.time))
        return listOf(
            label,
            context.getString(R.string.self_check_location_coordinates, location.latitude, location.longitude),
            context.getString(R.string.self_check_location_accuracy_time, location.accuracy, time),
            "Provider: ${location.provider ?: context.getString(R.string.self_check_unknown_name)}"
        ).joinToString("\n")
    }
}
