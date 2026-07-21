package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
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
import java.util.concurrent.atomic.AtomicReference

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

            val fusedUpdates = awaitFusedLocationUpdates(fusedLocationClient)
            values.add(formatLocationProbe(context, context.getString(R.string.self_check_location_fused_updates), fusedUpdates))


            val nativeUpdates = awaitNativeLocationUpdates(context, locationManager)
            values.add(formatLocationProbe(context, context.getString(R.string.self_check_location_native_updates), nativeUpdates))

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

    @SuppressLint("MissingPermission")
    private fun awaitNativeLocationUpdates(context: Context, locationManager: LocationManager): Location? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)
        val executor = ContextCompat.getMainExecutor(context)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (result.compareAndSet(null, location)) {
                    latch.countDown()
                }
            }
        }
        runCatching {
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.FUSED_PROVIDER
            ).filter { provider ->
                runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            }
            if (providers.isEmpty()) return null

            providers.forEach { provider ->
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        /* minTimeMs = */ 0L,
                        /* minDistanceM = */ 0f,
                        executor,
                        listener
                    )
                }
            }
            latch.await(LOCATION_UPDATES_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        runCatching { locationManager.removeUpdates(listener) }
        return result.get()
    }

    @SuppressLint("MissingPermission")
    private fun awaitFusedLocationUpdates(fusedLocationClient: FusedLocationProviderClient): Location? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)
        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                if (result.compareAndSet(null, location)) {
                    latch.countDown()
                }
            }
        }
        runCatching {
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 0L)
                .setMinUpdateIntervalMillis(0L)
                .setMaxUpdates(1)
                .setDurationMillis(LOCATION_UPDATES_TIMEOUT_MS)
                .build()
            Tasks.await(
                fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper()),
                1500L,
                TimeUnit.MILLISECONDS
            )
            latch.await(LOCATION_UPDATES_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        runCatching { Tasks.await(fusedLocationClient.removeLocationUpdates(callback), 1500L, TimeUnit.MILLISECONDS) }
        return result.get()
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

    private companion object {
        private const val LOCATION_UPDATES_TIMEOUT_MS = 5000L
    }
}
