package cp.player.app.ui.wall

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 墙上的一个条目。
 *
 * **刻意不直接用 `TrackSummary` / `AlbumSummary`** —— 墙同时要放专辑、歌曲、本地文件，
 * 用领域模型会逼出一个 `Any` 字段；这里只保留"画一块瓦片"真正需要的六样东西，
 * 映射由 ScreenModel 负责。
 */
@Immutable
data class WallItem(
    /** 全局唯一（`album:123` / `song:456` / `local:/path`），用于 key、去重与 FLIP。 */
    val id: String,
    val title: String,
    val subtitle: String?,
    val coverUrl: String?,
    val kind: WallKind,
    /** 权重：越大越容易长成 `2:2` 大瓦片。 */
    val weight: Int,
    /**
     * 领域模型的原始 id / 路径。`null` 表示这一项暂时没有可跳转的落点 ——
     * **不要**从 [id] 里解析，那个前缀是给 key 与去重用的、与显示无关的标识。
     */
    val sourceId: String? = null,
)

/**
 * 海报的**出生矩形**（视口坐标，px）。
 *
 * ⚠️ 必须是"打开海报那一刻"快照下来的，不能用实时布局里的瓦片矩形：
 * 从 Z2 到 Z3，单位格会从 150px 长到 330px，`2:2` 的瓦片随之从 ~330px 变成 ~678px，
 * 并且整面墙会重排、平移也会被重新锚定 —— 那时再去读"瓦片矩形"，
 * 拿到的是一个**巨大的、多半已经跑出屏幕的方块**，海报会从屏幕外撑开。
 */
@Immutable
data class PosterOrigin(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * 墙的相机状态（焦距 + 平移 + 焦点 + 当前海报）。
 *
 * ## 为什么把 [zoomTo] 放在这里，而不是留在画布里
 *
 * 墙有**五个**缩放入口：滚轮、双指捏合、表冠、缩放谱、按层步进。它们必须产生
 * **完全一样**的手感 —— 都要"锚定焦点"（焦点下的瓦片缩放前后停在同一个屏幕位置）。
 *
 * 第一版把带锚定的缩放写成了 `AlbumWall` 里的局部函数，于是：
 * - 表冠与缩放谱只能直接改 `zoom`（拿不到锚定逻辑）⇒ 画面往左上角漂；
 * - 滚轮/捏合走的是 `pointerInput(Unit)`，那个 lambda **捕获了首次组合时的 layout**
 *   ⇒ 缩放一次之后锚定就基于过期布局，位置开始错乱。
 *
 * 把"当前布局 + 布局输入"挂到状态上、把锚定收成一个方法，两个问题一起消失：
 * 任何入口调 [zoomTo] 都拿到**当下**的布局。
 */
@Stable
class WallState(initialZoom: Float = WallLevel.MOSAIC.zoom) {

    /** 连续焦距 `∈ [0, 1]`。整面墙都由它驱动。 */
    var zoom by mutableFloatStateOf(initialZoom)

    /** 画布平移（内容坐标 → 视口坐标的偏移，单位 px）。**存的是意图，读的时候夹取。** */
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    /** 焦点（视口坐标 px）。桌面跟指针，触摸端保持 false 时用视口中心。 */
    var focusX by mutableFloatStateOf(0f)
    var focusY by mutableFloatStateOf(0f)
    var focusLive by mutableStateOf(false)

    /** 当前展开成海报的那一项（Z3）。 */
    var posterId by mutableStateOf<String?>(null)

    /** 海报的出生矩形。由点击处理在**缩放之前**写入，见 [PosterOrigin]。 */
    var posterFrom by mutableStateOf<PosterOrigin?>(null)

    /** 正在播放的那一项（画一圈强调环）。 */
    var playingId by mutableStateOf<String?>(null)

    /** 视口尺寸（px），由画布回填。 */
    var viewportWidth by mutableIntStateOf(0)
    var viewportHeight by mutableIntStateOf(0)

    /** 画布最近一次算出的布局。给 [zoomTo] 与 HUD 读数用。 */
    var layout by mutableStateOf<WallLayout?>(null)

    /** 布局输入。由画布回填 —— [zoomTo] 要能自己重算一份新焦距下的装箱。 */
    internal var kinds: List<WallKind> = emptyList()
    internal var rankOf: (Int) -> Int = { Int.MAX_VALUE }

    fun panBy(deltaX: Float, deltaY: Float) {
        panX += deltaX
        panY += deltaY
    }

    /** 夹取后的平移 —— **渲染与命中判定一律用它**，不用 [panX] / [panY]。 */
    fun clampedPanX(): Float {
        val l = layout ?: return panX
        return WallZoomMath.clampPan(panX, l.contentWidth, viewportWidth.toFloat(), l.pad)
    }

    fun clampedPanY(): Float {
        val l = layout ?: return panY
        return WallZoomMath.clampPan(panY, l.contentHeight, viewportHeight.toFloat(), l.pad)
    }

    /** 焦点（视口坐标）。触摸端没有 hover，用视口中心偏上一点，视觉重心更稳。 */
    fun effectiveFocusX(): Float = if (focusLive) focusX else viewportWidth / 2f

    fun effectiveFocusY(): Float = if (focusLive) focusY else viewportHeight * 0.46f

    /**
     * **带锚定的缩放** —— 墙唯一的缩放入口，所有手势与控件都必须走它。
     *
     * 锚定 = 让焦点下的那块瓦片在缩放前后停在同一个屏幕位置。没有它，缩放时内容会
     * 往一个方向持续漂；而 Z3 海报"从瓦片位置长出来"也正是靠它。
     */
    fun zoomTo(targetZoom: Float, focusX: Float, focusY: Float) {
        val z = targetZoom.coerceIn(0f, 1f)
        val current = layout
        if (current == null || current.rects.isEmpty()) {
            zoom = z
            return
        }
        if (z == zoom) return

        val vw = viewportWidth.toFloat().coerceAtLeast(1f)
        val vh = viewportHeight.toFloat().coerceAtLeast(1f)
        val px = clampedPanX()
        val py = clampedPanY()

        // 焦点落在哪个矩形里就锚哪个（先判包含，再退化为最近中心）
        val anchor = current.rects.indices.minByOrNull { i ->
            current.rects[i].distanceSquaredTo(focusX - px, focusY - py)
        }
        if (anchor == null) {
            zoom = z
            return
        }
        val before = current.rects[anchor]
        val offX = WallZoomMath.normalizedOffset(focusX - px, before.x, before.w)
        val offY = WallZoomMath.normalizedOffset(focusY - py, before.y, before.h)

        zoom = z

        val next = WallLayoutEngine.build(kinds, rankOf, z, vw, current.pad)
        val after = next.rects.getOrNull(anchor) ?: return
        panX = WallZoomMath.clampPan(
            WallZoomMath.anchoredPan(focusX, offX, after.x, after.w),
            next.contentWidth, vw, current.pad,
        )
        panY = WallZoomMath.clampPan(
            WallZoomMath.anchoredPan(focusY, offY, after.y, after.h),
            next.contentHeight, vh, current.pad,
        )
    }

    /** 以视口中心为焦点缩放。表冠 / 缩放谱 / 层步进都走这个。 */
    fun zoomToCentre(targetZoom: Float) = zoomTo(targetZoom, effectiveFocusX(), effectiveFocusY())
}
