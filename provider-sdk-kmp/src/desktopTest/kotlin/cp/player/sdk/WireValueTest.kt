package cp.player.sdk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class WireValueTest {
    @Test
    fun bytes_accept_js_numeric_arrays() {
        assertContentEquals(byteArrayOf(0, 127, -128, -1), valueBytes(listOf(0, 127, 128, 255)))
        assertContentEquals(byteArrayOf(0, 127, -128, -1), valueBytes(arrayOf(0, 127, -128, -1)))
        assertContentEquals(byteArrayOf(0, 127, -128, -1), valueBytes(intArrayOf(0, 127, 128, 255)))
    }

    @Test
    fun bytes_reject_out_of_range_values() {
        assertFailsWith<IllegalArgumentException> { valueBytes(listOf(256)) }
        assertFailsWith<IllegalArgumentException> { valueBytes(listOf(-129)) }
        assertFailsWith<IllegalArgumentException> { valueBytes(listOf("0")) }
    }
}
