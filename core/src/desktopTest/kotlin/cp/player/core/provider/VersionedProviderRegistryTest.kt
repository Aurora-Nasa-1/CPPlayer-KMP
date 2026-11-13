package cp.player.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VersionedProviderRegistryTest {
    private data class Descriptor(val id: String, val version: String)

    @Test
    fun `stale startup scan cannot replace a provider updated while loading`() {
        val registry = VersionedProviderRegistry<Descriptor> { it.id }
        val scanGeneration = registry.generation("demo")
        val updated = Descriptor("demo", "2.0")
        registry.put(updated)

        assertFalse(registry.putIfUnchanged(Descriptor("demo", "1.0"), scanGeneration))
        assertSame(updated, registry.get("demo"))
    }

    @Test
    fun `stale startup scan cannot resurrect a provider deleted while loading`() {
        val registry = VersionedProviderRegistry<Descriptor> { it.id }
        val scanGeneration = registry.generation("demo")
        val stale = Descriptor("demo", "1.0")

        assertNull(registry.remove("demo"))
        assertFalse(registry.putIfUnchanged(stale, scanGeneration))
        assertNull(registry.get("demo"))
        assertEquals(emptyList(), registry.all())
    }

    @Test
    fun `unchanged startup scan can register one descriptor only once`() {
        val registry = VersionedProviderRegistry<Descriptor> { it.id }
        val scanGeneration = registry.generation("demo")
        val first = Descriptor("demo", "1.0")

        assertTrue(registry.putIfUnchanged(first, scanGeneration))
        assertFalse(registry.putIfUnchanged(Descriptor("demo", "1.0"), scanGeneration))
        assertSame(first, registry.get("demo"))
    }
}
