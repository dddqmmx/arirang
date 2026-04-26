package asia.nana7mi.arirang.service

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.edit
import asia.nana7mi.arirang.data.datastore.ClipboardPromptPrefs
import asia.nana7mi.arirang.hook.IHookNotify
import asia.nana7mi.arirang.ui.ConfirmDialogActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.Policy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HookNotifyService 是一个后台 Service，用于处理应用对剪贴板访问的请求。
 * 它提供 IPC 接口，允许其他应用通过 AIDL 调用 requestClipboardRead 来获取用户允许或拒绝的结果。
 *
 * 核心逻辑：
 * 1. 支持 Always Allow / Always Deny 策略。
 * 2. 支持弹出确认对话框让用户决定。
 * 3. 请求有超时机制，防止 UI 未响应导致阻塞。
 * 4. 并发请求通过 ConcurrentHashMap 管理，避免过载。
 */
class HookNotifyService : Service() {

    companion object {
        // 内部决策值：允许或拒绝
        private const val DECISION_DENY = 0
        private const val DECISION_ALLOW = 1

        // UI 返回结果码
        private const val UI_RESULT_DENY_ONCE = 0       // 本次拒绝
        private const val UI_RESULT_ALLOW_ONCE = 1      // 本次允许
        private const val UI_RESULT_ALLOW_ALWAYS = 2    // 永久允许
        private const val UI_RESULT_DENY_ALWAYS = 3     // 永久拒绝

        private const val DEFAULT_TIMEOUT_MS = 2500L    // 默认超时 2.5 秒
        private const val MAX_TIMEOUT_MS = 3000L        // 最大超时 3 秒
        private const val MAX_PENDING_REQUESTS = 8      // 最大同时等待请求数
        private const val LATE_DECISION_GRACE_MS = 15_000L // UI 决策延迟的宽限时间 15 秒
    }

    // 主线程 Handler，用于 UI 操作
    private val mainHandler = Handler(Looper.getMainLooper())

    // 请求 ID 生成器，确保每个请求唯一
    private val requestIdGenerator = AtomicLong(1L)

    // 存放待处理请求的 map
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()

    // 锁屏管理器
    private val keyguardManager by lazy { getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }

    // 策略锁，用于同步
    private val policyLock = Any()

    @Volatile
    private var isFeatureEnabled = true

    @Volatile
    private var defaultPolicy = ClipboardPromptPrefs.Policy.ASK

    private var appPolicies = mapOf<String, ClipboardPromptPrefs.Policy>()

    /**
     * 内部类表示一个待处理请求
     * @param latch 用于阻塞请求线程，直到用户决策返回
     * @param decision 用户最终决策
     * @param timedOut 请求是否超时
     */
    private data class PendingRequest(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var decision: Int? = null,
        @Volatile var timedOut: Boolean = false
    )

    /**
     * Binder 对象，实现 AIDL 接口 IHookNotify
     * 提供给其他进程调用
     */
    private val binder = object : IHookNotify.Stub() {

        /**
         * 请求读取剪贴板
         * @param pkgName 请求的应用包名
         * @param uid 请求的用户 ID
         * @param userId Android 系统的用户 ID
         * @param timeoutMs 等待用户决策的超时时间
         * @return DECISION_ALLOW 或 DECISION_DENY
         */
        override fun requestClipboardRead(pkgName: String, uid: Int, userId: Int, timeoutMs: Long): Int {
            if (!isFeatureEnabled) return DECISION_ALLOW

            val policy = synchronized(policyLock) { appPolicies[pkgName] } ?: defaultPolicy

            if (policy == ClipboardPromptPrefs.Policy.ALLOW) return DECISION_ALLOW
            if (policy == ClipboardPromptPrefs.Policy.DENY) return DECISION_DENY

            // 如果处于锁屏状态，直接拒绝
            if (keyguardManager?.isKeyguardLocked == true) {
                return DECISION_DENY
            }

            // 超过最大待处理请求数则直接拒绝
            if (pendingRequests.size >= MAX_PENDING_REQUESTS) return DECISION_DENY

            // 为本次请求生成唯一 ID，并存储 PendingRequest 对象
            val requestId = requestIdGenerator.getAndIncrement()
            val pending = PendingRequest()
            pendingRequests[requestId] = pending

            // 构建 ResultReceiver 用于接收 UI 决策
            val receiver = buildDecisionReceiver(requestId, pkgName)

            val effectiveTimeout = timeoutMs.coerceIn(200L, MAX_TIMEOUT_MS)

            // 在主线程启动确认对话框
            mainHandler.post {
                launchDialog(pkgName, receiver, effectiveTimeout)
            }

            // 阻塞当前线程等待用户决策或超时
            return try {
                val completed = pending.latch.await(
                    if (effectiveTimeout > 0L) effectiveTimeout else DEFAULT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
                if (!completed) {
                    // 超时未决策，标记并安排清理
                    pending.timedOut = true
                    scheduleCleanup(requestId)
                    DECISION_DENY
                } else {
                    // 移除请求并返回用户决策
                    pendingRequests.remove(requestId)
                    pending.decision ?: DECISION_DENY
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                pendingRequests.remove(requestId)
                DECISION_DENY
            }
        }

        /**
         * 当应用使用权限时调用（例如读取剪贴板）
         * @param pkgName 应用包名
         * @param opName 操作名称
         */
        override fun onPermissionUsed(pkgName: String, opName: String) {
            // 弹出确认对话框提醒用户
            mainHandler.post {
                launchDialog(pkgName, null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 启动时加载策略
        loadPolicy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 不会自动重启
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * 启动确认对话框 Activity
     * @param pkgName 请求的应用包名
     * @param receiver 用于回传决策结果
     */
    private fun launchDialog(pkgName: String, receiver: ResultReceiver?, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val intent = Intent(this, ConfirmDialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("pkg_name", pkgName)
            putExtra("timeout_ms", timeoutMs)
            if (receiver != null) {
                putExtra("receiver", receiver)
            }
        }

        val options = android.app.ActivityOptions.makeBasic()
        options.setPendingIntentBackgroundActivityStartMode(
            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        )

        try {
            startActivity(intent, options.toBundle())
        } catch (_: Exception) {
            // 如果无法启动 Activity，默认拒绝
            receiver?.send(UI_RESULT_DENY_ONCE, Bundle.EMPTY)
        }
    }

    /**
     * 构建 ResultReceiver，用于接收 UI 决策并通知 PendingRequest
     */
    private fun buildDecisionReceiver(
        requestId: Long,
        pkgName: String
    ): ResultReceiver {
        return object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                // 更新永久允许/拒绝策略
                when (resultCode) {
                    UI_RESULT_ALLOW_ALWAYS -> setAlwaysAllowed(pkgName)
                    UI_RESULT_DENY_ALWAYS -> setAlwaysDenied(pkgName)
                }

                // 转换成内部决策值
                val resolvedDecision = when (resultCode) {
                    UI_RESULT_ALLOW_ONCE, UI_RESULT_ALLOW_ALWAYS -> DECISION_ALLOW
                    else -> DECISION_DENY
                }

                // 获取 PendingRequest 并设置结果
                val pending = pendingRequests.remove(requestId)
                if (pending == null || pending.timedOut) {
                    return
                }

                pending.decision = resolvedDecision
                pending.latch.countDown()
            }
        }
    }

    /**
     * 超时请求的延迟清理，避免长时间占用 pendingRequests
     */
    private fun scheduleCleanup(requestId: Long) {
        mainHandler.postDelayed({
            pendingRequests.remove(requestId)
        }, LATE_DECISION_GRACE_MS)
    }

    /** ============================== 配置文件管理I/O ============================== */

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 加载永久策略（允许/拒绝应用包名）
     */
    private fun loadPolicy() {
        serviceScope.launch {
            ClipboardPromptPrefs.getAppPoliciesFlow(this@HookNotifyService).collect { policies ->
                synchronized(policyLock) {
                    appPolicies = policies
                }
            }
        }
        serviceScope.launch {
            ClipboardPromptPrefs.isFeatureEnabledFlow(this@HookNotifyService).collect { enabled ->
                isFeatureEnabled = enabled
            }
        }
        serviceScope.launch {
            ClipboardPromptPrefs.getDefaultPolicyFlow(this@HookNotifyService).collect { policy ->
                defaultPolicy = policy
            }
        }
    }

    /**
     * 将某个包名设置为永久允许
     */
    private fun setAlwaysAllowed(pkgName: String) {
        synchronized(policyLock) {
            val newMap = appPolicies.toMutableMap()
            newMap[pkgName] = ClipboardPromptPrefs.Policy.ALLOW
            appPolicies = newMap
            persistPolicyLocked(pkgName, ClipboardPromptPrefs.Policy.ALLOW)
        }
    }

    /**
     * 将某个包名设置为永久拒绝
     */
    private fun setAlwaysDenied(pkgName: String) {
        synchronized(policyLock) {
            val newMap = appPolicies.toMutableMap()
            newMap[pkgName] = ClipboardPromptPrefs.Policy.DENY
            appPolicies = newMap
            persistPolicyLocked(pkgName, ClipboardPromptPrefs.Policy.DENY)
        }
    }

    /**
     * 将策略持久化到 ClipboardPromptPrefs
     */
    private fun persistPolicyLocked(pkgName: String, policy: ClipboardPromptPrefs.Policy) {
        serviceScope.launch {
            ClipboardPromptPrefs.setAppPolicy(this@HookNotifyService, pkgName, policy)
        }
    }

}
