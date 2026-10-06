package cp.player.app.ui.wall

import kotlin.math.abs
import kotlin.math.exp

/**
 * 专辑墙的**焦距数学** —— 纯计算，无 Compose 依赖。
 *
 * 墙的全部交互压在一个连续标量 `zoom ∈ [0, 1]` 上。这个文件只回答四个问题：
 * 焦距怎么吸附（软磁吸）、怎么分层（命名层）、怎么锚定（缩放时视野不跳）、
 * 怎么产生"表冠手感"（焦点鱼眼）。
 */

/**
 * 五个**命名层**。它们是"软磁吸"的落点，也是 UI 上的刻度标签。
 *
 * ⚠️ 层不是"页面"：层与层之间完全连续，命名只是为了让"无极"仍然可发现。
 */
enum class WallLevel(val zoom: Float) {
    /** 尘埃：纯色块，不加载图片。看库的规模与色彩分布。 */
    DUST(0.00f),

    /** 马赛克：封面 + 角标，同屏上百张。 */
    MOSAIC(0.30f),

    /** 封面：封面 + 标题 / 歌手，默认层。 */
    COVER(0.55f),

    /** 海报：单张竖版大卡。 */
    POSTER(0.78f),

    /** 沉浸：满屏播放器。 */
    IMMERSIVE(1.00f),
    ;

    companion object {
        val Ordered: List<WallLevel> = entries.toList()

        /** 进入沉浸层所需的焦距下限（也用于判断"再缩就退出播放器"）。 */
        const val ImmersiveFrom = 0.88f
    }
}

/** 软磁吸的生效半径与强度。 */
private const val DetentRadius = 0.045f
private const val DetentStrength = 0.42f

/**
 * 平滑阶跃。整条尺度空间里所有"某个量随焦距淡入 / 淡出"的地方都走它，
 * 避免各自手写 `lerp` 导致曲线不一致。
 */
internal fun wallSmoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 == edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

object WallZoomMath {

    /**
     * **软磁吸**：`zoom` 连续，但在四个命名层处叠一个弱弹簧。
     *
     * 这是"无极"与"可发现"的调和 —— 纯连续会让人找不到落点（永远停在差一点的构图上），
     * 纯分档又不是无极。[DetentStrength] = 0.42 意味着**最多拉回 42% 的偏移**：
     * 用户想停在两层中间，照样停得住。
     *
     * @param radius 磁吸半径。超过这个距离完全不干预。
     */
    fun magnetic(zoom: Float, radius: Float = DetentRadius): Float {
        var out = zoom
        for (level in WallLevel.Ordered) {
            val d = zoom - level.zoom
            if (abs(d) < radius) out -= d * DetentStrength * (1f - abs(d) / radius)
        }
        return out.coerceIn(0f, 1f)
    }

    /** 离 [zoom] 最近的命名层。 */
    fun levelOf(zoom: Float): WallLevel =
        WallLevel.Ordered.minBy { abs(it.zoom - zoom) }

    /** 命名层在列表里的下标（给缩放谱画刻度用）。 */
    fun levelIndex(zoom: Float): Int = levelOf(zoom).ordinal

    /** 按层步进（`[` / `]`）。到顶 / 到底时夹住，不循环。 */
    fun stepLevel(zoom: Float, delta: Int): Float {
        val next = (levelIndex(zoom) + delta).coerceIn(0, WallLevel.Ordered.lastIndex)
        return WallLevel.Ordered[next].zoom
    }

    /**
     * **焦点鱼眼增益**。
     *
     * 前两条只让整面墙一起缩放；真正"像 Apple Watch 表冠"的手感来自这一条 ——
     * 手表蜂窝网格的本质是"焦点处局部放大 + 平滑衰减"，而不是整体缩放。
     *
     * ⚠️ 两端必须为 0：
     * - Z0（尘埃）：瓦片只有 15px，做鱼眼会让整片噪点抖动；
     * - Z4（沉浸）：网格已交给播放器，再放大焦点只会干扰。
     */
    fun fisheyeGain(zoom: Float): Float =
        0.17f * wallSmoothstep(0.04f, 0.20f, zoom) * (1f - wallSmoothstep(0.80f, 0.94f, zoom))

    /**
     * 单块瓦片的放大倍率。
     *
     * 用**高斯核**而不是线性 falloff：线性在半径边界一阶不连续，
     * 缩放时会看到一圈"涟漪"扫过整面墙。
     */
    fun fisheyeMul(distanceSquared: Float, radius: Float, gain: Float): Float {
        if (gain <= 0.0001f) return 1f
        val r = if (radius <= 0f) 1f else radius
        return 1f + gain * exp(-distanceSquared / (r * r))
    }

    /** 焦点处瓦片的鱼眼半径：与瓦片尺寸挂钩，小瓦片的影响范围也小。 */
    fun fisheyeRadius(tileWidth: Float, tileHeight: Float): Float =
        minOf(tileWidth, tileHeight) * 2.05f

    /**
     * 平移夹取。
     *
     * 内容比视口小时居中；比视口大时限制在"内容边缘贴住视口边缘"的范围内，
     * 上下左右各留 [pad] 的呼吸位。
     */
    fun clampPan(pan: Float, content: Float, viewport: Float, pad: Float): Float =
        if (content + pad * 2f <= viewport) (viewport - content) / 2f - pad
        else pan.coerceIn(viewport - content - pad * 2f, 0f)

    /**
     * 焦点在瓦片内的**归一化偏移**（0..1）。缩放前后要保持的是这个值。
     */
    fun normalizedOffset(focus: Float, rectStart: Float, rectSize: Float): Float =
        if (rectSize <= 0f) 0.5f else ((focus - rectStart) / rectSize).coerceIn(0f, 1f)

    /**
     * **缩放锚定**：反解出新的 pan，使锚定瓦片上的同一个归一化位置仍落在同一个屏幕点。
     *
     * 没有这一步，缩放时焦点下的内容会往屏幕外跑 —— 而且这正是 Z3 海报
     * "从瓦片位置长出来"的机制：把锚点设在该瓦片中心，缩放到海报层时它原地不动。
     */
    fun anchoredPan(focus: Float, normalized: Float, rectStart: Float, rectSize: Float): Float =
        focus - rectStart - normalized * rectSize
}
