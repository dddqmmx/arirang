package asia.nana7mi.arirang.hook.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValuesTest {

    @Test
    fun `an alias resolves to whichever spelling the source uses`() {
        val fromSnapshot = ConfigValues.of(mapOf("currentSsid" to "home"))
        val fromPrefs = ConfigValues.of(mapOf("current_ssid" to "home"))

        assertEquals("home", fromSnapshot.string("currentSsid", "current_ssid"))
        assertEquals("home", fromPrefs.string("currentSsid", "current_ssid"))
    }

    @Test
    fun `a blank value reads as absent so the caller's default applies`() {
        val values = ConfigValues.of(mapOf("currentSsid" to "   "))

        assertNull(values.string("currentSsid", "current_ssid"))
    }

    @Test
    fun `booleans accept both sources' spellings`() {
        val values = ConfigValues.of(
            mapOf("a" to "true", "b" to "false", "c" to "1", "d" to "yes", "e" to "on", "f" to "junk")
        )

        assertEquals(true, values.boolean("a"))
        assertEquals(false, values.boolean("b"))
        assertEquals(true, values.boolean("c"))
        assertEquals(true, values.boolean("d"))
        assertEquals(true, values.boolean("e"))
        assertNull(values.boolean("f"))
    }

    @Test
    fun `the spellings the log-switch properties used still parse`() {
        // `persist.arirang.log.*` values predate this reader and included "enabled"/"disabled";
        // dropping them would silently ignore an operator's override.
        val values = ConfigValues.of(mapOf("a" to "enabled", "b" to "disabled", "c" to "enable"))

        assertEquals(true, values.boolean("a"))
        assertEquals(false, values.boolean("b"))
        assertEquals(true, values.boolean("c"))
    }

    @Test
    fun `numbers survive being carried as strings`() {
        val values = ConfigValues.of(mapOf("n" to "42", "f" to "42.0", "bad" to "x"))

        assertEquals(42, values.int("n"))
        assertEquals(42, values.int("f"))
        assertEquals(42L, values.long("n"))
        assertNull(values.int("bad"))
    }

    @Test
    fun `a nested array reads the same from a snapshot and from prefs`() {
        // The snapshot carries a real JSON array; the prefs store carries Gson text in a string.
        val fromSnapshot = ConfigValues.ofJson(
            """{"scanResults":[{"ssid":"a","bssid":"02:00:00:00:00:01"},{"ssid":"b","bssid":"02:00:00:00:00:02"}]}"""
        )!!
        val fromPrefs = ConfigValues.of(
            mapOf("scan_results" to """[{"ssid":"a","bssid":"02:00:00:00:00:01"},{"ssid":"b","bssid":"02:00:00:00:00:02"}]""")
        )

        val snapshotNetworks = fromSnapshot.objects("scanResults", "scan_results")
            .map { it.string("ssid") to it.string("bssid") }
        val prefsNetworks = fromPrefs.objects("scanResults", "scan_results")
            .map { it.string("ssid") to it.string("bssid") }

        assertEquals(listOf("a" to "02:00:00:00:00:01", "b" to "02:00:00:00:00:02"), snapshotNetworks)
        assertEquals(snapshotNetworks, prefsNetworks)
    }

    @Test
    fun `a string set reads the same from a JSON array and from a newline-joined set`() {
        val fromSnapshot = ConfigValues.ofJson("""{"exempt":["a.b","c.d"]}""")!!
        val fromPrefs = ConfigValues.of(mapOf("exempt" to "a.b\nc.d"))

        assertEquals(listOf("a.b", "c.d"), fromSnapshot.strings("exempt"))
        assertEquals(listOf("a.b", "c.d"), fromPrefs.strings("exempt"))
    }

    @Test
    fun `a JSON null becomes an absent key, not the string null`() {
        val values = ConfigValues.ofJson("""{"present":"x","missing":null}""")!!

        assertEquals("x", values.string("present"))
        assertNull(values.string("missing"))
    }

    @Test
    fun `freshness is read from whichever stamp the source uses`() {
        assertEquals(7L, ConfigValues.of(mapOf("lastModified" to "7")).version())
        assertEquals(7L, ConfigValues.of(mapOf("last_modified" to "7")).version())
        assertEquals(0L, ConfigValues.of(mapOf("other" to "7")).version())
    }

    @Test
    fun `an unparseable document yields nothing rather than throwing`() {
        assertNull(ConfigValues.ofJson("{not json"))
        assertNull(ConfigValues.ofJson(""))
    }

    @Test
    fun `preferences carrying a string set survive the round trip`() {
        val values = ConfigValues.ofPreferences(mapOf("exempt" to setOf("a.b", "c.d"), "n" to 3))

        assertEquals(listOf("a.b", "c.d"), values.strings("exempt"))
        assertEquals(3, values.int("n"))
    }
}
