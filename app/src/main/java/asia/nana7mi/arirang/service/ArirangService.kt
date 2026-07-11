package asia.nana7mi.arirang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import asia.nana7mi.arirang.hook.IArirangService
import asia.nana7mi.arirang.hook.IClipboardDecisionCallback
import asia.nana7mi.arirang.hook.IConfigSnapshotCallback
import asia.nana7mi.arirang.model.ClipboardAccessDecision

/**
 * ArirangService 是一个后台 Service，现在作为一个 Facade，
 * 将具体的逻辑委托给独立的管理器类。
 */
class ArirangService : Service() {

    companion object {
        private const val TAG = "ArirangService"
        private const val PER_USER_RANGE = 100_000
    }

    private lateinit var callerValidator: CallerValidator
    private lateinit var configProvider: ConfigProvider
    private lateinit var clipboardController: ClipboardController

    private val serviceUserId by lazy { Process.myUid() / PER_USER_RANGE }

    override fun onCreate() {
        super.onCreate()
        callerValidator = CallerValidator(this)
        configProvider = ConfigProvider(this)
        clipboardController = ClipboardController(this)
    }

    private val binder = object : IArirangService.Stub() {
        override fun requestClipboardRead(pkgName: String, uid: Int, userId: Int): Int {
            val normalizedPkgName = pkgName.trim()
            val callingUid = getCallingUid()
            
            if (!callerValidator.isTrustedCaller(callingUid) ||
                !callerValidator.isAuthorizedPackageForCaller(callingUid, normalizedPkgName) ||
                !callerValidator.isPackageOwnedByUid(uid, normalizedPkgName)
            ) {
                Log.w(TAG, "Rejected clipboard decision request from uid=$callingUid for pkg=$pkgName")
                return ClipboardAccessDecision.ALLOW.value
            }

            if (userId != serviceUserId) {
                Log.w(TAG, "Rejected cross-user clipboard request for pkg=$normalizedPkgName callerUser=$userId serviceUser=$serviceUserId")
                return ClipboardAccessDecision.DENY.value
            }

            return clipboardController.handleClipboardRequest(userId, normalizedPkgName).value
        }

        override fun requestClipboardReadAsync(
            pkgName: String,
            uid: Int,
            userId: Int,
            callback: IClipboardDecisionCallback
        ) {
            val decision = requestClipboardRead(pkgName, uid, userId)
            runCatching { callback.onDecision(decision) }
                .onFailure { Log.w(TAG, "Failed to deliver clipboard decision", it) }
        }

        override fun onPermissionUsed(pkgName: String, uid: Int, userId: Int, opName: String) {
            val normalizedPkgName = pkgName.trim()
            val callingUid = getCallingUid()
            
            if (!callerValidator.isTrustedCaller(callingUid) ||
                !callerValidator.isAuthorizedPackageForCaller(callingUid, normalizedPkgName) ||
                !callerValidator.isPackageOwnedByUid(uid, normalizedPkgName)
            ) {
                Log.w(TAG, "Rejected permission usage event from uid=$callingUid for pkg=$pkgName")
                return
            }

            if (userId != serviceUserId) {
                Log.w(TAG, "Rejected cross-user permission usage event for pkg=$normalizedPkgName callerUser=$userId serviceUser=$serviceUserId")
                return
            }

            clipboardController.launchDialog(normalizedPkgName, null)
        }

        override fun readConfigVersion(configName: String): Long {
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid)) {
                Log.w(TAG, "Rejected config version request from uid=$callingUid config=$configName")
                return 0L
            }
            return configProvider.readConfigVersion(configName)
        }

        override fun readConfigSnapshot(configName: String): String {
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid)) {
                Log.w(TAG, "Rejected config snapshot request from uid=$callingUid config=$configName")
                return ""
            }
            return configProvider.readConfigSnapshot(configName)
        }

        override fun readConfigAsync(configName: String, callback: IConfigSnapshotCallback) {
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid)) {
                Log.w(TAG, "Rejected async config request from uid=$callingUid config=$configName")
                runCatching { callback.onConfig(0L, "") }
                return
            }
            val config = configProvider.readConfig(configName)
            runCatching { callback.onConfig(config?.version ?: 0L, config?.payload.orEmpty()) }
                .onFailure { Log.w(TAG, "Failed to deliver config snapshot", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
