package asia.nana7mi.arirang.ui.activity

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.hook.IArirangService
import asia.nana7mi.arirang.service.ArirangService
import kotlin.concurrent.thread

class TestActivity : AppCompatActivity() {

    private var hookNotify: IArirangService? = null
    private lateinit var tvResult: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            hookNotify = IArirangService.Stub.asInterface(service)
            Toast.makeText(this@TestActivity, R.string.service_connected, Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            hookNotify = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        tvResult = findViewById(R.id.tv_result)

        val btnForeground = findViewById<Button>(R.id.btn_test_clipboard_foreground)
        val btnBackground = findViewById<Button>(R.id.btn_test_clipboard_background)

        btnForeground.setOnClickListener {
            tvResult.setText(R.string.requesting_foreground)
            triggerPermissionRequest("asia.nana7mi.foreground.test")
        }

        btnBackground.setOnClickListener {
            tvResult.setText(R.string.requesting_background_hint)
            handler.postDelayed({
                triggerPermissionRequest("asia.nana7mi.background.test")
            }, 3000)
        }

        // Bind service automatically on create
        val intent = Intent(this, ArirangService::class.java)
        bindService(intent, conn, BIND_AUTO_CREATE)
    }

    private fun triggerPermissionRequest(packageName: String) {
        val notify = hookNotify
        if (notify != null) {
            thread {
                try {
                    // This is a blocking call if it goes to ArirangService which waits for latch
                    val decision = notify.requestClipboardRead(packageName, 10000, 0, 15000L)
                    val resultResId = if (decision == 1) {
                        R.string.result_allow
                    } else {
                        R.string.result_deny
                    }
                    handler.post { tvResult.setText(resultResId) }
                } catch (e: Exception) {
                    handler.post { tvResult.text = getString(R.string.exception) + e.message }
                }
            }
        } else {
            Toast.makeText(this, R.string.service_not_connected, Toast.LENGTH_SHORT).show()
            val intent = Intent(this, ArirangService::class.java)
            bindService(intent, conn, BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(conn)
        } catch (ignored: Exception) {
        }
    }
}
