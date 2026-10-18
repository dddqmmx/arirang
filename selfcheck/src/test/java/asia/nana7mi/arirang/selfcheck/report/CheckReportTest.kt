package asia.nana7mi.arirang.selfcheck.report

import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckReportTest {

    @Test
    fun `writes a header, a status line and the body, then a blank line before the next section`() {
        val text = CheckReport.render(
            entries = listOf(
                CheckReport.Entry("Unique Identifiers", CheckResult(CheckState.VISIBLE, "visible", "Android ID: 1234")),
                CheckReport.Entry("Build", CheckResult(CheckState.BLOCKED, "not visible", "")),
            ),
            statusLabel = "Status"
        )

        assertEquals(
            "=== Unique Identifiers ===\n" +
                "Status: visible\n" +
                "Android ID: 1234\n" +
                "\n" +
                "=== Build ===\n" +
                "Status: not visible\n" +
                "\n" +
                "\n",
            text
        )
    }

    @Test
    fun `an empty selection writes nothing`() {
        assertEquals("", CheckReport.render(emptyList(), statusLabel = "Status"))
    }
}
