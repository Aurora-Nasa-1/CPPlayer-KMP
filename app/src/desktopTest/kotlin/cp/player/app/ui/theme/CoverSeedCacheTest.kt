package cp.player.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CoverSeedCache] 回归测试。
 *
 * 这个缓存的三个不变量，每一个坏了都会以「用户能感觉到、但排查不到」的方式表现出来：
 * 1. **有界** —— 无界增长会随播放列表一路吃内存；
 * 2. **保留最近使用的** —— 淘汰策略写错会让「刚播过的歌再播一次」重新量化，正是要避免的卡顿；
 * 3. **失败不写缓存** —— 否则一次网络抖动会把某首歌的配色永久钉在回退色上。
 */
class CoverSeedCacheTest {

    @AfterTest
    fun tearDown() = CoverSeedCache.clear()

    @Test
    fun `a miss returns null so the caller knows it must decode`() {
        CoverSeedCache.clear()
        assertNull(CoverSeedCache.get("never-seen"))
    }

    @Test
    fun `a stored url is served from the cache`() {
        CoverSeedCache.clear()
        CoverSeedCache.put("https://example.com/a.jpg", Color(0xFF3B82F6))
        assertEquals(Color(0xFF3B82F6), CoverSeedCache.get("https://example.com/a.jpg"))
    }

    @Test
    fun `the cache is bounded and keeps the most recent entries`() {
        CoverSeedCache.clear()
        repeat(200) { CoverSeedCache.put("u$it", Color(0xFF000000.toInt() or it)) }

        val count = CoverSeedCache.cachedCount()
        assertTrue(
            count in 1..32,
            "缓存必须有界（当前 $count 条）—— 无界增长会随播放列表一路吃内存",
        )
        assertNotNull(CoverSeedCache.get("u199"), "刚写入的条目必须在，否则等于每次切歌都要重新量化")
        assertNull(CoverSeedCache.get("u0"), "最早写入的条目应已被淘汰")
    }

    @Test
    fun `reading an entry refreshes its recency`() {
        CoverSeedCache.clear()
        repeat(200) { CoverSeedCache.put("u$it", Color(0xFF000000.toInt() or it)) }

        // 此时缓存里是 u184..u199（按插入顺序即 LRU 顺序）。
        // 读一次 u184，把它顶成最近使用 —— 若 accessOrder 失效，这一步不会有任何效果。
        assertNotNull(CoverSeedCache.get("u184"))

        // 再插 15 条（= 容量 - 1）：只够把「最久未使用」的那批挤出去。
        repeat(15) { CoverSeedCache.put("extra$it", Color(0xFF123456)) }

        assertNotNull(
            CoverSeedCache.get("u184"),
            "刚读过的那首歌被淘汰了 —— accessOrder 失效，用户会听到莫名的重新取色",
        )
        assertNull(
            CoverSeedCache.get("u185"),
            "没被读过的邻居应当先被淘汰；它还在说明淘汰策略根本不是 LRU",
        )
    }
}
