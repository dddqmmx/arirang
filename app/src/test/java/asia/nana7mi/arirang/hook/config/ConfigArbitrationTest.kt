package asia.nana7mi.arirang.hook.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigArbitrationTest {

    private fun stamped(version: Long, vararg pairs: Pair<String, String>) =
        ConfigValues.of(mapOf("lastModified" to version.toString()) + pairs)

    @Test
    fun `Single keeps source order and drops sources that answered nothing`() {
        val first = ConfigValues.EMPTY
        val second = ConfigValues.of(mapOf("a" to "1"))
        val third = ConfigValues.of(mapOf("a" to "2"))

        val ordered = Arbitrations.Single.order(listOf(first, second, third))

        assertEquals(listOf(second, third), ordered)
    }

    @Test
    fun `FreshestVersion puts the newer stamp first`() {
        val older = stamped(1, "a" to "old")
        val newer = stamped(2, "a" to "new")

        assertEquals(listOf(newer, older), Arbitrations.FreshestVersion.order(listOf(older, newer)))
    }

    @Test
    fun `FreshestVersion keeps source order when the stamps tie`() {
        val snapshot = stamped(5, "a" to "snapshot")
        val prefs = stamped(5, "a" to "prefs")

        assertEquals(
            listOf(snapshot, prefs),
            Arbitrations.FreshestVersion.order(listOf(snapshot, prefs))
        )
    }

    @Test
    fun `Overlay lets an earlier source override individual keys of the base`() {
        val property = ConfigValues.of(mapOf("wifi" to "false"))
        val snapshot = ConfigValues.of(mapOf("wifi" to "true", "sim" to "true"))
        val prefs = ConfigValues.of(mapOf("wifi" to "false", "sim" to "false", "core" to "true"))

        val merged = Arbitrations.Overlay.order(listOf(property, snapshot, prefs)).single()

        // The property wins where it spoke; the snapshot wins where it spoke; prefs supply the rest.
        assertEquals("false", merged.string("wifi"))
        assertEquals("true", merged.string("sim"))
        assertEquals("true", merged.string("core"))
    }

    @Test
    fun `Overlay yields nothing when no source answered`() {
        assertEquals(emptyList<ConfigValues>(), Arbitrations.Overlay.order(listOf(ConfigValues.EMPTY)))
    }
}
