package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Arrays

class DeviceInfoConfigActivity : AppCompatActivity() {

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建 ScrollView 和 TextView
        val scrollView = ScrollView(this)
        textView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 16f
        }
        scrollView.addView(textView)
        setContentView(scrollView)

        // 检查并请求权限
        if (checkPermissions()) {
            displayDeviceInfo()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_PHONE_STATE),
            REQUEST_PHONE_STATE_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE_STATE_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            displayDeviceInfo()
        } else {
            textView.text = "权限被拒绝，无法获取部分设备信息。"
        }
    }

    private fun displayDeviceInfo() {
        val info = StringBuilder()

        // 基本设备信息
        info.append("品牌 (Brand): ${Build.BRAND}\n")
        info.append("型号 (Model): ${Build.MODEL}\n")
        info.append("设备 (Device): ${Build.DEVICE}\n")
        info.append("产品 (Product): ${Build.PRODUCT}\n")
        info.append("主板代号 (Board): ${Build.BOARD}\n")
        info.append("CPU 代号 (Supported ABIs): ${Arrays.toString(Build.SUPPORTED_ABIS)}\n")
        info.append("Android 版本 (Release): ${Build.VERSION.RELEASE}\n")
        info.append("API 级别 (SDK): ${Build.VERSION.SDK_INT}\n")
        info.append("Fingerprint: ${Build.FINGERPRINT}\n")

        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val simCountry = tm.simCountryIso?.uppercase() ?: "Unknown"
        info.append("SIM 国家 (ISO): $simCountry\n")


        // 显示信息
        textView.text = info.toString()
    }

    companion object {
        private const val REQUEST_PHONE_STATE_PERMISSION = 100
    }
}