/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/OPlusLyricPayload.kt
 *          (buildJsonObject / escapeJsonString / parseJsonString，297-355 行)
 * Changes: 从 OPlusLyricPayload 内部私有函数提成 lyricpush 包内共用的最小 JSON 工具，
 *          供 OPlus 载荷与超级岛配置**共用同一套**转义（两份实现必然漂移，而漂移点
 *          恰是「歌词里的引号与换行」）；新增 intField / boolField 两个读取口。
 *          仍然是手写而非 kotlinx.serialization：目标是稳定输出给**系统进程**读，
 *          不希望序列化框架哪天改了转义规则（例如把 `/` 也转义）而无人察觉。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

/**
 * 最小 JSON 工具：只支持「一维字符串 / 整数 / 布尔对象」这一种形状。
 *
 * 为什么不用 `kotlinx.serialization`：这些字符串是写给**系统进程 / 第三方模块**读的，
 * 字段顺序与转义必须稳定可预测；用通用序列化器等于把兼容性交给框架的默认行为。
 */
internal object LyricPushJson {

    /** 构造 `{"a":"1","b":"2"}`。字段顺序 = 传入顺序（接收端依赖顺序的时候才可控）。 */
    fun buildObject(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (name, value) ->
            "\"${name.escapeJsonString()}\":\"${value.escapeJsonString()}\""
        }

    /** 读取字符串字段；缺失或不是字符串返回 null。 */
    fun stringField(rawJson: String, name: String): String? {
        val key = "\"${name.escapeJsonString()}\""
        val keyIndex = rawJson.indexOf(key)
        if (keyIndex < 0) return null
        var index = rawJson.indexOf(':', keyIndex + key.length)
        if (index < 0) return null
        index++
        while (index < rawJson.length && rawJson[index].isWhitespace()) index++
        if (index >= rawJson.length || rawJson[index] != '"') return null
        return rawJson.parseJsonString(index)
    }

    fun intField(rawJson: String, name: String): Int? =
        stringField(rawJson, name)?.toIntOrNull()

    fun boolField(rawJson: String, name: String): Boolean? =
        stringField(rawJson, name)?.toBooleanStrictOrNull()

    internal fun String.escapeJsonString(): String {
        val out = StringBuilder(length + 16)
        forEach { char ->
            when (char) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        out.append("\\u")
                        val hex = char.code.toString(16)
                        repeat(4 - hex.length) { out.append('0') }
                        out.append(hex)
                    } else {
                        out.append(char)
                    }
                }
            }
        }
        return out.toString()
    }

    /** 从 `startQuoteIndex`（指向 `"`）开始解析一个 JSON 字符串。 */
    private fun String.parseJsonString(startQuoteIndex: Int): String? {
        if (startQuoteIndex !in indices || this[startQuoteIndex] != '"') return null
        val out = StringBuilder()
        var index = startQuoteIndex + 1
        while (index < length) {
            val char = this[index++]
            when (char) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= length) return null
                    when (val escaped = this[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > length) return null
                            val hex = substring(index, index + 4)
                            out.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                            index += 4
                        }
                        else -> return null
                    }
                }
                else -> out.append(char)
            }
        }
        return null
    }
}
