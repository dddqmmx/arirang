package asia.nana7mi.arirang.selfcheck.report

import asia.nana7mi.arirang.selfcheck.model.CheckResult

/**
 * The exported report: the sections the user picked, in the order the list holds them, as the
 * file's text.
 *
 * An object of one function, deliberately free of Android: the file's shape — what a section
 * header looks like, how a status line is spelled, how sections are separated — is the part of
 * exporting worth being able to read and test without a device, and it is the part that used to
 * be written inline in the activity between a document picker and a toast for it.
 */
object CheckReport {

    data class Entry(val title: String, val result: CheckResult)

    fun render(entries: List<Entry>, statusLabel: String): String = buildString {
        entries.forEach { entry ->
            appendLine("=== ${entry.title} ===")
            appendLine("$statusLabel: ${entry.result.status}")
            appendLine(entry.result.content)
            appendLine()
        }
    }
}
