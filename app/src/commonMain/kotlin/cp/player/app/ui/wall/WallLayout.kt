package cp.player.app.ui.wall

/**
 * 专辑墙的**尺度空间与装箱引擎** —— 纯计算，无任何 Compose 依赖。
 *
 * 单独成文件的理由：这套算法是墙的全部数学（比例分配、天际线装箱、连续列数），
 * 而它最容易出错、也最值得被单测钉死。放进 Composable 里就只能靠出图肉眼核对，
 * 而"装箱有没有重叠""比例配额是不是单调"这类性质**肉眼根本看不出来**。
 *
 * 与原型 `docs/design/album-wall-prototype.html` 里的实现一一对应，
 * 差别只在类型与命名。
 */

/**
 * 瓦片比例，用**单位格数**表示。
 *
 * 墙是一张单位网格：单位格边长就是 [WallLayout.unit]（随焦距连续变化），
 * 瓦片占 `cols × rows` 个单位格 —— 于是比例天然就是 `cols:rows`，
 * 不需要单独维护一套"宽高比"字段。
 */
data class WallSpan(val cols: Int, val rows: Int) {
    val key: String get() = "${cols}x$rows"

    companion object {
        /** 1:1 —— 标准专辑。 */
        val Square = WallSpan(1, 1)

        /** 2:1 —— 横向构图（歌单 / 合辑 / 现场）。 */
        val Wide = WallSpan(2, 1)

        /** 1:2 —— 竖向构图（单曲 / EP / 艺人）。 */
        val Tall = WallSpan(1, 2)

        /** 2:2 —— 大瓦片，能放下完整信息 + 播放键。 */
        val Big = WallSpan(2, 2)

        /** 全部比例，供 UI 做图例 / 统计。 */
        val All = listOf(Square, Wide, Tall, Big)
    }
}

/**
 * 内容类型 —— 决定瓦片的**基础比例**。
 *
 * 比例不是装饰：`2:1` 多出来的那一格放标题 / 歌手，`1:2` 多出来的那一格放标题，
 * `2:2` 则能放下完整信息与播放键。所以类型 → 比例的映射本身就是信息层级设计，
 * 不是"随机给几个瓦片加大尺寸"。
 */
enum class WallKind(val baseSpan: WallSpan) {
    /** 专辑：1:1。 */
    ALBUM(WallSpan.Square),

    /** 歌单：2:1 横条。 */
    PLAYLIST(WallSpan.Wide),

    /** 单曲 / EP：1:2 竖条。 */
    SINGLE(WallSpan.Tall),

    /** 艺人：1:2 竖条。 */
    ARTIST(WallSpan.Tall),
}

/** 装箱结果里的一块（**单位格坐标**，与像素无关）。 */
data class WallSlot(val col: Int, val row: Int, val span: WallSpan)

/** 换算到内容坐标系后的矩形（单位 px）。 */
data class WallRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val span: WallSpan,
    val slot: WallSlot,
) {
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f

    /**
     * 点到本矩形外沿的距离平方（**落在矩形内为 0**）。
     *
     * 锚定与焦点判定都用它：只用"中心距离最近"的话，焦点落在一个 2×2 大瓦片的
     * 角落里时会被判给旁边的小瓦片 —— 用户看到的却是自己点在大瓦片上。
     */
    fun distanceSquaredTo(px: Float, py: Float): Float {
        val dx = maxOf(0f, kotlin.math.abs(px - centerX) - w / 2f)
        val dy = maxOf(0f, kotlin.math.abs(py - centerY) - h / 2f)
        return dx * dx + dy * dy
    }
}

/**
 * 一次完整的布局结果。
 *
 * [signature] 是"列数 + 比例分配"的指纹：它一变就说明需要一次**重排**
 * （FLIP 位移补偿），而不是普通的连续缩放。
 */
data class WallLayout(
    /** 单位格边长（已按可用宽度校正，见 [WallLayoutEngine.build]）。 */
    val unit: Float,
    val gap: Float,
    val cols: Int,
    val rows: Int,
    val pad: Float,
    val contentWidth: Float,
    val contentHeight: Float,
    val rects: List<WallRect>,
    val spans: List<WallSpan>,
) {
    val signature: String = cols.toString() + "|" + spans.joinToString("") { it.key }

    /** 各比例的数量，给 HUD / 调试读数用。 */
    fun mix(): Map<WallSpan, Int> = spans.groupingBy { it }.eachCount()
}

object WallLayoutEngine {

    /**
     * 单位格边长的关键帧。
     *
     * ⚠️ **必须是对数式增长，不能线性插值**。线性时 `15 → 64` 只占全长的 1/6，
     * 却要走 6 倍变化 —— 观感是"这一段看不出在缩放"，而 `150 → 330` 段又"一秒冲过去"。
     * 对数映射与音高同构，每一档的手感步长一致，这是"跟手"的数学来源。
     */
    private val SizeKeyframes = listOf(
        0.00f to 15f,   // Z0 尘埃
        0.30f to 64f,   // Z1 马赛克
        0.55f to 150f,  // Z2 封面
        0.78f to 330f,  // Z3 海报
        1.00f to 330f,  // Z4 沉浸（网格交给沉浸层接管）
    )

    /** `2:2` 大瓦片的配额上限（占全库比例）。 */
    const val BigQuota = 0.30f

    /** 大瓦片开始长出来的焦距 / 长满的焦距。 */
    const val BigFrom = 0.10f
    const val BigTo = 0.60f

    /** 低于此焦距：全部归一为 1:1（尘埃层只看色彩分布，不表现结构）。 */
    const val DustBelow = 0.10f

    /** 单位格边长。 */
    fun unitSize(zoom: Float): Float {
        val z = zoom.coerceIn(0f, 1f)
        for (i in 0 until SizeKeyframes.size - 1) {
            val (z0, s0) = SizeKeyframes[i]
            val (z1, s1) = SizeKeyframes[i + 1]
            if (z <= z1) return s0 + (s1 - s0) * wallSmoothstep(z0, z1, z)
        }
        return SizeKeyframes.last().second
    }

    /** 缝宽随单位格走，并夹在一个上下限里（太小看不出分组，太大散成独立卡片）。 */
    fun gapFor(unit: Float): Float = (unit * 0.055f).coerceIn(3f, 20f)

    /**
     * 列数 = 可用宽度能放下几个单位格。
     *
     * 返回**整数**列数，但瓦片的实际宽度由 [build] 里的 `unitW` 连续给出 ——
     * 于是缩放时瓦片宽度连续变化，只有列数跨过整数才发生一次重排。这就是"呼吸"。
     */
    fun columnsFor(viewportWidth: Float, pad: Float, unit: Float, gap: Float): Int {
        val usable = viewportWidth - pad * 2f
        if (usable <= 0f) return 2
        return ((usable + gap) / (unit + gap)).toInt().coerceAtLeast(2)
    }

    /**
     * 按焦距分配比例。
     *
     * ⚠️ 两条纪律，违反任何一条墙都会"抽":
     *
     * 1. **稳定排名 + 单调阈值**：用调用方给的 [rankOf]（按权重排好的名次）去比
     *    `rank < quota(zoom)`，保证每张专辑在一次缩放行程里**只升级一次、不回退**。
     *    写成"每帧随机"墙会一直抖；写成"接近阈值就翻转"会让某张专辑在阈值附近来回变形。
     * 2. **尘埃层全部归一**：`zoom < DustBelow` 时一律 1:1 —— 那一层只有 15px 的色块，
     *    表现比例只会变成噪点。
     */
    fun spansFor(
        kinds: List<WallKind>,
        rankOf: (Int) -> Int,
        zoom: Float,
        cols: Int = 8,
    ): List<WallSpan> {
        if (zoom < DustBelow) return List(kinds.size) { WallSpan.Square }
        // ⚠️ **比例必须受列数约束**。焦距一拉大，列数就掉到 3–4 ——
        // 此时再允许 2 格宽的瓦片，一行只放得下一张，整面墙变成"几块巨大的方块"，
        // 既是版面浪费，也让 Z3 海报背后的墙彻底失去"墙"的观感（第一版出图就是这样）。
        // 规则：一行至少要塞下两张瓦片，所以最大跨度 = cols / 2。
        val maxCols = (cols / 2).coerceAtLeast(1)
        val quota = (kinds.size * BigQuota * wallSmoothstep(BigFrom, BigTo, zoom)).toInt()
        return List(kinds.size) { i ->
            val want = if (rankOf(i) < quota) WallSpan.Big else kinds[i].baseSpan
            narrow(want, maxCols)
        }
    }

    /** 把超出 [maxCols] 的跨度降级成同类里更窄的一档（高度保持不变）。 */
    private fun narrow(span: WallSpan, maxCols: Int): WallSpan = when {
        span.cols <= maxCols -> span
        span.rows >= 2 -> WallSpan.Tall   // 2:2 降级成 1:2，保住"高"这个特征
        else -> WallSpan.Square           // 2:1 降级成 1:1
    }

    /**
     * **天际线（skyline）底左装箱**。
     *
     * `sky[x]` = 第 x 列当前的"地面高度"。为每个瓦片找一个 x，使
     * `max(sky[x .. x+w-1])` 最小（最低、最靠左），落下去并把这一段地面抬高 `h`。
     *
     * 复杂度 `O(n · cols)`：n=156、cols≈20 时约 3000 次比较 ——
     * **每次缩放都重算也毫无压力**，这正是"比例随焦距重排"能成立的前提。
     */
    fun pack(spans: List<WallSpan>, cols: Int): List<WallSlot> {
        val safeCols = cols.coerceAtLeast(1)
        val sky = IntArray(safeCols)
        val out = ArrayList<WallSlot>(spans.size)
        for (span in spans) {
            val w = span.cols.coerceAtMost(safeCols)
            var bestX = 0
            var bestY = Int.MAX_VALUE
            for (x in 0..safeCols - w) {
                var y = 0
                for (k in x until x + w) if (sky[k] > y) y = sky[k]
                if (y < bestY) {
                    bestY = y
                    bestX = x
                }
            }
            for (k in bestX until bestX + w) sky[k] = bestY + span.rows
            out += WallSlot(bestX, bestY, span)
        }
        return out
    }

    /** 内容总行数（天际线的最高点）。 */
    fun rowsOf(slots: List<WallSlot>): Int = slots.maxOfOrNull { it.row + it.span.rows } ?: 0

    /**
     * 把装箱结果换算到像素内容坐标。
     *
     * ⚠️ 瓦片宽度用 `unitW`（**校正过的**单位格宽）而不是原始 `unit`：
     * 列数是取整来的，直接用 `unit` 会留下一段永远填不满的余量。
     */
    fun toRects(slots: List<WallSlot>, unitW: Float, gap: Float, pad: Float): List<WallRect> =
        slots.map { s ->
            WallRect(
                x = pad + s.col * (unitW + gap),
                y = pad + s.row * (unitW + gap),
                w = s.span.cols * unitW + (s.span.cols - 1) * gap,
                h = s.span.rows * unitW + (s.span.rows - 1) * gap,
                span = s.span,
                slot = s,
            )
        }

    /** 一次算完：焦距 + 视口宽 → 完整布局。 */
    fun build(
        kinds: List<WallKind>,
        rankOf: (Int) -> Int,
        zoom: Float,
        viewportWidth: Float,
        pad: Float,
    ): WallLayout {
        val rawUnit = unitSize(zoom)
        val gap = gapFor(rawUnit)
        val cols = columnsFor(viewportWidth, pad, rawUnit, gap)
        val usable = (viewportWidth - pad * 2f).coerceAtLeast(rawUnit)
        val unitW = (usable - (cols - 1) * gap) / cols
        val spans = spansFor(kinds, rankOf, zoom, cols)
        val slots = pack(spans, cols)
        val rects = toRects(slots, unitW, gap, pad)
        val rows = rowsOf(slots)
        return WallLayout(
            unit = unitW,
            gap = gap,
            cols = cols,
            rows = rows,
            pad = pad,
            contentWidth = cols * unitW + (cols - 1) * gap,
            contentHeight = if (rows == 0) 0f else rows * unitW + (rows - 1) * gap,
            rects = rects,
            spans = spans,
        )
    }

    /**
     * 用**当前**的单位格宽重算一套指定比例的装箱 —— 只给 FLIP 补偿用。
     *
     * 为什么需要它：重排前后的差异必须**只含重排、不含缩放**。若拿旧布局（旧 `unitW`）
     * 直接做差，缩放本身也会被算进位移补偿里，墙会在缩放时多飘一下。
     */
    fun repackRects(
        spans: List<WallSpan>,
        cols: Int,
        unitW: Float,
        gap: Float,
        pad: Float,
    ): List<WallRect> = toRects(pack(spans, cols), unitW, gap, pad)

}
