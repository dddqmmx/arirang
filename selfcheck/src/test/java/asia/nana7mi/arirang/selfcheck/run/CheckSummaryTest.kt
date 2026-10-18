package asia.nana7mi.arirang.selfcheck.run

import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckSummaryTest {

    @Test
    fun `counts each state, and what the run has not heard from yet`() {
        val summary = CheckSummary.of(
            listOf(visible(), visible(), blocked(), leaked()),
            total = 10
        )

        assertEquals(2, summary.visible)
        assertEquals(1, summary.blocked)
        assertEquals(1, summary.leaked)
        assertEquals(4, summary.settled)
        assertEquals(6, summary.pending)
        assertEquals(10, summary.total)
    }

    @Test
    fun `a complete tally has nothing pending`() {
        val summary = CheckSummary.of(listOf(visible(), leaked()), total = 2)

        assertEquals(0, summary.pending)
        assertEquals(2, summary.total)
    }

    @Test
    fun `more results than the run has sections is not a negative count`() {
        assertEquals(0, CheckSummary.of(listOf(visible(), visible()), total = 1).pending)
    }

    @Test
    fun `a fresh run has everything pending`() {
        val summary = CheckSummary.pending(19)

        assertEquals(0, summary.settled)
        assertEquals(19, summary.pending)
    }

    private fun visible() = CheckResult(CheckState.VISIBLE, "visible", "")

    private fun blocked() = CheckResult(CheckState.BLOCKED, "blocked", "")

    private fun leaked() = CheckResult(CheckState.LEAKED, "leaked", "")
}
