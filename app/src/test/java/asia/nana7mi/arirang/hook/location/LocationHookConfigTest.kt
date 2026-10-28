package asia.nana7mi.arirang.hook.location

import asia.nana7mi.arirang.hook.config.ConfigValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "leave app locations unchanged by default" switch, at the decode seam.
 *
 * The switch is folded into the default profile's `enabled` flag rather than carried as a field
 * of its own, so these tests are about that folding surviving both sources' spellings.
 */
class LocationHookConfigTest {

    private fun decodeConfig(vararg pairs: Pair<String, String>): LocationHookConfig {
        val decoded = decode(ConfigValues.of(mapOf(*pairs)))
        assertNotNull("config should decode", decoded)
        return decoded!!
    }

    @Test
    fun `default profile applies when the switch is absent`() {
        val config = decodeConfig("enabled" to "true", "latitude" to "10.0")

        assertTrue(config.enabled)
        assertTrue(config.defaultProfile.enabled)
        assertEquals(10.0, config.defaultProfile.latitude, 0.0)
    }

    @Test
    fun `switch off leaves the default profile applying, as before`() {
        val config = decodeConfig("enabled" to "true", "unchangedDefault" to "false")

        assertTrue(config.defaultProfile.enabled)
    }

    @Test
    fun `switch on stops the default profile applying but keeps its coordinates`() {
        val config = decodeConfig(
            "enabled" to "true",
            "unchangedDefault" to "true",
            "latitude" to "10.0",
            "longitude" to "20.0",
        )

        assertTrue(config.enabled)
        assertFalse(config.defaultProfile.enabled)
        // Kept so switching the flag back off restores the same values without re-entering them.
        assertEquals(10.0, config.defaultProfile.latitude, 0.0)
        assertEquals(20.0, config.defaultProfile.longitude, 0.0)
    }

    @Test
    fun `the prefs spelling of the switch is read too`() {
        val config = decodeConfig("enabled" to "true", "unchanged_default" to "true")

        assertFalse(config.defaultProfile.enabled)
    }

    @Test
    fun `a per-app profile still applies while the default is unchanged`() {
        val config = decodeConfig(
            "enabled" to "true",
            "unchangedDefault" to "true",
            "per_package" to """{"com.example":{"enabled":true,"latitude":"35.0"}}""",
        )

        assertFalse(config.defaultProfile.enabled)
        val profile = config.perPackage["com.example"]
        assertNotNull(profile)
        assertTrue(profile!!.enabled)
        assertEquals(35.0, profile.latitude, 0.0)
    }

    @Test
    fun `a per-app profile can still opt out while the default applies`() {
        val config = decodeConfig(
            "enabled" to "true",
            "per_package" to """{"com.example":{"enabled":false}}""",
        )

        assertTrue(config.defaultProfile.enabled)
        assertFalse(config.perPackage.getValue("com.example").enabled)
    }

    @Test
    fun `an empty source decodes to nothing`() {
        assertNull(decode(ConfigValues.EMPTY))
    }
}
