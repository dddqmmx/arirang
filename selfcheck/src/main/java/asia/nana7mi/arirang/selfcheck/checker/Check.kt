package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One question the self check asks of the device, and the whole of what a check is.
 *
 * A check owns its probes and its verdict: which channels to read, how to read them, and what an
 * answer means. It does not own its presentation — no layout id, no view, no chip, no position in
 * the list — because [CheckRegistry] is the only place that knows the set of checks, and the UI
 * builds whatever that list holds. Adding a check is a file plus one line there.
 *
 * Implementations are named `...Checker`, and all but the live sensor monitor are a
 * [SnapshotCheck].
 */
interface Check {

    /** The section's title. */
    val titleRes: Int

    /**
     * The navigation chip's label, which is shorter than the title: the strip scrolls
     * horizontally, so a chip that does not fit is a chip the user never reads.
     */
    val navLabelRes: Int

    /**
     * Whether [run] keeps producing results after its first one — a measurement to watch rather
     * than a snapshot to take. Only a check that says so gets a live toggle in its section.
     */
    val continuous: Boolean get() = false

    /**
     * This check's results, in the order they become known.
     *
     * The **first** result is the settled answer, and is what a run counts and tallies; a
     * [continuous] check may then emit more, refining the same section until the collector stops.
     * Nothing else reports: a check that could not read anything emits a blocked result rather
     * than throwing, so no section is ever left over a question the device silently refused.
     *
     * A check that registers for callbacks — a sensor listener, a location update — must release
     * them when the collector cancels the flow, so an abandoned section stops costing the device
     * work. A check also dispatches for itself: it may block, on any thread the collector runs it
     * on.
     */
    fun run(context: Context): Flow<CheckResult>
}

/**
 * A check whose answer is one reading: read the device, judge it, emit it, done.
 *
 * The common shape, kept as an adapter so those checks write only their reading and never the
 * flow, the emit, or the dispatcher. A check that streams — the live sensor monitor — implements
 * [Check] directly instead.
 */
abstract class SnapshotCheck : Check {

    final override fun run(context: Context): Flow<CheckResult> = flow { emit(read(context)) }

    /**
     * The reading and the verdict on it. May block: this is collected on the caller's dispatcher,
     * so a check that waits on the device dispatches for itself.
     */
    protected abstract suspend fun read(context: Context): CheckResult
}
