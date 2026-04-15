package asia.nana7mi.arirang.hook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import asia.nana7mi.arirang.BuildConfig
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HookNotifyClient
 *
 * 运行环境：system_server
 * 作用：
 * 1. 作为 system_server 与目标 App之间的 Binder 客户端桥接层
 * 2. 向目标 App 上报权限使用事件
 * 3. 在读取剪贴板前向目标 App 请求授权（同步阻塞等待结果）
 *
 * 设计目标：
 * - 非阻塞 system_server 主逻辑（等待时间严格受控）
 * - 支持服务异常死亡自动恢复
 * - 防止并发重复 bind
 * - 可配置 fail-open 策略（服务不可用时是否默认放行）
 */
object HookNotifyClient {

    /** 日志 TAG */
    private const val TAG = "HookNotifyClient"

    /** 目标服务所在包名 */
    private const val TARGET_PKG = BuildConfig.APPLICATION_ID

    /** 显式绑定的 Action */
    private const val ACTION_BIND = "${TARGET_PKG}.BIND_NOTIFY"

    /** bindService 连接超时时间（兜底防死锁） */
    private const val BIND_TIMEOUT_MS = 3000L

    /** getOrBind 时最大等待连接时间 */
    private const val BIND_WAIT_MS = 300L

    /** 默认请求授权等待时间 */
    private const val DEFAULT_REQUEST_TIMEOUT_MS = 2500L

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
    private var sService: IHookNotify? = null

    /**
     * 是否正在进行 bind 操作
     *
     * 防止并发重复 bind
     */
    @Volatile
    private var sBinding = false

    /**
     * 用于等待连接完成的同步器
     *
     * 每次 bind 创建一个新的 CountDownLatch
     */
    @Volatile
    private var sConnectLatch: CountDownLatch? = null

    /**
     * 同步锁对象
     *
     * 保护 sBinding 与 sConnectLatch 状态一致性
     */
    private val LOCK = Any()

    /**
     * 主线程 Handler
     *
     * 用于实现 bind 超时兜底逻辑
     */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 重连延迟任务
     */
    private val reconnectRunnable = Runnable {
        val ctx = getSystemContext()
        if (ctx != null && sService == null) {
            XposedBridge.log("$TAG Triggering auto-reconnect...")
            ensureBound(ctx)
        }
    }

    /**
     * ⚠ 注意：
     * 在 system_server 中必须为成员变量
     * 不能使用匿名临时对象，否则可能被 GC 回收导致连接丢失
     */
    private val connection = object : ServiceConnection {

        /**
         * 成功建立 Binder 连接时回调
         */
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            XposedBridge.log("$TAG onServiceConnected $name")
            try {
                // 将 IBinder 转换为 AIDL 接口
                sService = IHookNotify.Stub.asInterface(service)
                // 连接成功，移除潜在的重连任务
                handler.removeCallbacks(reconnectRunnable)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG asInterface failed: ${t.stackTraceToString()}")
                sService = null
            } finally {
                // 标记不再处于 binding 状态
                sBinding = false
                // 释放等待线程
                sConnectLatch?.countDown()
            }
        }

        /**
         * 远程服务异常断开（进程死亡等）
         */
        override fun onServiceDisconnected(name: ComponentName) {
            XposedBridge.log("$TAG onServiceDisconnected $name")
            handleDisconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            XposedBridge.log("$TAG onBindingDied $name, connection is permanently dead for system auto-restart")
            handleDisconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            XposedBridge.log("$TAG onNullBinding $name")
            handleDisconnect()
        }

        private fun handleDisconnect() {
            sService = null
            sBinding = false
            sConnectLatch?.countDown()
            
            // 延迟重连（防止因 App 持续崩溃导致 system_server 死循环 bind 消耗过多 CPU）
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, 5000L) // 5秒后尝试重连
        }
    }

    /**
     * 自动绑定服务（如果尚未绑定）
     */
    fun autoBind(ctx: Context) {
        if (sService == null && !sBinding) {
            ensureBound(ctx)
        }
    }

    /**
     * 上报某个应用使用了某权限
     *
     * 异步 fire-and-forget，不等待返回值
     */
    fun notifyPermissionUsed(pkgName: String, opName: String) {
        val ctx = getSystemContext() ?: return

        val service = getOrBindService(ctx) ?: return

        try {
            service.onPermissionUsed(pkgName, opName)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG notify failed: ${t.stackTraceToString()}")
            // Binder 死亡，清空引用，下次自动重连
            sService = null
        }
    }

    /**
     * 请求剪贴板读取权限
     *
     * @param pkgName 调用方包名
     * @param uid     调用方 UID
     * @param userId  多用户环境下的 userId
     * @param timeoutMs 服务端允许等待的最长时间
     *
     * @return true = 允许读取
     *         false = 拒绝读取
     */
    fun requestClipboardReadAccess(
        pkgName: String,
        uid: Int,
        userId: Int,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): Boolean {

        val ctx = getSystemContext()
        if (ctx == null) {
            XposedBridge.log("$TAG requestClipboardReadAccess: no system context")
            return FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }

        val service = getOrBindService(ctx)
        if (service == null) {
            XposedBridge.log("$TAG requestClipboardReadAccess: service unavailable, fail-open=$FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE")
            return FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }

        return try {
            // 限制 timeout 范围，避免滥用
            service.requestClipboardRead(
                pkgName,
                uid,
                userId,
                timeoutMs.coerceIn(200L, 3000L)
            ) == RESULT_ALLOW

        } catch (_: DeadObjectException) {
            // 远程进程已死亡
            sService = null
            FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        } catch (t: Throwable) {
            XposedBridge.log("$TAG requestClipboardReadAccess failed: ${t.stackTraceToString()}")
            sService = null
            FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }
    }

    /**
     * 确保已发起 bind 操作
     *
     * 线程安全：
     * - 只允许一个线程触发 bind
     * - 其他线程复用同一个 CountDownLatch
     */
    private fun ensureBound(ctx: Context) {

        var connectLatch: CountDownLatch? = null

        synchronized(LOCK) {
            // 已经在绑定 或 已连接
            if (sBinding || sService != null) return

            sBinding = true
            connectLatch = CountDownLatch(1)
            sConnectLatch = connectLatch
        }

        val intent = Intent(ACTION_BIND).apply {
            setPackage(TARGET_PKG)
        }

        try {
            // 获取 SYSTEM UserHandle
            val systemUser = XposedHelpers.getStaticObjectField(
                UserHandle::class.java,
                "SYSTEM"
            ) as UserHandle

            // 以 SYSTEM 用户身份绑定服务
            ctx.bindServiceAsUser(
                intent,
                connection,
                Context.BIND_AUTO_CREATE,
                systemUser
            )

            /**
             * 🔴 兜底超时机制
             *
             * 如果系统未触发回调（极端异常）
             * 必须主动释放 latch，否则 await 将永久阻塞
             */
            handler.postDelayed({
                if (sService == null) {
                    XposedBridge.log("$TAG bind timeout, reset binding")
                    sBinding = false
                    connectLatch?.countDown()
                }
            }, BIND_TIMEOUT_MS)

        } catch (t: Throwable) {
            XposedBridge.log("$TAG bind exception: ${t.stackTraceToString()}")
            sBinding = false
            connectLatch?.countDown()
        }
    }

    /**
     * 获取远程服务
     *
     * 若尚未连接：
     * - 触发 bind
     * - 最多等待 BIND_WAIT_MS
     */
    private fun getOrBindService(ctx: Context): IHookNotify? {

        // 快速路径
        sService?.let { return it }

        ensureBound(ctx)

        val latch = sConnectLatch
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
    @SuppressLint("PrivateApi")
    fun getSystemContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = XposedHelpers.callStaticMethod(
                atClass,
                "currentActivityThread"
            ) ?: return null

            XposedHelpers.callMethod(at, "getSystemContext") as Context

        } catch (_: Throwable) {
            null
        }
    }
}