package cp.player.core.provider

import cp.player.sdk.*
import kotlin.test.*

class KmpSdkWireTest {
    @Test
    fun `binary values preserve opaque IDs Unicode bytes and explicit nulls`() {
        val track = Track("001-UUID-a", "\u97f3\u4e50", listOf(Artist("artist-A", "Artist")), 1234)
        val bytes = WireWriter().also(track::write).finish()
        val reader = WireReader(bytes)
        assertEquals(track, Track.read(reader))
        reader.finish()
        val binary = WireWriter().also { it.bytes(byteArrayOf(0, 127, -1)) }.finish()
        assertContentEquals(byteArrayOf(0, 127, -1), WireReader(binary).bytes())
    }

    @Test
    fun `truncation trailing values invalid tags invalid UTF8 and limits are rejected`() {
        assertFailsWith<IllegalArgumentException> { WireReader(byteArrayOf()).int() }
        assertFails { WireReader(byteArrayOf(2)).bool() }
        assertFailsWith<IllegalArgumentException> { WireReader(byteArrayOf(0)).finish() }
        assertFails { WireReader(byteArrayOf(1, 0, 0, 0, -1)).string() }
        assertFailsWith<IllegalArgumentException> { WireReader(ByteArray(MAX_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> {
            val writer = WireWriter()
            writer.list(List(501) { 0 }) { writer.int(it) }
        }
        assertFailsWith<IllegalArgumentException> { WireWriter().bytes(ByteArray(MAX_BYTES)) }
    }

    @Test
    fun `value decoder rejects wrong types fractional nonfinite and unsafe JS integers`() {
        assertEquals("001", valueString("001"))
        assertEquals(MAX_SAFE_INTEGER, valueLong(MAX_SAFE_INTEGER.toDouble()))
        assertFailsWith<IllegalArgumentException> { valueLong(1.5) }
        assertFailsWith<IllegalArgumentException> { valueLong(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { valueLong(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { valueLong(9_007_199_254_740_992.0) }
        assertFails { valueString(123) }
        assertFails { Track.fromValues(mapOf("id" to 123, "title" to "Track", "durationMs" to 1)) }
    }
}
