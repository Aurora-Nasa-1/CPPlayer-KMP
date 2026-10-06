package cp.player.app.ui.wall

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 墙的纯计算内核（[WallLayoutEngine] / [WallZoomMath]）。
 *
 * 这些用例存在的理由很具体：**装箱有没有重叠、比例配额是不是单调、鱼眼在两端是不是真的关掉了**
 * —— 这三类性质肉眼在出图上根本看不出来，但它们一错，墙就会变成"随机拼贴"或者"缩放时发抖"。
 */
class WallEngineTest {

    /** 120 项，比例按 3:1:1 混专辑 / 歌单 / 单曲 —— 与真实库里"专辑远多于歌单"一致。 */
    private val kinds: List<WallKind> = List(120) { i ->
        when (i % 5) {
            3 -> WallKind.PLAYLIST
            4 -> WallKind.SINGLE
            else -> WallKind.ALBUM
        }
    }

    /** 名次 = 下标：稳定，且 `rankOf(i) < quota` 的语义可以直接数出来。 */
    private fun rankOf(i: Int): Int = i

    private fun layout(zoom: Float, width: Float = 1280f, pad: Float = 26f) =
        WallLayoutEngine.build(kinds, ::rankOf, zoom, width, pad)

    // ======================== 尺度空间 ========================

    @Test
    fun `unit size grows with zoom then plateaus at the poster level`() {
        // ⚠️ 单调**非递减**，而不是严格递增：Z3（0.78）到 Z4 之间单位格**刻意持平**在 330px，
        // 那一档的放大交给沉浸层（海报铺满），网格再长只会把内容推出视口。
        var prev = 0f
        var z = 0f
        while (z <= 1f) {
            val unit = WallLayoutEngine.unitSize(z)
            assertTrue(unit >= prev - 0.001f, "zoom=$z 时单位格回缩了：$prev -> $unit")
            prev = unit
            z += 0.05f
        }
        assertEquals(15f, WallLayoutEngine.unitSize(0f))
        assertEquals(330f, WallLayoutEngine.unitSize(1f))
        // 严格增长只发生在尘埃 → 海报这一段
        assertTrue(
            WallLayoutEngine.unitSize(WallLevel.POSTER.zoom) >
                WallLayoutEngine.unitSize(WallLevel.MOSAIC.zoom),
        )
    }

    @Test
    fun `dust level collapses every tile to square`() {
        // Z0 只有 15px 的色块，表现比例只会变成噪点 —— 必须全部归一。
        val spans = WallLayoutEngine.spansFor(kinds, ::rankOf, WallLayoutEngine.DustBelow - 0.01f)
        assertTrue(spans.all { it == WallSpan.Square }, "尘埃层出现了非方形瓦片")
    }

    @Test
    fun `big tile quota grows with zoom`() {
        fun bigAt(zoom: Float) = WallLayoutEngine.spansFor(kinds, ::rankOf, zoom).count { it == WallSpan.Big }
        assertEquals(0, bigAt(WallLayoutEngine.DustBelow))
        assertTrue(bigAt(0.30f) > 0, "马赛克层应该有 2:2 大瓦片")
        assertTrue(bigAt(0.60f) > bigAt(0.30f), "大瓦片配额没有随焦距增长")
        assertEquals((kinds.size * WallLayoutEngine.BigQuota).toInt(), bigAt(1f), "配额上限不是 30%")
    }

    @Test
    fun `span assignment is stable for the same zoom`() {
        // ⚠️ 这是"呼吸而不是抽搐"的判据：同一焦距下两次分配必须逐项相同。
        val a = WallLayoutEngine.spansFor(kinds, ::rankOf, 0.42f)
        val b = WallLayoutEngine.spansFor(kinds, ::rankOf, 0.42f)
        assertEquals(a, b)
    }

    @Test
    fun `spans degrade when few columns fit`() {
        // 焦距拉大 ⇒ 列数掉到 3，此时 2 格宽的瓦片必须降级，否则一行只放一张。
        val narrowCols = WallLayoutEngine.columnsFor(1320f, 26f, WallLayoutEngine.unitSize(0.78f), 18f)
        assertTrue(narrowCols <= 4, "预期 Z3 列数很少，实际 $narrowCols")
        val spans = WallLayoutEngine.spansFor(kinds, ::rankOf, 0.78f, narrowCols)
        assertTrue(
            spans.all { it.cols <= (narrowCols / 2).coerceAtLeast(1) },
            "列数只有 $narrowCols 时仍出现了跨 2 格的瓦片：${spans.toSet()}",
        )
        // 降级不能把"高"这个特征弄丢：2:2 应当变成 1:2
        assertTrue(spans.any { it == WallSpan.Tall }, "降级后应当保留竖版瓦片")
    }

    @Test
    fun `all four ratios coexist at cover level`() {
        val spans = layout(0.60f).spans.toSet()
        WallSpan.All.forEach { span ->
            assertTrue(span in spans, "封面层缺少比例 $span（实际只有 $spans）")
        }
    }

    // ======================== 装箱 ========================

    @Test
    fun `packing never overlaps`() {
        for (zoom in listOf(0.12f, 0.30f, 0.45f, 0.60f, 0.75f, 0.95f)) {
            val rects = layout(zoom).rects
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val a = rects[i]
                    val b = rects[j]
                    val overlap = a.x < b.x + b.w - 0.01f && b.x < a.x + a.w - 0.01f &&
                        a.y < b.y + b.h - 0.01f && b.y < a.y + a.h - 0.01f
                    assertTrue(!overlap, "zoom=$zoom 时 #$i 与 #$j 重叠：$a / $b")
                }
            }
        }
    }

    @Test
    fun `packing stays inside the column count`() {
        for (zoom in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            val l = layout(zoom)
            l.rects.forEachIndexed { i, r ->
                assertTrue(
                    r.x + r.w <= l.pad + l.contentWidth + 1f,
                    "zoom=$zoom #$i 越出内容宽度：${r.x + r.w} > ${l.pad + l.contentWidth}",
                )
                assertTrue(r.slot.col >= 0 && r.slot.col + r.span.cols <= l.cols, "zoom=$zoom #$i 列越界")
            }
        }
    }

    @Test
    fun `content size matches columns rows and gaps`() {
        val l = layout(0.55f)
        assertEquals(l.cols * l.unit + (l.cols - 1) * l.gap, l.contentWidth, 0.01f)
        assertEquals(l.rows * l.unit + (l.rows - 1) * l.gap, l.contentHeight, 0.01f)
        assertTrue(l.rows >= 1)
    }

    @Test
    fun `narrow viewport keeps at least two columns`() {
        // 手机窄屏也必须能放下 2 个单位格 —— 否则 2:1 / 2:2 的瓦片会被压成 1 格，比例失效。
        listOf(320f, 360f, 420f).forEach { w ->
            val l = layout(0.30f, width = w, pad = 14f)
            assertTrue(l.cols >= 2, "宽 $w 时列数只有 ${l.cols}")
            assertTrue(l.rects.all { it.span.cols <= l.cols })
        }
    }

    @Test
    fun `repack keeps the requested spans`() {
        // FLIP 补偿依赖它：用当前单位格宽重算旧比例，逐项比例必须原样保留。
        val spans = listOf(WallSpan.Square, WallSpan.Big, WallSpan.Wide, WallSpan.Tall, WallSpan.Square)
        val rects = WallLayoutEngine.repackRects(spans, cols = 4, unitW = 100f, gap = 6f, pad = 10f)
        assertEquals(spans, rects.map { it.span })
    }

    // ======================== 焦距手感 ========================

    @Test
    fun `magnetic pulls toward the nearest level`() {
        // 0.32 离马赛克层（0.30）很近，应当被往回收
        val pulled = WallZoomMath.magnetic(0.32f)
        assertTrue(pulled in 0.30f..0.32f, "软磁吸方向错了：$pulled")
        assertTrue(pulled < 0.32f, "软磁吸没有生效")
    }

    @Test
    fun `magnetic leaves mid-gap values alone`() {
        // 0.45 离马赛克 0.15、离封面 0.10，都在磁吸半径之外 —— 用户想停在两层中间就该停得住
        assertEquals(0.45f, WallZoomMath.magnetic(0.45f))
    }

    @Test
    fun `level lookup and stepping clamp at both ends`() {
        assertEquals(WallLevel.MOSAIC, WallZoomMath.levelOf(0.31f))
        // ⚠️ stepLevel 返回的是**焦距**（Float），不是层 —— 断言要比 zoom 值。
        assertEquals(WallLevel.DUST.zoom, WallZoomMath.stepLevel(WallLevel.DUST.zoom, -1))
        assertEquals(WallLevel.IMMERSIVE.zoom, WallZoomMath.stepLevel(WallLevel.IMMERSIVE.zoom, +1))
        assertEquals(WallLevel.COVER.zoom, WallZoomMath.stepLevel(WallLevel.MOSAIC.zoom, +1))
    }

    @Test
    fun `fisheye is off at dust and immersive`() {
        assertEquals(0f, WallZoomMath.fisheyeGain(0f))
        assertEquals(0f, WallZoomMath.fisheyeGain(1f))
        assertTrue(WallZoomMath.fisheyeGain(0.40f) > 0.10f)
    }

    @Test
    fun `fisheye peaks at the focus and decays`() {
        val gain = 0.2f
        val radius = 100f
        val atFocus = WallZoomMath.fisheyeMul(0f, radius, gain)
        val atRadius = WallZoomMath.fisheyeMul(radius * radius, radius, gain)
        val far = WallZoomMath.fisheyeMul(radius * radius * 16f, radius, gain)
        assertEquals(1.2f, atFocus, 0.001f)
        assertTrue(atRadius < atFocus && far < atRadius)
        // ⚠️ 不能写 `far > 1f`：exp(-16) ≈ 1.1e-7 已经小于 Float 在 1.0 处的间隔
        // （约 1.19e-7），1f + 2e-8f 在 Float 下就等于 1f。用容差断言收敛即可。
        assertEquals(1f, far, 0.001f)
        assertEquals(1f, WallZoomMath.fisheyeMul(0f, radius, 0f), "gain=0 时不应放大")
    }

    @Test
    fun `clampPan centres small content and bounds large content`() {
        // 内容比视口小 ⇒ 居中
        assertEquals(224f, WallZoomMath.clampPan(0f, content = 500f, viewport = 1000f, pad = 26f))
        // 内容比视口大 ⇒ 夹在 [viewport - content - 2*pad, 0]
        assertEquals(0f, WallZoomMath.clampPan(500f, content = 2000f, viewport = 1000f, pad = 26f))
        assertEquals(-1052f, WallZoomMath.clampPan(-9999f, content = 2000f, viewport = 1000f, pad = 26f))
    }

    @Test
    fun `anchoredPan keeps the normalised offset under the focus`() {
        // 焦点 500、瓦片 [400, 600]、归一化偏移 0.5 ⇒ 锚定后焦点仍落在瓦片正中
        val pan = WallZoomMath.anchoredPan(focus = 500f, normalized = 0.5f, rectStart = 400f, rectSize = 200f)
        assertEquals(0f, pan, 0.001f)
        val rectLeft = 400f + pan
        assertEquals(500f, rectLeft + 0.5f * 200f, 0.001f)
    }

    // ======================== 命中测试 ========================

    @Test
    fun `hit test prefers the tile under the point`() {
        val rects = listOf(
            WallRect(0f, 0f, 100f, 100f, WallSpan.Square, WallSlot(0, 0, WallSpan.Square)),
            WallRect(106f, 0f, 100f, 100f, WallSpan.Square, WallSlot(1, 0, WallSpan.Square)),
        )
        assertEquals(0, hitTest(rects, 0f, 0f, Offset(50f, 50f)))
        assertEquals(1, hitTest(rects, 0f, 0f, Offset(150f, 50f)))
        // 落在瓦片之间的缝里 ⇒ 不算命中任何一块（宁可不响应，也不要跳错专辑）
        assertEquals(-1, hitTest(rects, 0f, 0f, Offset(103f, 50f)))
    }

    @Test
    fun `hit test accounts for pan`() {
        val rects = listOf(WallRect(0f, 0f, 100f, 100f, WallSpan.Square, WallSlot(0, 0, WallSpan.Square)))
        assertEquals(0, hitTest(rects, 200f, 300f, Offset(250f, 350f)))
    }
}
