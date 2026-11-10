package cp.player.core.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 计算字符串的 MD5 哈希（32位小写十六进制）。 */
expect fun md5Hex(text: String): String

/**
 * 稳定的 64 位 FNV-1a，输出定长十六进制串。
 *
 * **不用 `String.hashCode`**：它只有 32 位、碰撞率高，且用在缓存键里意味着
 * 「两个不同输入的键撞成同一个」—— 本仓库的用途是给流缓存文件命名，
 * 撞了就会把 A 曲的音频当成 B 曲的本地副本。
 *
 * ⚠️ **实现不可换**：`DesktopStreamLocalizer` 用它给缓存文件命名，
 * 改了等于整盘 stream-cache 改名、全部失效（几十上百 MB 要重下）。
 */
fun stableHash64(value: String): String {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 位偏移基准
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}

/**
 * 缓存键内组件的转义：`#`、`&`、`=`、`%` 是键的结构分隔符，不转义就会撞键。
 *
 * 例：`{a: "1&b=2"}` 与 `{a: "1", b: "2"}` 不转义时都会拼成 `a=1&b=2`。
 */
private fun escapeComponent(value: String): String = buildString(value.length) {
    for (c in value) {
        when (c) {
            '#', '&', '=', '%' -> {
                append('%')
                append(c.code.toString(16))
            }
            else -> append(c)
        }
    }
}

/**
 * 生成稳定键：`providerId#method#sortedParams#cookieHash[#cfgHash]`。
 *
 * 目前唯一的消费者是 `MusicBackend.cacheNamespace`（流缓存按账号隔离）。
 * ⚠️ **算法不可换**：它参与流缓存文件命名，改一处等于整盘 stream-cache 失效。
 *
 * - 参数按 key 排序 ⇒ 传参顺序不影响键；
 * - 参数值转义 ⇒ 分隔符不会撞键；
 * - [cookie] 参与键 ⇒ 同机多账号必须隔离，否则 B 账号会读到 A 账号的文件。
 */
fun cacheKey(
    providerId: String,
    method: String,
    params: Map<String, String>,
    cookie: String? = null,
    providerConfig: Map<String, String> = emptyMap(),
): String {
    val sortedParams = params.entries.sortedBy { it.key }
        .joinToString("&") { (k, v) -> "${escapeComponent(k)}=${escapeComponent(v)}" }
    // 无 cookie 时用固定占位：null 与「空 cookie」必须落在同一个键上。
    val ck = if (cookie.isNullOrEmpty()) "anon" else "h${stableHash64(cookie)}"
    val configuration = if (providerConfig.isEmpty()) "" else {
        val canonical = JsonObject(
            providerConfig.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) },
        ).toString()
        "#cfg${stableHash64(canonical)}"
    }
    return "${escapeComponent(providerId)}#${escapeComponent(method)}#$sortedParams#$ck$configuration"
}
