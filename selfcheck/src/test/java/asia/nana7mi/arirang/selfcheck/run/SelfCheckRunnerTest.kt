package asia.nana7mi.arirang.selfcheck.run

import android.content.Context
import android.content.ContextWrapper
import asia.nana7mi.arirang.selfcheck.checker.Check
import asia.nana7mi.arirang.selfcheck.checker.SnapshotCheck
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run's contract — which is what the redesign was for: a check's result reaches the collector
 * as soon as *that* check has one, whatever the others are still doing.
 *
 * These are the parts a device cannot show you: the order results arrive in, what the tally counts
 * and when it counts it, that one broken check cannot take the run down with it, and that a check
 * which registered something releases it when the screen goes away. The fakes are opened and closed
 * by hand rather than by timing, so the tests do not depend on how fast the machine is.
 */
class SelfCheckRunnerTest {

    /**
     * A Context for checks that never read the device. The runner only passes it through, and
     * nothing here dereferences it — a JVM test has no Android runtime to build a real one from.
     */
    private val context: Context = ContextWrapper(null)

    private val wording = CheckWording(failed = "failed", noResult = "no result")

    private fun runner(vararg checks: Check) = SelfCheckRunner(checks.toList())

    @Test
    fun `a result arrives while another check is still running`() = runBlocking {
        val slow = GatedCheck(CompletableDeferred<Unit>(), visible("slow"))
        val fast = GatedCheck(CompletableDeferred(Unit), visible("fast"))
        val received = Channel<CheckUpdate>(Channel.UNLIMITED)
        val job = launch { runner(slow, fast).run(context, wording).collect { received.send(it) } }

        val update = withTimeout(DEADLINE_MS) { received.receive() }

        assertEquals("the answered check is reported while the other is still open", fast, update.check)
        assertEquals(1, update.summary.settled)
        assertEquals(1, update.summary.pending)
        job.cancelAndJoin()
    }

    @Test
    fun `each update carries the tally as of itself, and the run ends when every check has answered`() =
        runBlocking {
            val first = GatedCheck(CompletableDeferred<Unit>(), visible())
            val second = GatedCheck(CompletableDeferred<Unit>(), leaked())
            val third = GatedCheck(CompletableDeferred<Unit>(), blocked())
            val received = Channel<CheckUpdate>(Channel.UNLIMITED)
            val job = launch { runner(first, second, third).run(context, wording).collect { received.send(it) } }

            second.answer()
            val opening = withTimeout(DEADLINE_MS) { received.receive() }
            assertEquals(second, opening.check)
            assertEquals(CheckSummary(visible = 0, blocked = 0, leaked = 1, pending = 2), opening.summary)

            first.answer()
            third.answer()
            val closing = withTimeout(DEADLINE_MS) { listOf(received.receive(), received.receive()) }

            assertEquals(2, closing.first().summary.settled)
            assertEquals(CheckSummary(visible = 1, blocked = 1, leaked = 1, pending = 0), closing.last().summary)
            // The run ends by itself once nothing is left to answer; nothing had to time it out.
            withTimeout(DEADLINE_MS) { job.join() }
        }

    @Test
    fun `a check that throws is reported as a failure, not propagated`() = runBlocking {
        val broken = ThrowingCheck(IllegalStateException("no permission"))
        val fine = GatedCheck(CompletableDeferred(Unit), visible())
        val updates = withTimeout(DEADLINE_MS) { runner(broken, fine).run(context, wording).toList() }

        assertEquals(2, updates.size)
        val failure = updates.single { it.check === broken }.result
        assertEquals(CheckState.BLOCKED, failure.state)
        assertEquals("failed", failure.status)
        assertEquals("no permission", failure.content)
        assertEquals(CheckSummary(visible = 1, blocked = 1, pending = 0), updates.last().summary)
    }

    @Test
    fun `a check that ends without reporting is reported as a failure`() = runBlocking {
        val silent = SilentCheck()
        val update = withTimeout(DEADLINE_MS) { runner(silent).run(context, wording).toList() }.single()

        assertEquals(CheckState.BLOCKED, update.result.state)
        assertEquals("failed", update.result.status)
        assertEquals("no result", update.result.content)
    }

    @Test
    fun `a check that never answers stays pending instead of being invented`() = runBlocking {
        val hanging = HangingCheck()
        val prompt = GatedCheck(CompletableDeferred(Unit), visible())
        val received = Channel<CheckUpdate>(Channel.UNLIMITED)
        val job = launch { runner(hanging, prompt).run(context, wording).collect { received.send(it) } }

        val update = withTimeout(DEADLINE_MS) { received.receive() }

        assertEquals(1, update.summary.settled)
        assertEquals(1, update.summary.pending)
        assertTrue("no verdict may be invented for a check that has not answered", job.isActive)
        job.cancelAndJoinAndRelease(hanging)
    }

    @Test
    fun `a run takes a streaming check's first result and releases it`() = runBlocking {
        val streaming = StreamingCheck()
        val updates = withTimeout(DEADLINE_MS) { runner(streaming).run(context, wording).toList() }

        assertEquals("one result settles the section", 1, updates.size)
        assertEquals("first", updates.single().result.status)
        // Later emissions are the live section's, not the run's: the run must have stopped reading.
        withTimeout(DEADLINE_MS) { streaming.released.await() }
    }

    @Test
    fun `cancelling the collector releases a check that never answered`() = runBlocking {
        val hanging = HangingCheck()
        val job = launch { runner(hanging).run(context, wording).collect { } }
        withTimeout(DEADLINE_MS) { hanging.started.await() }

        job.cancelAndJoin()

        withTimeout(DEADLINE_MS) { hanging.released.await() }
    }

    private suspend fun Job.cancelAndJoinAndRelease(check: ReleasableCheck) {
        cancelAndJoin()
        withTimeout(DEADLINE_MS) { check.released.await() }
    }

    private companion object {
        /** Long enough to never fire on a working implementation, short enough not to hang a test run. */
        const val DEADLINE_MS = 2_000L

        fun visible(status: String = "visible") = CheckResult(CheckState.VISIBLE, status, "content")

        fun blocked() = CheckResult(CheckState.BLOCKED, "not visible", "hidden")

        fun leaked() = CheckResult(CheckState.LEAKED, "leak", "raw")
    }

    /** A check the test holds open: it answers when [answer] is called. */
    private class GatedCheck(
        private val gate: CompletableDeferred<Unit>,
        private val result: CheckResult,
    ) : SnapshotCheck() {
        override val titleRes = 0
        override val navLabelRes = 0

        override suspend fun read(context: Context): CheckResult {
            gate.await()
            return result
        }

        fun answer() = gate.complete(Unit)
    }

    /** A check that fails the way a check must not: by throwing instead of reporting. */
    private class ThrowingCheck(private val error: Exception) : SnapshotCheck() {
        override val titleRes = 0
        override val navLabelRes = 0
        override suspend fun read(context: Context): CheckResult = throw error
    }

    /** A check whose flow ends without ever reporting. */
    private class SilentCheck : Check {
        override val titleRes = 0
        override val navLabelRes = 0
        override fun run(context: Context): Flow<CheckResult> = flow { }
    }

    /** A check that never answers, and reports that its cleanup ran. */
    private class HangingCheck : ReleasableCheck() {
        override fun run(context: Context): Flow<CheckResult> = flow {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                released.complete(Unit)
            }
        }
    }

    /** A check that keeps emitting until cancelled, and reports that its cleanup ran. */
    private class StreamingCheck : ReleasableCheck() {
        override val continuous = true

        override fun run(context: Context): Flow<CheckResult> = flow {
            try {
                started.complete(Unit)
                emit(visible("first"))
                while (true) {
                    delay(1)
                    emit(visible("later"))
                }
            } finally {
                released.complete(Unit)
            }
        }
    }

    /**
     * A check that registered something. [released] completing is how a test sees that the flow's
     * cleanup ran, which for the sensor check is the listener coming off the sensor service.
     */
    private abstract class ReleasableCheck : Check {
        override val titleRes = 0
        override val navLabelRes = 0

        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
    }
}
