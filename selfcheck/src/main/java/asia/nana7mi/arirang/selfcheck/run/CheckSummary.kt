package asia.nana7mi.arirang.selfcheck.run

import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState

/**
 * What a run has found so far: how many sections reported each state, and how many are still open.
 *
 * A run re-sends this with every result, so the summary line tracks the section list instead of
 * being computed once at the end — which is what keeps a slow check from hiding a fast one's
 * verdict behind an unchanged screen.
 */
data class CheckSummary(
    val visible: Int = 0,
    val blocked: Int = 0,
    val leaked: Int = 0,
    val pending: Int = 0,
) {

    /** Sections that have answered. */
    val settled: Int get() = visible + blocked + leaked

    /** Sections this run covers. */
    val total: Int get() = settled + pending

    companion object {
        /**
         * The tally of [results], counting [total] - `results.size` sections as still open.
         *
         * [total] is passed rather than derived because a run's size is the registry's, not the
         * answered subset's — the difference between the two *is* [pending].
         */
        fun of(results: Collection<CheckResult>, total: Int): CheckSummary = CheckSummary(
            visible = results.count { it.state == CheckState.VISIBLE },
            blocked = results.count { it.state == CheckState.BLOCKED },
            leaked = results.count { it.state == CheckState.LEAKED },
            pending = (total - results.size).coerceAtLeast(0),
        )

        /** Every section open, nothing answered yet. */
        fun pending(total: Int): CheckSummary = CheckSummary(pending = total)
    }
}
