package asia.nana7mi.arirang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.Toast
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.activity.ConfirmDialogActivity

class ClipboardRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callingPackage = intent.getStringExtra("calling_package") ?: "Unknown"

        // 创建一个 ResultReceiver 来接收 Activity 返回结果
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == ConfirmDialogActivity.RESULT_ALLOW_ONCE ||
                    resultCode == ConfirmDialogActivity.RESULT_ALLOW_ALWAYS
                ) {
                    // 用户允许
                    Toast.makeText(context, context.getString(R.string.clipboard_access_allowed, callingPackage), Toast.LENGTH_SHORT).show()
                } else {
                    // 用户拒绝
                    Toast.makeText(context, context.getString(R.string.clipboard_access_denied, callingPackage), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 启动 ConfirmDialogActivity
        val dialogIntent = Intent(context, ConfirmDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("pkg_name", callingPackage)
            putExtra("receiver", receiver)
        }
        context.startActivity(dialogIntent)
    }
}
