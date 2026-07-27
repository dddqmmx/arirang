package asia.nana7mi.arirang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import asia.nana7mi.arirang.hook.IArirangService
import asia.nana7mi.arirang.hook.IClipboardDecisionCallback
import asia.nana7mi.arirang.hook.IConfigSnapshotCallback
import asia.nana7mi.arirang.data.config.ManagedConfigSnapshot
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
            // Genuinely async: this returns as soon as the request is registered.
            // It previously delegated to the blocking form, so despite being
            // `oneway` it held one of this app's ~15 binder threads for up to 10 s
            // per request, eight at once, and config refreshes from system_server,
            // com.android.phone and GMS queued behind them.
            fun deliver(decision: Int) {
                runCatching { callback.onDecision(decision) }
                    .onFailure { Log.w(TAG, "Failed to deliver clipboard decision", it) }
            }

            val normalizedPkgName = pkgName.trim()
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid) ||
                !callerValidator.isAuthorizedPackageForCaller(callingUid, normalizedPkgName) ||
                !callerValidator.isPackageOwnedByUid(uid, normalizedPkgName)
            ) {
                Log.w(TAG, "Rejected clipboard decision request from uid=$callingUid for pkg=$pkgName")
                return deliver(ClipboardAccessDecision.ALLOW.value)
            }
            if (userId != serviceUserId) {
                Log.w(TAG, "Rejected cross-user clipboard request for pkg=$normalizedPkgName callerUser=$userId serviceUser=$serviceUserId")
                return deliver(ClipboardAccessDecision.DENY.value)
            }

            clipboardController.requestClipboardDecision(userId, normalizedPkgName) { decision ->
                deliver(decision.value)
            }
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

        // ConfigRegistry reads throw ConfigValidationException on a schema-version
        // mismatch, a missing required field or an oversized payload. Letting that
        // escape a binder method is bad here and worse on the oneway one: the
        // exception is swallowed by Binder, so the client never learns anything --
        // it just blocks its single config-refresh executor for the full 1s
        // timeout, every 300ms, forever, head-of-line blocking every other
        // config's refresh while logging only "timed out".

        override fun readConfigVersion(configName: String): Long {
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid)) {
                Log.w(TAG, "Rejected config version request from uid=$callingUid config=$configName")
                return 0L
            }
            return runCatching { configProvider.readConfigVersion(configName) }
                .onFailure { Log.e(TAG, "Unable to read version of config '$configName'", it) }
                .getOrDefault(0L)
        }

        override fun readConfigSnapshot(configName: String): String {
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid)) {
                Log.w(TAG, "Rejected config snapshot request from uid=$callingUid config=$configName")
                return ""
            }
            return runCatching { configProvider.readConfigSnapshot(configName) }
                .onFailure { Log.e(TAG, "Unable to read config '$configName'", it) }
                .getOrDefault("")
        }

        override fun readConfigAsync(configName: String, configCallback: IConfigSnapshotCallback) {
            val callingUid = getCallingUid()
            if (!callerValidator.isTrustedCaller(callingUid)) {
                Log.w(TAG, "Rejected async config request from uid=$callingUid config=$configName")
                deliverConfig(configCallback, null)
                return
            }
            val config = runCatching { configProvider.readConfig(configName) }
                .onFailure { Log.e(TAG, "Unable to read config '$configName'", it) }
                .getOrNull()
            deliverConfig(configCallback, config)
        }

        /** Invokes [callback] exactly once, so the client never waits out its timeout. */
        private fun deliverConfig(
            callback: IConfigSnapshotCallback,
            config: ManagedConfigSnapshot?
        ) {
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
