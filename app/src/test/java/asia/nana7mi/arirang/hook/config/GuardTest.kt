package asia.nana7mi.arirang.hook.config

import asia.nana7mi.arirang.hook.xposed.HookLog
import asia.nana7mi.arirang.hook.xposed.MethodHookParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Executable

class GuardTest {

    private class FakeSource(private val payload: () -> ConfigValues?) : ConfigSource {
        override val id = "fake"
        override val inlineReadable = true
        override fun read(force: Boolean): ConfigValues? = payload()
    }

    /** A spec with a declared guard, as a feature config declares one. */
    private class GatedSpec(id: String, value: String) : ConfigSpec<String>(
        id = id,
        logModule = HookLog.Module.CORE,
        fallback = "closed",
        sources = listOf(FakeSource { ConfigValues.of(mapOf("v" to value)) }),
        decode = { it.string("v") },
    ) {
        val gate = guard("gate") { it == "open" }
    }

    private val executable: Executable = String::class.java.getDeclaredMethod("length")

    private fun param() = MethodHookParam(executable, "subject", arrayOf())

    @Test
    fun `the body does not run while the guard does not hold`() {
        val spec = GatedSpec("guard-closed", "closed")
        var calls = 0

        spec.gate.after { calls++ }.afterHookedMethod(param())

        assertEquals(0, calls)
    }

    @Test
    fun `the body runs with the value the guard admitted`() {
        val spec = GatedSpec("guard-open", "open")
        var seen: String? = null

        spec.gate.after { seen = it }.afterHookedMethod(param())

        assertEquals("open", seen)
    }

    @Test
    fun `a hooked method that threw is not gated in even when the guard holds`() {
        val spec = GatedSpec("guard-threw", "open")
        var calls = 0
        val param = param().apply { throwable = IllegalStateException("framework method threw") }

        spec.gate.after { calls++ }.afterHookedMethod(param)

        assertEquals(0, calls)
    }

    @Test
    fun `the guard can be asked for a value on its own`() {
        assertEquals("open", GatedSpec("guard-value-open", "open").gate.currentOrNull())
        assertNull(GatedSpec("guard-value-closed", "closed").gate.currentOrNull())
    }

    @Test
    fun `a site precondition keeps the gate shut even when the guard itself holds`() {
        // How a caller-dependent rule works: the config is on, and the guard holds, but this
        // caller is exempt — so the body must not run.
        val spec = GatedSpec("guard-where", "open")
        var calls = 0

        spec.gate.after(where = { _, _ -> false }) { calls++ }.afterHookedMethod(param())

        assertEquals(0, calls)
    }

    @Test
    fun `a site precondition that holds lets the body run with the value`() {
        val spec = GatedSpec("guard-where-allows", "open")
        var seen: String? = null

        spec.gate.after(where = { _, config -> config == "open" }) { seen = it }.afterHookedMethod(param())

        assertEquals("open", seen)
    }

    @Test
    fun `around runs neither half when a site precondition shuts the gate`() {
        val seen = mutableListOf<String>()
        val paired = GatedSpec("around-where", "open").gate.around(
            where = { _, _ -> false },
            before = { seen += "before" },
            after = { seen += "after" },
        )

        val param = param()
        paired.beforeHookedMethod(param)
        paired.afterHookedMethod(param)

        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `around gives both halves one value`() {
        val seen = mutableListOf<String>()
        val paired = GatedSpec("around-open", "open").gate.around(
            before = { seen += "before:$it" },
            after = { seen += "after:$it" },
        )

        val param = param()
        paired.beforeHookedMethod(param)
        paired.afterHookedMethod(param)

        assertEquals(listOf("before:open", "after:open"), seen)
    }

    @Test
    fun `around runs neither half when the gate is shut`() {
        // The first half decides; a half that undoes what the other did must not run alone.
        val seen = mutableListOf<String>()
        val paired = GatedSpec("around-shut", "closed").gate.around(
            before = { seen += "before" },
            after = { seen += "after" },
        )

        val param = param()
        paired.beforeHookedMethod(param)
        paired.afterHookedMethod(param)

        assertEquals(emptyList<String>(), seen)
    }
}
