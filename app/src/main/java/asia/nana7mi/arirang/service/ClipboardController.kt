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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ClipboardController(private val context: Context) {

    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val DEFAULT_TIMEOUT_MS = 10000L
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

    private class PendingRequest(
        /** Invoked exactly once, from whichever of user choice or timeout wins. */
        val onDecision: (ClipboardAccessDecision) -> Unit
    ) {
        val answered = AtomicBoolean(false)

        fun resolve(decision: ClipboardAccessDecision) {
            if (answered.compareAndSet(false, true)) onDecision(decision)
        }
    }

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

    /**
     * Resolves a clipboard read, calling [onDecision] exactly once.
     *
     * Does not block. Cases decidable from policy answer inline; a prompt
     * registers the request and returns, and the decision arrives later from
     * either the user's choice or the timeout, whichever is first.
     *
     * This exists because the binder side is `oneway`: previously the handler
     * still blocked on a latch for the full 10 s, occupying one of the manager
     * app's binder threads per request and up to eight at once, so config
     * refreshes arriving from system_server, com.android.phone and GMS queued
     * behind them and served stale data for the duration.
     */
    fun requestClipboardDecision(
        userId: Int,
        pkgName: String,
        onDecision: (ClipboardAccessDecision) -> Unit
    ) {
        if (!isFeatureEnabled) return onDecision(ClipboardAccessDecision.ALLOW)

        val policyKey = ClipboardPromptPrefs.scopedPolicyId(userId, pkgName)
        when (synchronized(policyLock) { appPolicies[policyKey] } ?: defaultPolicy) {
            ClipboardPromptPrefs.Policy.ALLOW -> return onDecision(ClipboardAccessDecision.ALLOW)
            ClipboardPromptPrefs.Policy.DENY -> return onDecision(ClipboardAccessDecision.DENY)
            ClipboardPromptPrefs.Policy.ASK -> Unit
        }

        if (keyguardManager?.isKeyguardLocked == true) {
            return onDecision(ClipboardAccessDecision.DENY)
        }
        if (pendingRequests.size >= MAX_PENDING_REQUESTS) {
            return onDecision(ClipboardAccessDecision.DENY)
        }

        val requestId = requestIdGenerator.getAndIncrement()
        val pending = PendingRequest { decision ->
            pendingRequests.remove(requestId)
            onDecision(decision)
        }
        pendingRequests[requestId] = pending

        val receiver = buildDecisionReceiver(requestId, userId, pkgName)
        mainHandler.post { launchDialog(pkgName, receiver, DEFAULT_TIMEOUT_MS) }
        mainHandler.postDelayed({ pending.resolve(ClipboardAccessDecision.DENY) }, DEFAULT_TIMEOUT_MS)
    }

    /**
     * Blocking form, for the legacy synchronous binder method. Prefer
     * [requestClipboardDecision]; this one parks the calling thread.
     */
    fun handleClipboardRequest(userId: Int, pkgName: String): ClipboardAccessDecision {
        val latch = CountDownLatch(1)
        val decision = AtomicReference(ClipboardAccessDecision.DENY)
        requestClipboardDecision(userId, pkgName) {
            decision.set(it)
            latch.countDown()
        }
        return try {
            // The prompt's own timeout resolves the request, so this bound only
            // covers the handoff and is deliberately a little longer.
            if (latch.await(DEFAULT_TIMEOUT_MS + LATE_DECISION_GRACE_MS, TimeUnit.MILLISECONDS)) {
                decision.get()
            } else {
                ClipboardAccessDecision.DENY
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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
                    ConfirmDialogActivity.RESULT_ALLOW_ONCE, ConfirmDialogActivity.RESULT_ALLOW_ALWAYS ->
                        ClipboardAccessDecision.ALLOW
                    else -> ClipboardAccessDecision.DENY
                }

                // resolve() is idempotent, so a decision arriving after the
                // timeout already answered is simply dropped.
                pendingRequests[requestId]?.resolve(resolvedDecision)
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
