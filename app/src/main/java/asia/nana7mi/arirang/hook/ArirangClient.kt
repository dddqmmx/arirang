package asia.nana7mi.arirang.hook

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
import asia.nana7mi.arirang.service.ArirangService
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ArirangClient
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
object ArirangClient {

    /** 日志 TAG */
    private const val TAG = "ArirangClient"

    /** 目标服务所在包名 */
    private const val TARGET_PKG = BuildConfig.APPLICATION_ID

    /** bindService 连接超时时间（兜底防死锁） */
    private const val BIND_TIMEOUT_MS = 3000L

    /** getOrBind 时最大等待连接时间 */
    private const val BIND_WAIT_MS = 300L

    /** 配置远程读取的本地缓存时间，避免热路径频繁 Binder 调用 */
    private const val CONFIG_CACHE_TTL_MS = 300L

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

    /**
     * 用于等待连接完成的同步器
     *
     * 每次 bind 创建一个新的 CountDownLatch
     */
    @Volatile
    private var sConnectLatch: CountDownLatch? = null

    private data class CachedConfig(
        @Volatile var snapshot: String? = null,
        @Volatile var version: Long = Long.MIN_VALUE,
        @Volatile var checkedAt: Long = 0L
    )

    private val configCache = ConcurrentHashMap<String, CachedConfig>()

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

    private inline fun <T> withCleanCallingIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * 重连延迟任务
     */
    private val reconnectRunnable = Runnable {
        val ctx = getSystemContext()
        if (ctx != null && sService == null) {
            HookLog.i(HookLog.Module.NOTIFY, "Triggering auto-reconnect...")
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
            HookLog.i(HookLog.Module.NOTIFY, "onServiceConnected $name")
            try {
                // 将 IBinder 转换为 AIDL 接口
                sService = IArirangService.Stub.asInterface(service)
                // 连接成功，移除潜在的重连任务
                handler.removeCallbacks(reconnectRunnable)
            } catch (t: Throwable) {
                HookLog.i(HookLog.Module.NOTIFY, "asInterface failed: ${t.stackTraceToString()}")
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
            HookLog.i(HookLog.Module.NOTIFY, "onServiceDisconnected $name")
            handleDisconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            HookLog.i(HookLog.Module.NOTIFY, "onBindingDied $name, connection is permanently dead for system auto-restart")
            handleDisconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            HookLog.i(HookLog.Module.NOTIFY, "onNullBinding $name")
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
        val ctx = getSystemContext() ?: return

        val service = getOrBindService(ctx) ?: return

        try {
            withCleanCallingIdentity {
                service.onPermissionUsed(pkgName, uid, userId, opName)
            }
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "notify failed: ${t.stackTraceToString()}")
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
        userId: Int
    ): Boolean {

        val ctx = getSystemContext()
        if (ctx == null) {
            HookLog.i(HookLog.Module.NOTIFY, "requestClipboardReadAccess: no system context")
            return FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }

        val service = getOrBindService(ctx)
        if (service == null) {
            HookLog.i(HookLog.Module.NOTIFY, "requestClipboardReadAccess: service unavailable, fail-open=$FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE")
            return FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        }

        return try {
            withCleanCallingIdentity {
                service.requestClipboardRead(
                    pkgName,
                    uid,
                    userId
                ) == RESULT_ALLOW
            }

        } catch (_: DeadObjectException) {
            // 远程进程已死亡
            sService = null
            FAIL_OPEN_WHEN_SERVICE_UNAVAILABLE
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "requestClipboardReadAccess failed: ${t.stackTraceToString()}")
            sService = null
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
        if (!force && cachedSnapshot != null && now - cache.checkedAt < CONFIG_CACHE_TTL_MS) {
            return cachedSnapshot
        }

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
                HookLog.i(HookLog.Module.NOTIFY, "read $logName config snapshot: no context")
                return cachedSnapshot
            }
            if (useCurrentUser) {
                getOrBindServiceCurrentUser(ctx)
            } else {
                getOrBindService(ctx)
            }
        } else {
            sService
        } ?: return cachedSnapshot

        return try {
            withCleanCallingIdentity {
                val version = service.readConfigVersion(configName)
                if (force || cachedSnapshot == null || version != cache.version) {
                    val snapshot = service.readConfigSnapshot(configName)?.takeIf { it.isNotBlank() }
                    if (snapshot != null) {
                        cache.snapshot = snapshot
                        cache.version = version
                    }
                }
                cache.checkedAt = now
                cache.snapshot
            }
        } catch (_: DeadObjectException) {
            sService = null
            cachedSnapshot
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "read $logName config snapshot failed: ${t.stackTraceToString()}")
            sService = null
            cachedSnapshot
        }
    }

    /**
     * 确保已发起 bind 操作
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
            val systemUser = XposedHelpers.getStaticObjectField(
                UserHandle::class.java,
                "SYSTEM"
            ) as UserHandle

            HookLog.i(HookLog.Module.NOTIFY, "Binding to service $TARGET_PKG as user $systemUser")

            // 以 SYSTEM 用户身份绑定服务
            val success = withCleanCallingIdentity {
                ctx.bindServiceAsUser(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ABOVE_CLIENT,
                    systemUser
                )
            }

            if (!success) {
                HookLog.i(HookLog.Module.NOTIFY, "bindServiceAsUser returned false")
                sBinding = false
                connectLatch?.countDown()
            }

            /**
             * 🔴 兜底超时机制
             */
            handler.postDelayed({
                if (sService == null && sBinding) {
                    HookLog.i(HookLog.Module.NOTIFY, "bind timeout, reset binding")
                    sBinding = false
                    connectLatch?.countDown()
                }
            }, BIND_TIMEOUT_MS)

        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "bind exception: ${t.stackTraceToString()}")
            sBinding = false
            connectLatch?.countDown()
        }
    }

    private fun ensureBoundCurrentUser(ctx: Context) {
        var connectLatch: CountDownLatch? = null

        synchronized(LOCK) {
            if (sBinding || sService != null) return

            sBinding = true
            connectLatch = CountDownLatch(1)
            sConnectLatch = connectLatch
        }

        val intent = Intent().apply {
            component = ComponentName(TARGET_PKG, ArirangService::class.java.name)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        try {
            HookLog.i(HookLog.Module.NOTIFY, "Binding to service $TARGET_PKG as current user")
            val success = withCleanCallingIdentity {
                ctx.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }

            if (!success) {
                HookLog.i(HookLog.Module.NOTIFY, "current-user bindService returned false")
                sBinding = false
                connectLatch?.countDown()
            }

            handler.postDelayed({
                if (sService == null && sBinding) {
                    HookLog.i(HookLog.Module.NOTIFY, "current-user bind timeout, reset binding")
                    sBinding = false
                    connectLatch?.countDown()
                }
            }, BIND_TIMEOUT_MS)
        } catch (t: Throwable) {
            HookLog.i(HookLog.Module.NOTIFY, "current-user bind exception: ${t.stackTraceToString()}")
            sBinding = false
            connectLatch?.countDown()
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
                val b = XposedHelpers.callStaticMethod(
                    Class.forName("android.os.ServiceManager"),
                    "getService",
                    "package"
                ) as IBinder
                val ipm = XposedHelpers.callStaticMethod(
                    Class.forName("android.content.pm.IPackageManager\$Stub"),
                    "asInterface",
                    b
                )
                // setPackageStoppedState(packageName, stopped, userId)
                // UserId 0 是主用户
                XposedHelpers.callMethod(ipm, "setPackageStoppedState", TARGET_PKG, false, 0)
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

        val latch = sConnectLatch
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
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
                ?: return null
            XposedHelpers.callMethod(at, "getApplication") as? Context
        }.getOrNull()
    }

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
