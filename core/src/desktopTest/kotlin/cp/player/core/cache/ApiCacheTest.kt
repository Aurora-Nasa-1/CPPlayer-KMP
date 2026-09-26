package cp.player.core.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 缓存底层件（键 / LRU / 指纹）的不变式。
 *
 * 这些是"便宜但致命"的地方：键算错 → 串数据（用户 A 看到用户 B 的歌单）；
 * LRU 不算数 → 内存无界增长；指纹不稳 → 每次回源都被当成"内容变了"。
 */
class ApiCacheTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // ======================== cacheKey ========================

    @Test
    fun `参数值里的分隔符不能撞键`() {
        // 不转义时两者都会拼成 "a=1&b=2"，是两个完全不同的请求却共用一个缓存槽
        val collapsed = cacheKey("p", "m", mapOf("a" to "1&b=2"))
        val split = cacheKey("p", "m", mapOf("a" to "1", "b" to "2"))
        assertNotEquals(collapsed, split)
    }

    @Test
    fun `参数顺序不影响键`() {
        val a = cacheKey("p", "m", linkedMapOf("x" to "1", "y" to "2"))
        val b = cacheKey("p", "m", linkedMapOf("y" to "2", "x" to "1"))
        assertEquals(a, b)
    }

    @Test
    fun `账号 cookie 参与键`() {
        val accountA = cacheKey("p", "user/playlist", mapOf("uid" to "42"), cookie = "MUSIC_U=aaa")
        val accountB = cacheKey("p", "user/playlist", mapOf("uid" to "42"), cookie = "MUSIC_U=bbb")
        val anonymous = cacheKey("p", "user/playlist", mapOf("uid" to "42"), cookie = null)
        assertEquals(3, setOf(accountA, accountB, anonymous).size)
    }

    @Test
    fun `provider 与 method 参与键`() {
        assertNotEquals(
            cacheKey("p1", "m", emptyMap()),
            cacheKey("p2", "m", emptyMap())
        )
        assertNotEquals(
            cacheKey("p", "m1", emptyMap()),
            cacheKey("p", "m2", emptyMap())
        )
    }

    @Test
    fun `stableHash64 稳定且区分输入`() {
        assertEquals(stableHash64("MUSIC_U=abc"), stableHash64("MUSIC_U=abc"))
        assertNotEquals(stableHash64("a"), stableHash64("b"))
        assertNotEquals(stableHash64(""), stableHash64("a"))
        // 定长十六进制，可以直接塞进缓存键
        assertTrue(stableHash64("x").length <= 16)
    }

    // ======================== InMemoryApiCache ========================

    private fun entry(tag: String, now: Long = 1_000L) =
        CacheEntry(data = Json.parseToJsonElement("""{"tag":"$tag"}"""), fingerprint = tag, timestamp = now)

    @Test
    fun `超过上限时淘汰最久未使用的条目`() {
        val cache = InMemoryApiCache(maxEntries = 2)
        cache.put("a", entry("a"))
        cache.put("b", entry("b"))
        cache.get("a") // 让 a 变成最近使用，接下来该淘汰 b
        cache.put("c", entry("c"))

        assertEquals(2, cache.size())
        assertNull(cache.get("b"))
        assertTrue(cache.get("a") != null)
        assertTrue(cache.get("c") != null)
    }

    @Test
    fun `removeByPrefix 只删匹配前缀的条目`() {
        val cache = InMemoryApiCache()
        cache.put("default#playlist/detail#id=1#0", entry("1"))
        cache.put("default#playlist/detail#id=2#0", entry("2"))
        cache.put("default#song/detail#ids=9#0", entry("3"))

        cache.removeByPrefix("default#playlist/detail#")

        assertEquals(1, cache.size())
        assertNull(cache.get("default#playlist/detail#id=1#0"))
        assertNull(cache.get("default#playlist/detail#id=2#0"))
        assertTrue(cache.get("default#song/detail#ids=9#0") != null)
    }

    @Test
    fun `putData 自动打指纹与时间戳`() {
        val cache = InMemoryApiCache()
        cache.putData("k", json("""{"code":200,"songs":[{"id":7}]}"""), now = 12_345L)

        val stored = cache.get("k")!!
        assertEquals(12_345L, stored.timestamp)
        assertEquals(5L, stored.age(now = 12_350L))
        assertEquals(
            Fingerprinter.compute(json("""{"code":200,"songs":[{"id":7}]}""")),
            stored.fingerprint
        )
    }

    // ======================== Fingerprinter ========================

    @Test
    fun `同一份内容指纹稳定`() {
        val a = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":1},{"id":2}]}"""))
        val b = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":1},{"id":2}]}"""))
        assertEquals(a, b)
    }

    @Test
    fun `条目重排不算变化，增删才算`() {
        val base = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":1},{"id":2}]}"""))
        val reordered = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":2},{"id":1}]}"""))
        val added = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":1},{"id":2},{"id":3}]}"""))

        assertEquals(base, reordered)
        assertNotEquals(base, added)
    }

    @Test
    fun `无关字段不影响指纹，code 影响`() {
        val base = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":1}]}"""))
        val extraField = Fingerprinter.compute(json("""{"code":200,"songs":[{"id":1}],"extra":"x"}"""))
        val otherCode = Fingerprinter.compute(json("""{"code":301,"songs":[{"id":1}]}"""))

        assertEquals(base, extraField)
        assertNotEquals(base, otherCode)
    }

    @Test
    fun `非对象响应也能算出指纹`() {
        assertEquals(Fingerprinter.compute(json("\"scalar\"")), Fingerprinter.compute(json("\"scalar\"")))
        assertNotEquals(Fingerprinter.compute(json("\"a\"")), Fingerprinter.compute(json("\"b\"")))
    }
}
