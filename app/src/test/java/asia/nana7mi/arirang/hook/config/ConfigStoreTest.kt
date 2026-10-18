package asia.nana7mi.arirang.hook.config

import asia.nana7mi.arirang.hook.xposed.HookLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConfigStoreTest {

    private companion object {
        const val BACKGROUND_DEADLINE_NS = 2_000_000_000L
        const val BACKGROUND_POLL_MS = 5L
    }

    /** A source under the test's control: a payload, a read count, and the option to throw. */
    private class FakeSource(
        override val id: String = "fake",
        override val inlineReadable: Boolean = true,
        private val throws: Boolean = false,
        private val payload: () -> ConfigValues? = { null },
    ) : ConfigSource {
        val reads = AtomicInteger()
        override fun read(force: Boolean): ConfigValues? {
            reads.incrementAndGet()
            if (throws) throw IllegalStateException("source failed")
            return payload()
        }
    }

    private fun spec(
        id: String,
        sources: List<ConfigSource>,
        fallback: String = "fallback",
        arbitration: ConfigArbitration = Arbitrations.Single,
        readInline: Boolean = false,
        refreshIntervalMs: Long = 300L,
        decode: (ConfigValues) -> String? = { it.string("v") },
    ) = ConfigSpec(
        id = id,
        logModule = HookLog.Module.CORE,
        fallback = fallback,
        sources = sources,
        decode = decode,
        arbitration = arbitration,
        readInline = readInline,
        refreshIntervalMs = refreshIntervalMs,
    )

    /** A config with a declared field, as a feature module declares one. */
    private class OwnerSpec(id: String, sources: List<ConfigSource>) : ConfigSpec<String>(
        id = id,
        logModule = HookLog.Module.CORE,
        fallback = "x",
        sources = sources,
        decode = { it.string("v") },
    ) {
        val deviceName = field("deviceName", default = "default-name") { it.string("deviceName") }
    }

    @Test
    fun `a config nothing can answer for settles on the fallback`() {
        val spec = spec("fallback-only", listOf(FakeSource()))

        assertEquals("fallback", spec.current())
    }

    @Test
    fun `the value is decoded when the spec is installed, before any caller asks`() {
        val source = FakeSource(payload = { ConfigValues.of(mapOf("v" to "loaded")) })

        val spec = spec("warm-at-install", listOf(source))

        assertEquals(1, source.reads.get())
        assertEquals("loaded", spec.current())
    }

    @Test
    fun `two consumers of one config share its source reads but not its decode`() {
        // The unique-identifier config is read by one module for its advertising ID and by
        // another for its per-slot IMEI: they need the same reads and different projections.
        val firstSource = FakeSource("first", payload = { ConfigValues.of(mapOf("v" to "shared")) })
        val unusedSource = FakeSource("second", payload = { ConfigValues.of(mapOf("v" to "ignored")) })

        // Inline, so the test does not depend on a background refresh having run.
        val gaidProjection = spec(
            "shared-id", listOf(firstSource), readInline = true, decode = { it.string("v") }
        )
        val imeiProjection = spec(
            "shared-id", listOf(unusedSource), readInline = true,
            decode = { it.string("v")?.uppercase() },
        )

        assertNotSame(gaidProjection.store, imeiProjection.store)
        // Decoded from the config's shared values, not from the second spec's own source — which
        // would have answered "IGNORED". Its source is never read at all.
        assertEquals("SHARED", imeiProjection.current())
        assertEquals(0, unusedSource.reads.get())
    }

    @Test
    fun `a newer source wins when the arbitration asks for freshness`() {
        val older = FakeSource("old", payload = { ConfigValues.of(mapOf("v" to "old", "lastModified" to "1")) })
        val newer = FakeSource("new", payload = { ConfigValues.of(mapOf("v" to "new", "lastModified" to "2")) })

        val spec = spec("freshness", listOf(older, newer), arbitration = Arbitrations.FreshestVersion)

        assertEquals("new", spec.current())
    }

    @Test
    fun `a source that answers but cannot be decoded falls through to the next one`() {
        // The first source answers with something the decoder rejects; the next can still be
        // used, so one bad payload does not blank the config.
        val unusable = FakeSource("bad", payload = { ConfigValues.of(mapOf("v" to "reject")) })
        val usable = FakeSource("good", payload = { ConfigValues.of(mapOf("v" to "keep")) })

        val spec = spec(
            "decodable-fallthrough",
            listOf(unusable, usable),
            decode = { values -> values.string("v")?.takeIf { it != "reject" } },
        )

        assertEquals("keep", spec.current())
    }

    @Test
    fun `a background config keeps reading itself, and does not freeze at its install value`() {
        // An interval of zero makes the read permanently due, so this observes the scheduling
        // itself. An initial clock value that made the due comparison overflow would leave the
        // config frozen at whatever it held when the process started, and this would see one read.
        val source = FakeSource(payload = { ConfigValues.of(mapOf("v" to "live")) })
        val spec = spec("cadence-background", listOf(source), refreshIntervalMs = 0L)

        assertEquals("one read at install", 1, source.reads.get())
        spec.current()

        val deadline = System.nanoTime() + BACKGROUND_DEADLINE_NS
        while (source.reads.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(BACKGROUND_POLL_MS)
        }
        assertEquals("a refresh was scheduled", 2, source.reads.get())
    }

    @Test
    fun `an inline config does not re-read before its interval is up, but does when asked`() {
        val source = FakeSource(payload = { ConfigValues.of(mapOf("v" to "live")) })
        val spec = spec("cadence-inline", listOf(source), readInline = true)

        assertEquals(1, source.reads.get())
        spec.current()
        assertEquals(1, source.reads.get())

        spec.current(force = true)
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `a source that throws is tolerated and the others still answer`() {
        val broken = FakeSource("broken", throws = true)
        val working = FakeSource("working", payload = { ConfigValues.of(mapOf("v" to "fine")) })

        val spec = spec("throwing-source", listOf(broken, working))

        assertEquals("fine", spec.current())
    }

    @Test
    fun `a read that re-enters its own config gets the settled value instead of recursing`() {
        val holder = AtomicReference<ConfigStore<String>>()
        var nested: String? = null
        val source = FakeSource("reentrant") {
            // Ask for our own config from inside our own load, as a decoder that logs would.
            nested = holder.get()?.current()
            ConfigValues.of(mapOf("v" to "outer"))
        }

        val spec = spec("reentrancy", listOf(source), readInline = true)
        holder.set(spec.store)

        val value = spec.store.current(force = true)

        // The nested read is served from the settled value rather than re-entering the source.
        assertEquals("outer", nested)
        assertEquals("outer", value)
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `a declared field reads the values its own config was decoded from`() {
        val owner = OwnerSpec(
            id = "field-present",
            sources = listOf(FakeSource(payload = { ConfigValues.of(mapOf("v" to "x", "deviceName" to "Arirang")) })),
        )

        assertEquals("Arirang", owner.store.field(owner.deviceName))
    }

    @Test
    fun `a field falls back to its declared default when the config does not carry it`() {
        val owner = OwnerSpec(
            id = "field-absent",
            sources = listOf(FakeSource(payload = { ConfigValues.of(mapOf("v" to "x")) })),
        )

        assertEquals("default-name", owner.store.field(owner.deviceName))
    }

    @Test
    fun `the version bumps only when the decoded value changes`() {
        val payload = AtomicReference(ConfigValues.of(mapOf("v" to "one")))
        val spec = spec("versioning", listOf(FakeSource { payload.get() }), readInline = true)

        val initial = spec.store.version()
        spec.store.current(force = true)
        assertEquals(initial, spec.store.version())

        payload.set(ConfigValues.of(mapOf("v" to "two")))
        spec.store.current(force = true)
        assertTrue(spec.store.version() > initial)
        assertEquals("two", spec.current())
    }
}
