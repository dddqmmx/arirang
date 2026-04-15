package asia.nana7mi.arirang.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import asia.nana7mi.arirang.R;
import asia.nana7mi.arirang.hook.IHookNotify;
import asia.nana7mi.arirang.service.HookNotifyService;

public class TestActivity extends AppCompatActivity {

    private IHookNotify hookNotify;
    private TextView tvResult;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hookNotify = IHookNotify.Stub.asInterface(service);
            Toast.makeText(TestActivity.this, R.string.service_connected, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hookNotify = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        tvResult = findViewById(R.id.tv_result);

        Button btnForeground = findViewById(R.id.btn_test_clipboard_foreground);
        Button btnBackground = findViewById(R.id.btn_test_clipboard_background);

        btnForeground.setOnClickListener(v -> {
            tvResult.setText(R.string.requesting_foreground);
            triggerPermissionRequest("asia.nana7mi.foreground.test");
        });

        btnBackground.setOnClickListener(v -> {
            tvResult.setText(R.string.requesting_background_hint);
            handler.postDelayed(() -> {
                triggerPermissionRequest("asia.nana7mi.background.test");
            }, 3000);
        });
        
        // Bind service automatically on create
        Intent intent = new Intent(this, HookNotifyService.class);
        bindService(intent, conn, BIND_AUTO_CREATE);
    }

    private void triggerPermissionRequest(String packageName) {
        if (hookNotify != null) {
            new Thread(() -> {
                try {
                    // This is a blocking call if it goes to HookNotifyService which waits for latch
                    int decision = hookNotify.requestClipboardRead(packageName, 10000, 0, 15000L);
                    int resultResId;
                    if (decision == 1) {
                        resultResId = R.string.result_allow;
                    } else {
                        resultResId = R.string.result_deny;
                    }
                    handler.post(() -> tvResult.setText(resultResId));
                } catch (Exception e) {
                    handler.post(() -> tvResult.setText(getString(R.string.exception) + e.getMessage()));
                }
            }).start();
        } else {
            Toast.makeText(this, R.string.service_not_connected, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, HookNotifyService.class);
            bindService(intent, conn, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unbindService(conn);
        } catch (Exception ignored) {}
    }
}
