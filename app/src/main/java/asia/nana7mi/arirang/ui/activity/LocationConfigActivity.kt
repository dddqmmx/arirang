package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationConfigActivity : AppCompatActivity() {

    // 原生 API 的两个文本框
    private lateinit var tvNativeCache: TextView
    private lateinit var tvNativeReal: TextView

    // Fused API 的两个文本框
    private lateinit var tvFusedCache: TextView
    private lateinit var tvFusedReal: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager

    companion object {
        private const val REQUEST_CODE = 100
        private val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_location_config)

        // 1. 初始化视图 (确保你的 layout 文件中有对应的 ID)
        tvNativeCache = findViewById(R.id.tv_native_cache)
        tvNativeReal = findViewById(R.id.tv_native_real)
        tvFusedCache = findViewById(R.id.tv_fused_cache)
        tvFusedReal = findViewById(R.id.tv_fused_real)

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            clearUI()
            checkPermissionsAndGetLocation()
        }

        // 2. 初始化定位客户端
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissionsAndGetLocation()
    }

    private fun clearUI() {
        val loading = "正在获取..."
        tvNativeCache.text = "原生(缓存): $loading"
        tvNativeReal.text = "原生(实时): $loading"
        tvFusedCache.text = "Fused(缓存): $loading"
        tvFusedReal.text = "Fused(实时): $loading"
    }

    private fun checkPermissionsAndGetLocation() {
        if (PERMISSIONS.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startFetchingLocations()
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_CODE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFetchingLocations() {
        // --- 1. 原生 API 定位方式 ---

        // A. 获取缓存位置 (LastKnownLocation)
        // 尝试从 GPS 或网络获取最后一次记录
        val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        val bestLast = lastGps ?: lastNetwork
        updateUI(tvNativeCache, bestLast, "原生(缓存)")

        // B. 获取实时位置 (getCurrentLocation / requestSingleUpdate)
        locationManager.getCurrentLocation(
            LocationManager.GPS_PROVIDER,
            null,
            ContextCompat.getMainExecutor(this)
        ) { location ->
            updateUI(tvNativeReal, location, "原生(实时)")
        }

        // --- 2. Google Fused API ---

        // C. 获取 Fused 缓存
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            updateUI(tvFusedCache, location, "Fused(缓存)")
        }

        // D. 获取 Fused 实时
        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                updateUI(tvFusedReal, location, "Fused(实时)")
            }
            .addOnFailureListener {
                tvFusedReal.text = "Fused(实时): 失败 ${it.message}"
            }
    }

    private fun updateUI(textView: TextView, location: Location?, label: String) {
        if (location != null) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(location.time))
            val info = """
                [$label]
                经纬度: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}
                精度: ${location.accuracy}m | 时间: $time
            """.trimIndent()
            textView.text = info
        } else {
            textView.text = "[$label]: 无数据 (请尝试开启GPS或移动位置)"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startFetchingLocations()
        } else {
            Toast.makeText(this, "需要定位权限才能显示数据", Toast.LENGTH_SHORT).show()
        }
    }
}