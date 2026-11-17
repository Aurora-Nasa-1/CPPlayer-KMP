package cp.player.sdk

/** Bounded binary value codec. No Java serialization or business JSON is used. */
class WireWriter {
    private var out = ByteArray(256)
    private var size = 0
    private fun reserve(count: Int) {
        require(count >= 0 && count <= MAX_BYTES - size) { "Wire result limit" }
        if (size + count > out.size) out = out.copyOf(maxOf(size + count, minOf(MAX_BYTES, out.size * 2)))
    }
    fun int(value: Int) { reserve(4); repeat(4) { out[size++] = (value ushr (it * 8)).toByte() } }
    fun long(value: Long) { reserve(8); repeat(8) { out[size++] = (value ushr (it * 8)).toByte() } }
    fun bool(value: Boolean) { reserve(1); out[size++] = if (value) 1 else 0 }
    fun string(value: String) { bytes(value.encodeToByteArray()) }
    fun bytes(value: ByteArray) {
        require(value.size <= MAX_BYTES) { "Wire byte limit" }
        int(value.size)
        reserve(value.size)
        value.copyInto(out, size)
        size += value.size
    }
    fun <T> optional(value: T?, write: (T) -> Unit) { bool(value != null); if (value != null) write(value) }
    fun <T> list(value: List<T>, write: (T) -> Unit) {
        require(value.size <= MAX_ITEMS) { "Wire item limit" }
        int(value.size)
        value.forEach(write)
    }
    fun finish(): ByteArray = out.copyOf(size)
}

class WireReader(private val input: ByteArray) {
    private var offset = 0
    init { require(input.size <= MAX_BYTES) { "Wire result limit" } }
    private fun next(): Int {
        require(offset < input.size) { "Truncated wire value" }
        return input[offset++].toInt() and 255
    }
    fun int(): Int { var v = 0; repeat(4) { v = v or (next() shl (it * 8)) }; return v }
    fun long(): Long { var v = 0L; repeat(8) { v = v or (next().toLong() shl (it * 8)) }; return v }
    fun bool(): Boolean = when (next()) { 0 -> false; 1 -> true; else -> error("Invalid wire boolean") }
    fun string(): String = bytes().decodeToString(throwOnInvalidSequence = true)
    fun bytes(): ByteArray {
        val length = int()
        require(length >= 0 && length <= input.size - offset) { "Invalid wire length" }
        return input.copyOfRange(offset, offset + length).also { offset += length }
    }
    fun <T> optional(read: () -> T): T? = if (bool()) read() else null
    fun <T> list(read: () -> T): List<T> {
        val count = int()
        require(count in 0..MAX_ITEMS) { "Wire item limit" }
        return List(count) { read() }
    }
    fun finish() { require(offset == input.size) { "Trailing wire data" } }
}

const val MAX_BYTES = 4 * 1024 * 1024
const val MAX_ITEMS = 500
const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

fun valueObject(v: Any?): Map<String, Any?> {
    require(v is Map<*, *> && v.keys.all { it is String }) { "Expected object" }
    @Suppress("UNCHECKED_CAST")
    return v as Map<String, Any?>
}
fun valueList(v: Any?): List<Any?> = when (v) {
    is Array<*> -> v.toList()
    is List<*> -> v
    else -> error("Expected array")
}.also { require(it.size <= MAX_ITEMS) { "Value item limit" } }
fun valueString(v: Any?): String = (v as? String ?: error("Expected string"))
    .also { require(it.length <= MAX_BYTES) { "Value string limit" } }
fun valueLong(v: Any?): Long {
    require(v is Number) { "Expected number" }
    val d = v.toDouble()
    require(d.isFinite() && d >= -MAX_SAFE_INTEGER.toDouble() && d <= MAX_SAFE_INTEGER.toDouble() && d == d.toLong().toDouble()) {
        "Expected exact safe integer"
    }
    return d.toLong()
}
fun valueInt(v: Any?): Int = valueLong(v).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
fun valueBool(v: Any?): Boolean = v as? Boolean ?: error("Expected boolean")
/**
 * Accept both JVM byte arrays and JS number arrays. QuickJS materializes a
 * `Uint8Array`/`Array` as a numeric list, so a strict `ByteArray` cast would
 * reject every binary response produced by a script provider.
 */
fun valueBytes(v: Any?): ByteArray = when (v) {
    is ByteArray -> v.copyOf()
    is IntArray -> v.toCheckedBytes()
    is ShortArray -> v.toCheckedBytes()
    is LongArray -> v.toCheckedBytes()
    is DoubleArray -> v.toCheckedBytes()
    is FloatArray -> v.toCheckedBytes()
    is Array<*> -> bytesOf(v.asList())
    is List<*> -> bytesOf(v)
    else -> error("Expected bytes")
}

private fun bytesOf(values: List<*>): ByteArray {
    val output = ByteArray(values.size)
    values.forEachIndexed { index, value ->
        require(value is Number) { "Expected byte" }
        val number = value.toLong()
        require(number in -128..255) { "Byte out of range" }
        output[index] = number.toByte()
    }
    return output
}

private fun IntArray.toCheckedBytes(): ByteArray = ByteArray(size) { index ->
    require(this[index] in -128..255) { "Byte out of range" }
    this[index].toByte()
}

private fun ShortArray.toCheckedBytes(): ByteArray = ByteArray(size) { index ->
    require(this[index] in -128..255) { "Byte out of range" }
    this[index].toByte()
}

private fun LongArray.toCheckedBytes(): ByteArray = ByteArray(size) { index ->
    require(this[index] in -128..255) { "Byte out of range" }
    this[index].toByte()
}

private fun DoubleArray.toCheckedBytes(): ByteArray = ByteArray(size) { index ->
    val value = this[index]
    require(value.isFinite() && value == value.toLong().toDouble() && value in -128.0..255.0) {
        "Byte out of range"
    }
    value.toInt().toByte()
}

private fun FloatArray.toCheckedBytes(): ByteArray = ByteArray(size) { index ->
    val value = this[index]
    require(value.isFinite() && value == value.toLong().toFloat() && value in -128f..255f) {
        "Byte out of range"
    }
    value.toInt().toByte()
}
