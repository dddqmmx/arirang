package asia.nana7mi.arirang.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import asia.nana7mi.arirang.data.datastore.ClipboardPromptPrefs
import asia.nana7mi.arirang.model.ClipboardAccessDecision
import asia.nana7mi.arirang.ui.activity.ConfirmDialogActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ClipboardController(private val context: Context) {

    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val MAX_TIMEOUT_MS = 10000L
        private const val MAX_PENDING_REQUESTS = 8
        private const val LATE_DECISION_GRACE_MS = 15_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestIdGenerator = AtomicLong(1L)
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()
    private val keyguardManager by lazy { context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }
    private val policyLock = Any()

    @Volatile
    private var isFeatureEnabled = false

    @Volatile
    private var defaultPolicy = ClipboardPromptPrefs.Policy.ASK

    private var appPolicies = mapOf<String, ClipboardPromptPrefs.Policy>()
    private val serviceUserId = Process.myUid() / PER_USER_RANGE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        loadPolicy()
    }

    private data class PendingRequest(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var decision: Int? = null,
        @Volatile var timedOut: Boolean = false
    )

    private fun loadPolicy() {
        scope.launch {
            ClipboardPromptPrefs.getAllAppPoliciesFlow(context).collect { policies ->
                synchronized(policyLock) {
                    appPolicies = policies
                }
            }
        }
        scope.launch {
            ClipboardPromptPrefs.isFeatureEnabledFlow(context).collect { enabled ->
                isFeatureEnabled = enabled
            }
        }
        scope.launch {
            ClipboardPromptPrefs.getDefaultPolicyFlow(context).collect { policy ->
                defaultPolicy = policy
            }
        }
    }

    fun handleClipboardRequest(userId: Int, pkgName: String, timeoutMs: Long): ClipboardAccessDecision {
        if (!isFeatureEnabled) return ClipboardAccessDecision.ALLOW

        val policyKey = ClipboardPromptPrefs.scopedPolicyId(userId, pkgName)
        val policy = synchronized(policyLock) { appPolicies[policyKey] } ?: defaultPolicy

        if (policy == ClipboardPromptPrefs.Policy.ALLOW) return ClipboardAccessDecision.ALLOW
        if (policy == ClipboardPromptPrefs.Policy.DENY) return ClipboardAccessDecision.DENY

        if (keyguardManager?.isKeyguardLocked == true) {
            return ClipboardAccessDecision.DENY
        }

        if (pendingRequests.size >= MAX_PENDING_REQUESTS) return ClipboardAccessDecision.DENY

        val requestId = requestIdGenerator.getAndIncrement()
        val pending = PendingRequest()
        pendingRequests[requestId] = pending

        val receiver = buildDecisionReceiver(requestId, userId, pkgName)
        val effectiveTimeout = timeoutMs.coerceIn(200L, MAX_TIMEOUT_MS)

        mainHandler.post {
            launchDialog(pkgName, receiver, effectiveTimeout)
        }

        return try {
            val completed = pending.latch.await(
                if (effectiveTimeout > 0L) effectiveTimeout else DEFAULT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            if (!completed) {
                pending.timedOut = true
                scheduleCleanup(requestId)
                ClipboardAccessDecision.DENY
            } else {
                pendingRequests.remove(requestId)
                pending.decision?.let { ClipboardAccessDecision.fromInt(it) } ?: ClipboardAccessDecision.DENY
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            pendingRequests.remove(requestId)
            ClipboardAccessDecision.DENY
        }
    }

    fun launchDialog(pkgName: String, receiver: ResultReceiver?, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val intent = Intent(context, ConfirmDialogActivity::class.java).apply {
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
            context.startActivity(intent, options.toBundle())
        } catch (_: Exception) {
            receiver?.send(ConfirmDialogActivity.RESULT_DENY_ONCE, Bundle.EMPTY)
        }
    }

    private fun buildDecisionReceiver(
        requestId: Long,
        userId: Int,
        pkgName: String
    ): ResultReceiver {
        return object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    ConfirmDialogActivity.RESULT_ALLOW_ALWAYS -> setAlwaysAllowed(userId, pkgName)
                    ConfirmDialogActivity.RESULT_DENY_ALWAYS -> setAlwaysDenied(userId, pkgName)
                }

                val resolvedDecision = when (resultCode) {
                    ConfirmDialogActivity.RESULT_ALLOW_ONCE, ConfirmDialogActivity.RESULT_ALLOW_ALWAYS -> ClipboardAccessDecision.ALLOW.value
                    else -> ClipboardAccessDecision.DENY.value
                }

                val pending = pendingRequests.remove(requestId)
                if (pending == null || pending.timedOut) {
                    return
                }

                pending.decision = resolvedDecision
                pending.latch.countDown()
            }
        }
    }

    private fun scheduleCleanup(requestId: Long) {
        mainHandler.postDelayed({
            pendingRequests.remove(requestId)
        }, LATE_DECISION_GRACE_MS)
    }

    private fun setAlwaysAllowed(userId: Int, pkgName: String) {
        val policyKey = ClipboardPromptPrefs.scopedPolicyId(userId, pkgName)
        synchronized(policyLock) {
            val newMap = appPolicies.toMutableMap()
            newMap[policyKey] = ClipboardPromptPrefs.Policy.ALLOW
            appPolicies = newMap
            persistPolicyLocked(userId, pkgName, ClipboardPromptPrefs.Policy.ALLOW)
        }
    }

    private fun setAlwaysDenied(userId: Int, pkgName: String) {
        val policyKey = ClipboardPromptPrefs.scopedPolicyId(userId, pkgName)
        synchronized(policyLock) {
            val newMap = appPolicies.toMutableMap()
            newMap[policyKey] = ClipboardPromptPrefs.Policy.DENY
            appPolicies = newMap
            persistPolicyLocked(userId, pkgName, ClipboardPromptPrefs.Policy.DENY)
        }
    }

    private fun persistPolicyLocked(userId: Int, pkgName: String, policy: ClipboardPromptPrefs.Policy) {
        scope.launch {
            ClipboardPromptPrefs.setAppPolicyForUser(context, userId, pkgName, policy)
        }
    }
}
