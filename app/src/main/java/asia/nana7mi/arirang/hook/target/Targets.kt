package asia.nana7mi.arirang.hook.target

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IInterface
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.afterHookedMethod
import asia.nana7mi.arirang.hook.xposed.beforeHookedMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reaching a framework component that is not loaded yet, exactly once per process.
 *
 * A module cannot look up e.g. `ConnectivityService` when it runs: the class may not be loaded,
 * and `findClassIfExists` never triggers a load. Every module that needed a not-yet-loaded class
 * used to solve this itself — a watcher on `ServiceManager.addService`,
 * `SystemServiceManager.startServiceFromJar`, `Binder.attachInterface` or `Application.onCreate`,
 * plus its own once-only guard and its own classloader handling. This is that watcher, once.
 *
 * Every entry point guarantees:
 *  - [install] runs at most once per process per target;
 *  - [install] receives the discovered object and reaches the class from it as it likes;
 *  - a throwing [install] is logged and swallowed, never propagated into framework code
 *    (the same contract [asia.nana7mi.arirang.hook.xposed.HookBridge] gives hook callbacks);
 *  - a target that never appears is a silent no-op.
 */
internal object Targets {

    private const val SERVICE_MANAGER_CLASS = "android.os.ServiceManager"
    private const val SYSTEM_SERVICE_MANAGER_CLASS = "com.android.server.SystemServiceManager"

    /**
     * Collects installs for one target and publishes the discovered object to them once.
     *
     * The guard flips only after the installs have run, and flips back when every install failed,
     * so a target discovered before its own service is ready can still be retried — the
     * behaviour the per-module `AtomicBoolean`s had, with the reset rule stated once.
     */
    private class Waiter(private val label: String) {
        private val published = AtomicBoolean(false)
        private val installs = CopyOnWriteArrayList<(Any) -> Unit>()

        fun add(install: (Any) -> Unit) {
            installs.add(install)
        }

        fun publish(target: Any) {
            if (!published.compareAndSet(false, true)) return
            HookLog.i(
                HookLog.Module.CORE,
                "Targets: $label discovered as ${target.javaClass.name}"
            )
            var failures = 0
            for (install in installs) {
                runCatching { install(target) }.onFailure {
                    failures++
                    HookLog.e(HookLog.Module.CORE, "Targets: install for $label failed", it)
                }
            }
            if (installs.isNotEmpty() && failures == installs.size) {
                published.set(false)
            }
        }
    }

    // ---- Binders published through ServiceManager ----

    private val serviceWaiters = ConcurrentHashMap<String, Waiter>()
    private val addServiceHooked = AtomicBoolean(false)

    /**
     * Runs [install] with the object published under [serviceName] in `ServiceManager` — both if
     * it is already published and if it is published later.
     */
    fun onServiceClassPublished(serviceName: String, install: (Any) -> Unit) {
        val waiter = serviceWaiters.computeIfAbsent(serviceName) { Waiter(serviceName) }
        waiter.add(install)
        hookAddService()

        // Already published: reachable now, without waiting for a further addService.
        val serviceManager = serviceManagerClass()
        if (serviceManager != null) {
            runCatching {
                HookBridge.callStaticMethod(serviceManager, "getService", serviceName)
            }.getOrNull()?.let(waiter::publish)
        }
    }

    private fun serviceManagerClass(): Class<*>? =
        HookBridge.findClassIfExists(SERVICE_MANAGER_CLASS, null)

    private fun hookAddService() {
        if (!addServiceHooked.compareAndSet(false, true)) return
        val serviceManager = serviceManagerClass()
        if (serviceManager == null) {
            addServiceHooked.set(false)
            HookLog.w(HookLog.Module.CORE, "Targets: $SERVICE_MANAGER_CLASS not found")
            return
        }
        HookBridge.hookAllMethods(serviceManager, "addService", beforeHookedMethod {
            val name = args.getOrNull(0) as? String ?: return@beforeHookedMethod
            val waiter = serviceWaiters[name] ?: return@beforeHookedMethod
            val binder = args.getOrNull(1) ?: return@beforeHookedMethod
            waiter.publish(binder)
        })
    }

    // ---- Services started by SystemServiceManager ----

    private val systemServiceWaiters = ConcurrentHashMap<String, Waiter>()
    private val startServiceFromJarHooked = AtomicBoolean(false)

    /**
     * Runs [install] with the instance `SystemServiceManager.startServiceFromJar` produces for
     * [serviceClassName].
     */
    fun onSystemServiceStarted(
        serviceClassName: String,
        classLoader: ClassLoader?,
        install: (Any) -> Unit
    ) {
        val waiter = systemServiceWaiters.computeIfAbsent(serviceClassName) {
            Waiter(serviceClassName)
        }
        waiter.add(install)

        if (!startServiceFromJarHooked.compareAndSet(false, true)) return
        val managerClass = HookBridge.findClassIfExists(SYSTEM_SERVICE_MANAGER_CLASS, classLoader)
        if (managerClass == null) {
            startServiceFromJarHooked.set(false)
            HookLog.w(
                HookLog.Module.CORE,
                "Targets: $SYSTEM_SERVICE_MANAGER_CLASS not found in $classLoader"
            )
            return
        }
        HookBridge.hookAllMethods(managerClass, "startServiceFromJar", afterHookedMethod {
            val className = args.getOrNull(0) as? String ?: return@afterHookedMethod
            val waiter = systemServiceWaiters[className] ?: return@afterHookedMethod
            val started = result ?: return@afterHookedMethod
            HookLog.i(
                HookLog.Module.CORE,
                "Targets: startServiceFromJar($className) jar=${args.getOrNull(1)}"
            )
            waiter.publish(started)
        })
    }

    // ---- Binders that publish themselves through Binder.attachInterface ----

    private val interfaceWaiters = ConcurrentHashMap<String, Waiter>()
    private val attachInterfaceHooked = AtomicBoolean(false)

    /** Runs [install] with the [IInterface] owner that attaches [descriptor]. */
    fun onInterfaceAttached(descriptor: String, install: (Any) -> Unit) {
        val waiter = interfaceWaiters.computeIfAbsent(descriptor) { Waiter(descriptor) }
        waiter.add(install)

        if (!attachInterfaceHooked.compareAndSet(false, true)) return
        HookBridge.findAndHookMethod(
            Binder::class.java,
            "attachInterface",
            IInterface::class.java,
            String::class.java,
            afterHookedMethod {
                val attached = args.getOrNull(1) as? String ?: return@afterHookedMethod
                val waiter = interfaceWaiters[attached] ?: return@afterHookedMethod
                val owner = args.getOrNull(0) ?: return@afterHookedMethod
                waiter.publish(owner)
            }
        )
    }

    // ---- The host Application's Context ----

    private val contextInstalls = CopyOnWriteArrayList<(Context) -> Unit>()
    private val applicationContextHooked = AtomicBoolean(false)

    @Volatile
    private var applicationContext: Context? = null

    /**
     * This process's Application Context, if it has one yet.
     *
     * A config source binds to the manager service with this, so it must be the host process's
     * own Context rather than a framework one — and it does not exist until `onCreate`.
     */
    fun applicationContext(): Context? = applicationContext

    /**
     * Runs [install] with this process's Application Context, once it exists.
     *
     * A registration that arrives after the process was created is served immediately rather
     * than never — `Application.onCreate` has already fired by then.
     */
    fun onApplicationContext(install: (Context) -> Unit) {
        contextInstalls.add(install)

        applicationContext?.let { known ->
            runCatching { install(known) }.onFailure {
                HookLog.e(HookLog.Module.CORE, "Targets: late application-context install failed", it)
            }
            return
        }

        if (!applicationContextHooked.compareAndSet(false, true)) return
        HookBridge.hookAllMethods(Application::class.java, "onCreate", afterHookedMethod {
            val context = (thisObject as? Application)?.applicationContext
                ?: return@afterHookedMethod
            applicationContext = context
            for (target in contextInstalls) {
                runCatching { target(context) }.onFailure {
                    HookLog.e(HookLog.Module.CORE, "Targets: application-context install failed", it)
                }
            }
        })
    }
}
