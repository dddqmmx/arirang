package asia.nana7mi.arirang.selfcheck.run

import android.content.Context
import asia.nana7mi.arirang.selfcheck.checker.Check
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A check's settled result, and the run's tally as of that moment.
 *
 * The two travel together so a section and the summary line can never disagree: whatever the
 * screen shows for a check, the tally it was computed with came from the same snapshot.
 */
data class CheckUpdate(
    val check: Check,
    val result: CheckResult,
    val summary: CheckSummary,
)

/**
 * What a run says on a check's behalf when the check does not answer for itself.
 *
 * Injected rather than resolved off a `Context`, because wording is the one thing on the run's own
 * path that a test cannot stand in for — the runner is otherwise exercisable without a device, and
 * it is the piece worth exercising: ordering, tallying, error isolation and cancellation are
 * exactly the parts a device cannot show you.
 */
data class CheckWording(
    /** A check that threw, or whose flow ended in failure. */
    val failed: String,
    /** A check whose flow ended without reporting anything. */
    val noResult: String,
)

/**
 * One run of the whole registry: every check started at once, each result forwarded the moment it
 * exists.
 *
 * The shape this replaces awaited all checks before touching the screen, so the location check's
 * fifteen seconds of bounded waits hid the other eighteen verdicts for fifteen seconds, and a
 * check that never finished meant the run never appeared to finish either. Here each check
 * publishes on its own thread of execution, and every update carries the tally as of itself, so
 * the screen is never behind what is already known.
 *
 * Checks are started together rather than in a bounded pool, which is deliberate: the slowest
 * check sets the run's duration either way, and queueing a fast check behind a slow one would
 * only delay the verdict that could have been on screen straight away.
 *
 * No check is given a deadline. A check that hangs leaves its own section open and counted in
 * [CheckSummary.pending], which is a truthful thing to show; a fabricated timeout result would
 * claim a verdict the device never gave, and would have to be retracted when the real one landed.
 *
 * The flow completes when every check has answered — a check's later results, the ones a
 * [Check.continuous] check emits while the user watches it, are collected from
 * [Check.run] directly and are not part of a run.
 */
class SelfCheckRunner(private val checks: List<Check>) {

    /** The run's updates, on the collector's dispatcher. Cancelling the collector stops the run. */
    fun run(context: Context, wording: CheckWording): Flow<CheckUpdate> = channelFlow {
        val answered = mutableMapOf<Check, CheckResult>()
        val tally = Mutex()
        checks.forEach { check ->
            launch { forward(check, context, wording, answered, tally) }
        }
    }

    /** One check's whole lifetime: its first result, and the run's tally with it counted. */
    private suspend fun ProducerScope<CheckUpdate>.forward(
        check: Check,
        context: Context,
        wording: CheckWording,
        answered: MutableMap<Check, CheckResult>,
        tally: Mutex,
    ) {
        // Reading a check cannot throw and always answers at least once (see [results]); the
        // fallback only keeps a check from being left pending if that ever stops being true.
        val settled = check.results(context, wording).firstOrNull()
            ?: CheckResult(CheckState.BLOCKED, wording.failed, wording.noResult)

        // Recorded and sent as one step, so the tally an update carries is always the tally as of
        // that update — never one a later update has already moved past.
        tally.withLock {
            answered[check] = settled
            send(CheckUpdate(check, settled, CheckSummary.of(answered.values, checks.size)))
        }
    }
}
