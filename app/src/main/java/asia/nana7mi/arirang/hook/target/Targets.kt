package asia.nana7mi.arirang.hook.target

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IInterface
import android.os.Process
import android.os.SystemClock
import asia.nana7mi.arirang.hook.xposed.HookBridge
import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.afterHookedMethod
import asia.nana7mi.arirang.hook.xposed.beforeHookedMethod
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 *  - [install] runs until it succeeds, and never again once it has;
 *  - [install] receives the discovered object and reaches the class from it as it likes;
 *  - a throwing [install] is logged and swallowed, never propagated into framework code
 *    (the same contract [asia.nana7mi.arirang.hook.xposed.HookBridge] gives hook callbacks);
 *  - a target that never appears is a silent no-op.
 */
internal object Targets {

    private const val SERVICE_MANAGER_CLASS = "android.os.ServiceManager"
    private const val SYSTEM_SERVICE_MANAGER_CLASS = "com.android.server.SystemServiceManager"
    private const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"

    /**
     * Collects installs for one target and publishes the discovered object to them once.
     *
     * Success is tracked per install, not per target: an install that failed — the class was not
     * loaded yet, the APEX was not up — is retried on the next discovery, while the ones that
     * succeeded are not run again. A target that later registers an install is served from the
     * remembered object instead of waiting for a discovery that already happened. That is the
     * behaviour the per-module `AtomicBoolean`s had, with the reset rule stated once.
     */
    private class Waiter(private val label: String) {
        private companion object {
            /**
             * How long a second discovery waits for the first one's installs. Bounded because a
             * discovery runs inside framework code (`addService`, `Application.onCreate`), and
             * blocking it indefinitely is worse than dropping a duplicate.
             */
            const val PUBLISH_WAIT_MS = 2_000L
        }

        /**
         * The thread offering a discovery to the installs right now, or null.
         *
         * One reference rather than a flag plus a thread field: only its owner clears it, so a
         * publisher cannot erase the identity a successor just set, and a re-entrant discovery
         * recognises itself instead of spinning on its own work.
         */
        private val publisher = AtomicReference<Thread?>(null)

        private val installs = CopyOnWriteArrayList<(Any) -> Unit>()

        /**
         * The installs that have already done their job, by identity: a registration is a lambda
         * allocated once at its call site and never overrides equality.
         */
        private val completed: MutableSet<(Any) -> Unit> =
            Collections.newSetFromMap(ConcurrentHashMap())

        /**
         * The object already published for this target, kept so a registration that arrives
         * afterwards is served now rather than never.
         */
        @Volatile
        private var target: Any? = null

        fun add(install: (Any) -> Unit) {
            installs.add(install)
            // Discovery can reach an entry point before another module registers for the same
            // target; that module still has to get its install.
            val known = target
            if (known != null && !run(install, known)) {
                // It could not do its job even with the object in hand. Drop the object so the
                // next discovery offers it again, as a failed install during publish does.
                target = null
            }
        }

        fun publish(discovered: Any) {
            // Wait for a discovery in flight rather than dropping this one: that discovery may
            // be about to fail an install, and this object is the chance to retry it. Never
            // wait on ourselves — an install that reaches this target again has nothing to add.
            if (!acquire()) return
            try {
                target = discovered
                HookLog.i(
                    HookLog.Module.CORE,
                    "Targets: $label discovered as ${discovered.javaClass.name}"
                )
                // Only the installs that have not succeeded yet run: one that could not reach
                // its class the first time is retried on the next discovery, exactly as the
                // per-module `AtomicBoolean`s used to retry themselves, and a later
                // registration that already ran is not run twice.
                installs.filterNot(completed::contains).forEach { install -> run(install, discovered) }
            } finally {
                if (installs.any { it !in completed }) {
                    // Something failed: forget the discovery so the next one retries it.
                    target = null
                }
                publisher.compareAndSet(Thread.currentThread(), null)
            }
        }

        private fun acquire(): Boolean {
            val me = Thread.currentThread()
            val deadline = SystemClock.uptimeMillis() + PUBLISH_WAIT_MS
            while (true) {
                if (publisher.compareAndSet(null, me)) return true
                // An install that reaches this target again has nothing to add, and waiting on
                // itself would hold the discovery until the deadline.
                if (publisher.get() === me) return false
                if (SystemClock.uptimeMillis() >= deadline) return false
                Thread.yield()
            }
        }

        /** Runs [install] once; returns whether it has now succeeded. */
        private fun run(install: (Any) -> Unit, discovered: Any): Boolean {
            // Added before the call so a discovery and a late registration racing on the same
            // install cannot both run it; removed again if the install could not do its job.
            if (!completed.add(install)) return true
            return runCatching { install(discovered) }
                .onFailure {
                    completed.remove(install)
                    HookLog.e(HookLog.Module.CORE, "Targets: install for $label failed", it)
                }
                .isSuccess
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

    /** The installs already handed a Context, by identity, so none is run twice. */
    private val deliveredContextInstalls: MutableSet<(Context) -> Unit> =
        Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var applicationContext: Context? = null

    /**
     * This process's Application Context, if it has one yet.
     *
     * A config source binds to the manager service with this, so it must be the host process's
     * own Context rather than a framework one — and it does not exist until the Application is
     * attached.
     */
    fun applicationContext(): Context? = knownApplicationContext()

    /**
     * Runs [install] with this process's Application Context, once it exists.
     *
     * A registration that arrives after the process was created is served immediately rather
     * than never. `Application.onCreate` is the only notification the module gets, so a late
     * registration — the module is dispatched into a host that has been up for a while — has to
     * reach the Application through `ActivityThread`; otherwise it waits forever for an event
     * that has already happened, and the phone process's SIM rewrite silently never activates.
     */
    fun onApplicationContext(install: (Context) -> Unit) {
        contextInstalls.add(install)

        // A registration that arrives after the Application exists is served now rather than
        // never: `onCreate` has already fired, and there is no second notification.
        knownApplicationContext()?.let { known ->
            deliverContext(install, known)
            return
        }

        if (applicationContextHooked.compareAndSet(false, true)) {
            runCatching {
                val handles = HookBridge.hookAllMethods(Application::class.java, "onCreate", afterHookedMethod {
                    val context = (thisObject as? Application)?.applicationContext
                        ?: return@afterHookedMethod
                    applicationContext = context
                    for (target in contextInstalls) deliverContext(target, context)
                })
                // Nothing found is a failed install of the watcher, not a watcher that is in
                // place; leaving the flag set would silence every later registration.
                check(handles.isNotEmpty()) { "Application.onCreate not found" }
            }.onFailure {
                // Leave the flag open so a later registration tries again; otherwise this
                // install and every one after it would wait for a hook that is not there.
                applicationContextHooked.set(false)
                HookLog.e(HookLog.Module.CORE, "Targets: Application.onCreate hook failed", it)
            }
        }

        // `onCreate` may have fired between the probe above and the hook going in; ask again so
        // a registration that lost that race is not left waiting for an event already past.
        knownApplicationContext()?.let { deliverContext(install, it) }
    }

    private fun deliverContext(install: (Context) -> Unit, context: Context) {
        // Marked before the call so a late registration and a discovery racing on the same install
        // cannot both run it; unmarked again when the install could not do its job, so the next
        // opportunity retries it — the rule [Waiter.run] follows.
        if (!deliveredContextInstalls.add(install)) return
        runCatching { install(context) }.onFailure {
            deliveredContextInstalls.remove(install)
            HookLog.e(HookLog.Module.CORE, "Targets: application-context install failed", it)
        }
    }

    /**
     * The process's Application Context, from the `onCreate` hook or, failing that,
     * `ActivityThread`.
     *
     * `ActivityThread.currentActivityThread().getApplication()` is the same Application object
     * `onCreate` is called on, and unlike the callback it can be asked at any time — including
     * after `onCreate` has already run. Both members are public (hidden-API usage only), so this
     * is a plain reflective read, the same one [asia.nana7mi.arirang.hook.ipc.ArirangClient]
     * already uses for the host process's Context.
     */
    private fun knownApplicationContext(): Context? {
        applicationContext?.let { return it }
        // system_server is not a host process: its config reads bind through the privileged
        // system path with the framework's own Context, so the Application there is not what
        // they want. Only the `onCreate` field — which is what this answered with before — is
        // used there; the backfill is for app processes.
        if (Process.myUid() == Process.SYSTEM_UID) return null
        val context = runCatching {
            // The boot classloader, as for the other framework classes here: ActivityThread is
            // on the boot classpath in every process, so nothing process-specific is needed.
            val activityThread = HookBridge.findClassIfExists(ACTIVITY_THREAD_CLASS, null)
                ?: return@runCatching null
            val thread = HookBridge.callStaticMethod(activityThread, "currentActivityThread")
                ?: return@runCatching null
            // Non-null only once `handleBindApplication` has attached the Application, which is
            // before `onCreate` — so this answer is usable at any point, including after it.
            val application = HookBridge.callMethod(thread, "getApplication") as? Application
                ?: return@runCatching null
            application.applicationContext
        }.getOrNull() ?: return null
        applicationContext = context
        return context
    }
}
