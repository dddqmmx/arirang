package asia.nana7mi.arirang.hook.core


import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import asia.nana7mi.arirang.BuildConfig
import asia.nana7mi.arirang.hook.IArirangService
import asia.nana7mi.arirang.hook.IClipboardDecisionCallback
import asia.nana7mi.arirang.hook.IConfigSnapshotCallback
import asia.nana7mi.arirang.service.ArirangService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ArirangClient
 *
 * 注入在system_server
 * 作用：
 * 1. 作为 system_server 与目标 App之间的 Binder 客户端桥接层
 * 2. 让hook与app实时通信
 *
 * 设计目标：
 * - 非阻塞 system_server 主逻辑（等待时间严格受控）
 * - 支持服务异常死亡自动恢复
 * - 防止并发重复 bind
 * - 可配置 fail-open 策略（服务不可用时是否默认放行）
 */
object ArirangClient {

    fun interface ConnectionListener {
        fun onServiceConnected()
    }

    private val connectionListeners = java.util.concurrent.CopyOnWriteArrayList<ConnectionListener>()

    fun addConnectionListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    /** 目标服务所在包名 */
    private const val TARGET_PKG = BuildConfig.APPLICATION_ID

    /** bindService 连接超时时间（兜底防死锁） */
    private const val BIND_TIMEOUT_MS = 3000L

    /** 仅后台刷新线程允许等待连接；hook 热路径从不等待 bind。 */
    private const val BIND_WAIT_MS = 300L

    /** 配置远程读取的本地缓存时间，过期后仅触发后台刷新。 */
    private const val CONFIG_CACHE_TTL_MS = 300L

    /** 配置 Binder 调用的后台等待上限。 */
    private const val CONFIG_CALL_TIMEOUT_MS = 1_000L

    /** 管理 App 的剪贴板弹窗自身最多等待 10 秒，客户端额外留少量收尾时间。 */
    private const val CLIPBOARD_CALL_TIMEOUT_MS = 10_500L

    /** 服务端定义：允许访问的返回值 */
    private const val RESULT_ALLOW = 1

    /**
     * 服务不可用时是否默认放行（fail-open）
     *
     * true  = 服务异常时不阻断系统流程
     * false = 服务异常时默认拒绝（更安全但更激进）
     */
    private const val FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE = false

    /**
     * 当前已连接的远程 Binder 接口
     *
     * @Volatile 保证多线程可见性
     */
    @Volatile
    private var sService: IArirangService? = null

    /**
     * 是否正在进行 bind 操作
     *
     * 防止并发重复 bind
     */
    @Volatile
    private var sBinding = false

    private data class CachedConfig(
        @Volatile var snapshot: String? = null,
        @Volatile var version: Long = Long.MIN_VALUE,
        @Volatile var checkedAt: Long = 0L,
        val refreshInFlight: AtomicBoolean = AtomicBoolean(false)
    )

    private val configCache = ConcurrentHashMap<String, CachedConfig>()

    private val bindGeneration = AtomicLong(0L)

    private class BindingAttempt(
        val generation: Long,
        val context: Context,
        val currentUser: Boolean
    ) {
        val latch = CountDownLatch(1)
        lateinit var connection: ServiceConnection

        @Volatile
        var bound = false
    }

    // Process-lifetime binding owned by the injected host process; the Context is always an
    // application/system context and is released together with the ServiceConnection.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var activeBinding: BindingAttempt? = null

    private val configRefreshExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ArirangConfigRefresh").apply { isDaemon = true }
    }

    /**
     * 同步锁对象
     *
     * 保护服务、绑定尝试与代次状态的一致性
     */
    private val LOCK = Any()

    /**
     * 主线程 Handler
     *
     * 用于实现 bind 超时兜底逻辑
     */
    private val handler = Handler(Looper.getMainLooper())

    private inline fun <T> withCleanCallingIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun createConnection(attempt: BindingAttempt): ServiceConnection {
        return object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (activeBinding !== attempt) {
                    safeUnbind(attempt)
                    return
                }
                HookLog.i(HookLog.Module.NOTIFY, "onServiceConnected $name")
                try {
                    val connectedService = IArirangService.Stub.asInterface(service)
                    sService = connectedService
                    connectionListeners.forEach { listener ->
                        runCatching { listener.onServiceConnected() }
                            .onFailure {
                                HookLog.w(
                                    HookLog.Module.NOTIFY,
                                    "connection listener failed: ${it.message}"
                                )
                            }
                    }
                } catch (t: Throwable) {
                    HookLog.w(HookLog.Module.NOTIFY, "asInterface failed: ${t.message}")
                    sService = null
                } finally {
                    synchronized(LOCK) {
                        if (activeBinding === attempt) sBinding = false
                    }
                    attempt.latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (activeBinding !== attempt) return
                HookLog.i(HookLog.Module.NOTIFY, "onServiceDisconnected $name")
                sService = null
                sBinding = false
                attempt.latch.countDown()
                // A non-permanent disconnect keeps the binding alive; Android reconnects it.
            }

            override fun onBindingDied(name: ComponentName) {
                HookLog.i(HookLog.Module.NOTIFY, "onBindingDied $name")
                handlePermanentDisconnect(attempt)
            }

            override fun onNullBinding(name: ComponentName) {
                HookLog.i(HookLog.Module.NOTIFY, "onNullBinding $name")
                handlePermanentDisconnect(attempt)
            }
        }
    }

    private fun handlePermanentDisconnect(attempt: BindingAttempt) {
        val shouldReconnect = synchronized(LOCK) {
            if (activeBinding !== attempt) return
            activeBinding = null
            sService = null
            sBinding = false
            bindGeneration.incrementAndGet()
            attempt.latch.countDown()
            true
        }
        safeUnbind(attempt)
        if (shouldReconnect) scheduleReconnect(attempt.context, attempt.currentUser)
    }

    private fun safeUnbind(attempt: BindingAttempt) {
        if (!attempt.bound) return
        attempt.bound = false
        runCatching {
            withCleanCallingIdentity { attempt.context.unbindService(attempt.connection) }
        }
    }

    private fun scheduleReconnect(context: Context, currentUser: Boolean) {
        handler.postDelayed({
            if (sService == null && activeBinding == null) {
                HookLog.i(HookLog.Module.NOTIFY, "Triggering auto-reconnect...")
                if (currentUser) ensureBoundCurrentUser(context) else ensureBound(context)
            }
        }, 5_000L)
    }

    /**
     * 自动绑定服务（如果尚未绑定）
     */
    fun autoBind(ctx: Context) {
        if (sService == null && !sBinding) {
            ensureBound(ctx)
        }
    }

    fun autoBindCurrentUser(ctx: Context) {
        if (sService == null && !sBinding) {
            ensureBoundCurrentUser(ctx)
        }
    }

    /**
     * 上报某个应用使用了某权限
     *
     * 异步 fire-and-forget，不等待返回值
     */
    fun notifyPermissionUsed(pkgName: String, uid: Int, userId: Int, opName: String) {
        val service = sService ?: return
        try {
            withCleanCallingIdentity {
                service.onPermissionUsed(pkgName, uid, userId, opName)
            }
        } catch (_: DeadObjectException) {
            clearDeadService(service)
        } catch (t: Throwable) {
            HookLog.w(HookLog.Module.NOTIFY, "notify failed: ${t.message}")
        }
    }

    /**
     * 请求剪贴板读取权限
     *
     * @param pkgName 调用方包名
     * @param uid     调用方 UID
     * @param userId  多用户环境下的 userId
     * @return true = 允许读取
     *         false = 拒绝读取
     */
    fun requestClipboardReadAccess(
        pkgName: String,
        uid: Int,
        userId: Int
    ): Boolean {

        // Never bind from ClipboardService.getPrimaryClip(). systemReady maintains the
        // connection; the hook path only uses an already-connected Binder.
        val service = sService
        if (service == null || !service.asBinder().isBinderAlive) {
            HookLog.i(HookLog.Module.NOTIFY, "requestClipboardReadAccess: service unavailable, fail-open=$FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE")
            return FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }

        val latch = CountDownLatch(1)
        val decision = AtomicInteger(Int.MIN_VALUE)
        val callback = object : IClipboardDecisionCallback.Stub() {
            override fun onDecision(result: Int) {
                decision.set(result)
                latch.countDown()
            }
        }

        return try {
            withCleanCallingIdentity {
                service.requestClipboardReadAsync(pkgName, uid, userId, callback)
            }
            if (!latch.await(CLIPBOARD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                HookLog.w(HookLog.Module.NOTIFY, "clipboard request timed out")
                FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
            } else {
                decision.get() == RESULT_ALLOW
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        } catch (t: Throwable) {
            if (t is DeadObjectException) clearDeadService(service)
            HookLog.w(HookLog.Module.NOTIFY, "requestClipboardReadAccess failed: ${t.message}")
            FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }
    }

    fun readConfigSnapshot(
        configName: String,
        force: Boolean = false,
        allowBind: Boolean = false,
        bindContext: Context? = null,
        bindCurrentUser: Boolean = false,
        logName: String = configName
    ): String? {
        val now = SystemClock.uptimeMillis()
        val cache = configCache.getOrPut(configName) { CachedConfig() }
        val cachedSnapshot = cache.snapshot
        if (cachedSnapshot == null || force || now - cache.checkedAt >= CONFIG_CACHE_TTL_MS) {
            refreshConfigAsync(
                configName = configName,
                cache = cache,
                force = force,
                allowBind = allowBind,
                bindContext = bindContext,
                bindCurrentUser = bindCurrentUser,
                logName = logName
            )
        }
        return cachedSnapshot
    }

    private fun refreshConfigAsync(
        configName: String,
        cache: CachedConfig,
        force: Boolean,
        allowBind: Boolean,
        bindContext: Context?,
        bindCurrentUser: Boolean,
        logName: String
    ) {
        if (!cache.refreshInFlight.compareAndSet(false, true)) return
        configRefreshExecutor.execute {
            try {
                refreshConfig(
                    configName,
                    cache,
                    force,
                    allowBind,
                    bindContext,
                    bindCurrentUser,
                    logName
                )
            } finally {
                cache.checkedAt = SystemClock.uptimeMillis()
                cache.refreshInFlight.set(false)
            }
        }
    }

    private fun refreshConfig(
        configName: String,
        cache: CachedConfig,
        force: Boolean,
        allowBind: Boolean,
        bindContext: Context?,
        bindCurrentUser: Boolean,
        logName: String
    ) {
        // system 绑定路径（bindServiceAsUser(SYSTEM) + unstopPackage）依赖
        // system_server 的权限，只有在 system_server 进程中才可用。在独立宿主
        // 进程（如 com.android.bluetooth）里这些特权调用会失败，导致永远绑不上
        // 服务、读不到配置。此时必须退化为普通的 current-user 绑定。
        val useCurrentUser = bindCurrentUser || !isSystemServer()

        val service = if (allowBind) {
            // 非 system_server 进程优先用宿主自身的 Application Context 绑定，
            // 这样 bindService 归属正确；system_server 仍用 system context。
            val ctx = bindContext
                ?: (if (useCurrentUser) hostAppContext() else null)
                ?: getSystemContext()
            if (ctx == null) {
                HookLog.d(HookLog.Module.NOTIFY, "read $logName config snapshot: no context")
                return
            }
            if (useCurrentUser) {
                getOrBindServiceCurrentUser(ctx)
            } else {
                getOrBindService(ctx)
            }
        } else {
            sService
        } ?: return

        val latch = CountDownLatch(1)
        val receivedVersion = AtomicLong(Long.MIN_VALUE)
        val receivedSnapshot = AtomicReference<String?>(null)
        val callback = object : IConfigSnapshotCallback.Stub() {
            override fun onConfig(version: Long, snapshot: String?) {
                receivedVersion.set(version)
                receivedSnapshot.set(snapshot)
                latch.countDown()
            }
        }

        try {
            withCleanCallingIdentity {
                service.readConfigAsync(configName, callback)
            }
            if (!latch.await(CONFIG_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                HookLog.w(HookLog.Module.NOTIFY, "read $logName config snapshot timed out")
                return
            }
            val version = receivedVersion.get()
            if (force || cache.snapshot == null || version != cache.version) {
                val snapshot = receivedSnapshot.get()?.takeIf { it.isNotBlank() }
                if (snapshot != null) {
                    cache.snapshot = snapshot
                    cache.version = version
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            if (t is DeadObjectException) clearDeadService(service)
            HookLog.w(HookLog.Module.NOTIFY, "read $logName config snapshot failed: ${t.message}")
        }
    }

    private fun clearDeadService(service: IArirangService) {
        val deadBinding = synchronized(LOCK) {
            if (sService !== service) return
            sService = null
            val attempt = activeBinding
            if (attempt != null) {
                activeBinding = null
                sBinding = false
                bindGeneration.incrementAndGet()
                attempt.latch.countDown()
            }
            attempt
        }
        if (deadBinding != null) {
            // Clearing activeBinding first makes a racing onBindingDied callback a no-op.
            safeUnbind(deadBinding)
            scheduleReconnect(deadBinding.context, deadBinding.currentUser)
        }
    }

    /**
     * 确保已发起 bind 操作
     */
    private fun ensureBound(ctx: Context) {
        val attempt = beginBinding(ctx, currentUser = false) ?: return

        // 在 system_server 中强制将包设置为“已启动”状态
        // 这样可以绕过系统对未启动 App 的 Intent 过滤
        unstopPackage()

        val intent = Intent().apply {
            component = ComponentName(TARGET_PKG, ArirangService::class.java.name)
            // 🟢 核心修复：包含处于停止状态的包
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // 增加前台优先级，确保快速启动
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        try {
            // 获取 SYSTEM UserHandle (通常为 User 0)
            val systemUser = HookBridge.getStaticObjectField(
                UserHandle::class.java,
                "SYSTEM"
            ) as UserHandle

            HookLog.i(HookLog.Module.NOTIFY, "Binding to service $TARGET_PKG as user $systemUser")

            // 以 SYSTEM 用户身份绑定服务
            val success = withCleanCallingIdentity {
                ctx.bindServiceAsUser(
                    intent,
                    attempt.connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ABOVE_CLIENT,
                    systemUser
                )
            }

            if (!success) {
                HookLog.i(HookLog.Module.NOTIFY, "bindServiceAsUser returned false")
                failBinding(attempt, scheduleReconnect = true)
                return
            }
            attempt.bound = true

            scheduleBindTimeout(attempt, "bind")

        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "bind exception: ${t.stackTraceToString()}")
            failBinding(attempt, scheduleReconnect = true)
        }
    }

    private fun ensureBoundCurrentUser(ctx: Context) {
        val attempt = beginBinding(ctx, currentUser = true) ?: return

        val intent = Intent().apply {
            component = ComponentName(TARGET_PKG, ArirangService::class.java.name)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        try {
            HookLog.i(HookLog.Module.NOTIFY, "Binding to service $TARGET_PKG as current user")
            val success = withCleanCallingIdentity {
                ctx.bindService(
                    intent,
                    attempt.connection,
                    Context.BIND_AUTO_CREATE
                )
            }

            if (!success) {
                HookLog.i(HookLog.Module.NOTIFY, "current-user bindService returned false")
                failBinding(attempt, scheduleReconnect = true)
                return
            }
            attempt.bound = true

            scheduleBindTimeout(attempt, "current-user bind")
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "current-user bind exception: ${t.stackTraceToString()}")
            failBinding(attempt, scheduleReconnect = true)
        }
    }

    private fun beginBinding(ctx: Context, currentUser: Boolean): BindingAttempt? {
        synchronized(LOCK) {
            if (sBinding || sService != null || activeBinding != null) return null
            val attempt = BindingAttempt(bindGeneration.incrementAndGet(), ctx, currentUser)
            attempt.connection = createConnection(attempt)
            activeBinding = attempt
            sBinding = true
            return attempt
        }
    }

    private fun scheduleBindTimeout(attempt: BindingAttempt, label: String) {
        handler.postDelayed({
            val timedOut = synchronized(LOCK) {
                if (activeBinding !== attempt || attempt.generation != bindGeneration.get() ||
                    sService != null || !sBinding
                ) {
                    false
                } else {
                    activeBinding = null
                    sBinding = false
                    bindGeneration.incrementAndGet()
                    attempt.latch.countDown()
                    true
                }
            }
            if (timedOut) {
                HookLog.w(HookLog.Module.NOTIFY, "$label timeout; abandoning generation ${attempt.generation}")
                safeUnbind(attempt)
                scheduleReconnect(attempt.context, attempt.currentUser)
            }
        }, BIND_TIMEOUT_MS)
    }

    private fun failBinding(attempt: BindingAttempt, scheduleReconnect: Boolean) {
        val owned = synchronized(LOCK) {
            if (activeBinding !== attempt) return
            activeBinding = null
            sBinding = false
            bindGeneration.incrementAndGet()
            attempt.latch.countDown()
            true
        }
        if (owned) {
            safeUnbind(attempt)
            if (scheduleReconnect) scheduleReconnect(attempt.context, attempt.currentUser)
        }
    }

    /**
     * 强制取消包的“停止状态”
     * 运行环境：system_server
     */
    @SuppressLint("PrivateApi")
    private fun unstopPackage() {
        try {
            withCleanCallingIdentity {
                val b = HookBridge.callStaticMethod(
                    Class.forName("android.os.ServiceManager"),
                    "getService",
                    "package"
                ) as IBinder
                val ipm = HookBridge.callStaticMethod(
                    Class.forName("android.content.pm.IPackageManager\$Stub"),
                    "asInterface",
                    b
                )
                // setPackageStoppedState(packageName, stopped, userId)
                // UserId 0 是主用户
                HookBridge.callMethod(ipm, "setPackageStoppedState", TARGET_PKG, false, 0)
            }
            HookLog.i(HookLog.Module.NOTIFY, "Success to unstop package $TARGET_PKG")
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "Failed to unstop package: ${t.message}")
        }
    }

    /**
     * 获取远程服务
     *
     * 若尚未连接：
     * - 触发 bind
     * - 最多等待 BIND_WAIT_MS
     */
    private fun getOrBindService(ctx: Context): IArirangService? {

        // 快速路径
        sService?.let { return it }

        ensureBound(ctx)

        val latch = activeBinding?.latch
        if (latch != null) {
            runCatching {
                latch.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }

        return sService
    }

    private fun getOrBindServiceCurrentUser(ctx: Context): IArirangService? {
        sService?.let { return it }

        ensureBoundCurrentUser(ctx)

        val latch = activeBinding?.latch
        if (latch != null) {
            runCatching {
                latch.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }

        return sService
    }

    /**
     * 反射获取 system_server 的 SystemContext
     *
     * 通过 ActivityThread.currentActivityThread().getSystemContext()
     *
     * @return system Context 或 null
     */
    /**
     * 当前进程是否为 system_server。
     *
     * system_server 以 SYSTEM_UID(1000) 运行；其它被注入的宿主进程（如
     * com.android.bluetooth、com.android.phone）则使用各自独立的 UID。
     * 用于决定 bind 时走特权的 system 路径还是普通的 current-user 路径。
     */
    private fun isSystemServer(): Boolean = Process.myUid() == Process.SYSTEM_UID

    /**
     * 当前被注入宿主进程的 Application Context（可能为空）。
     *
     * 用于非 system_server 进程绑定服务，避免使用框架 system context 导致
     * bindService 归属异常。
     */
    @SuppressLint("PrivateApi")
    private fun hostAppContext(): Context? {
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = HookBridge.callStaticMethod(atClass, "currentActivityThread")
                ?: return null
            HookBridge.callMethod(at, "getApplication") as? Context
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    fun getSystemContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = HookBridge.callStaticMethod(
                atClass,
                "currentActivityThread"
            ) ?: return null

            HookBridge.callMethod(at, "getSystemContext") as Context

        } catch (_: Throwable) {
            null
        }
    }
}
