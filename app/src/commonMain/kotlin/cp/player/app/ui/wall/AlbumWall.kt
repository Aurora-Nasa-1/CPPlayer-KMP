package cp.player.app.ui.wall

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.resized
import kotlin.math.roundToInt

/**
 * 墙上的一个条目。**刻意不直接用 `AlbumSummary`** ——
 * 墙同时要放专辑 / 歌单 / 艺人，用领域模型会逼出一个 `Any` 字段；
 * 这里只保留"画一块瓦片"真正需要的四样东西，映射由 ScreenModel 负责。
 */
@Immutable
data class WallItem(
    /** 全局唯一（`album:123` / `playlist:45`），用于 FLIP 与 key。 */
    val id: String,
    val title: String,
    val subtitle: String?,
    val coverUrl: String?,
    val kind: WallKind,
    /** 权重：越大越容易长成 `2:2` 大瓦片。 */
    val weight: Int,
    /**
     * 领域模型的原始 id（专辑 / 歌单）。`null` 表示这一项暂时没有可跳转的落点 ——
     * **不要**从 [id] 里解析，那个前缀是给 key 与 FLIP 用的显示无关标识。
     */
    val sourceId: Long? = null,
)

/**
 * 海报的**出生矩形**（视口坐标，px）。
 *
 * ⚠️ 必须是"打开海报那一刻"快照下来的，不能用实时布局里的瓦片矩形：
 * 从 Z2 到 Z3，单位格会从 150px 长到 330px，`2:2` 的瓦片随之从 ~330px 变成 ~678px，
 * 并且整面墙会重排、平移也会被重新锚定 —— 那时再去读"瓦片矩形"，
 * 拿到的是一个**巨大的、多半已经跑出屏幕的方块**，海报会从屏幕外撑开
 * （第一版出图就是这么错的：海报铺满整屏且标题落在左下角）。
 */
data class PosterOrigin(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * 墙的相机状态（焦距 + 平移 + 焦点 + 当前海报）。
 *
 * 用普通类而不是 `remember` 一堆散装 state：焦距、平移、焦点三者**必须一起被改写**
 * （缩放锚定要同时反解 pan），拆成三个独立 state 会让"谁在什么时候改了它"变得不可追踪。
 */
@Stable
class WallState(initialZoom: Float = WallLevel.MOSAIC.zoom) {

    /** 连续焦距 `∈ [0, 1]`。整面墙都由它驱动。 */
    var zoom by mutableFloatStateOf(initialZoom)

    /** 画布平移（内容坐标 → 视口坐标的偏移，单位 px）。 */
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

    /**
     * 画布最近一次算出的布局。给 HUD 读比例配比用。
     *
     * 由 `AlbumWall` 回填，而不是让 HUD 自己再算一遍 —— 两处各算一份迟早会因为
     * 视口宽 / pad 取值不同而对不上，读数和画面说的不是同一件事。
     */
    var layout by mutableStateOf<WallLayout?>(null)

    fun panBy(delta: Offset) {
        panX += delta.x
        panY += delta.y
    }

    /** 焦点（视口坐标）。触摸端没有 hover，用视口中心偏上一点，视觉重心更稳。 */
    fun effectiveFocusX(): Float = if (focusLive) focusX else viewportWidth / 2f
    fun effectiveFocusY(): Float = if (focusLive) focusY else viewportHeight * 0.46f
}

/**
 * 专辑墙画布。
 *
 * ## 三件事都在这里发生
 *
 * 1. **连续缩放**：`state.zoom` 是唯一输入，[WallLayoutEngine] 把它变成单位格边长、
 *    列数、比例分配与装箱结果 —— 全程没有"档位"，档位只是缩放谱上的软磁吸刻度。
 * 2. **焦点鱼眼**：焦点下的瓦片按高斯核放大（[WallZoomMath.fisheyeMul]），
 *    这是"表冠手感"的真正来源，而不是整体缩放。
 * 3. **重排（呼吸）**：列数或比例分配一变，就用 FLIP 位移补偿让瓦片**滑**到新位置。
 *
 * ## 渲染方式为什么是 graphicsLayer 而不是重新测量
 *
 * 瓦片用固定的"基座尺寸"（`100dp × 单位格数`）参与测量，缩放全部交给
 * [graphicsLayer] 的 `scaleX/scaleY` —— 于是**缩放与平移都不触发 measure**，
 * 只有比例变化（`1:1` → `2:2`）才改基座尺寸。这与 `CoverFlight` 里飞行器的做法一致。
 *
 * ⚠️ 基座是 `100dp × 单位格数`，而实际瓦片宽是 `unitW`（按列数校正过的），
 * 所以 `scaleX` 与 `scaleY` 会因缝宽修正略有差异（<2%）。这点非等比缩放的文字拉伸
 * 肉眼不可见，换来的是"缩放只走 transform"。**不要为了消除它改成逐帧改 width/height。**
 */
@Composable
fun AlbumWall(
    items: List<WallItem>,
    state: WallState,
    onOpenPoster: (WallItem) -> Unit,
    onClosePoster: () -> Unit,
    onPlay: (WallItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        val pad = with(density) { (if (maxWidth < 820.dp) 14.dp else 26.dp).toPx() }

        LaunchedEffect(viewportW, viewportH) {
            state.viewportWidth = viewportW.roundToInt()
            state.viewportHeight = viewportH.roundToInt()
        }

        val kinds = remember(items) { items.map { it.kind } }
        // 名次只算一次：它是"谁能长成 2×2"的依据，必须稳定，不能每帧重排
        val rankOf = remember(items) {
            val order = items.indices.sortedByDescending { items[it].weight }
            val rank = IntArray(items.size)
            order.forEachIndexed { r, i -> rank[i] = r }
            // ⚠️ 必须显式声明类型、并另起一行返回：块的最后一句若是裸 lambda，
            // Kotlin 会把它解析成**上一句调用的尾随 lambda**（报 "Expression is treated as
            // a trailing lambda argument"），于是整个 remember 块的类型变成 Unit，
            // 下游所有 build(...) 立刻报"期望 (Int) -> Int，实际是 Unit"。
            val lookup: (Int) -> Int = { i -> rank.getOrElse(i) { Int.MAX_VALUE } }
            lookup
        }

        val layout = remember(items, state.zoom, viewportW, pad) {
            WallLayoutEngine.build(kinds, rankOf, state.zoom, viewportW, pad)
        }
        // 回填给 HUD 读数用（key 在 layout 上，每次布局变化只写一次）
        LaunchedEffect(layout) { state.layout = layout }

        // ⚠️ **渲染一律用夹取后的平移，而不是 `state.panX/panY`。**
        // 存的是"用户意图"，读的时候夹一次 —— 这样**首帧就是对的**。
        // 反例：把夹取只放在 LaunchedEffect 里，内容小于视口时首帧会顶在左上角，
        // 下一帧才跳到居中（出图里就是这么暴露的：Z0 的 84 张瓦片全挤在顶部一行）。
        val panX = WallZoomMath.clampPan(state.panX, layout.contentWidth, viewportW, pad)
        val panY = WallZoomMath.clampPan(state.panY, layout.contentHeight, viewportH, pad)

        WallTiles(
            items = items,
            layout = layout,
            state = state,
            panX = panX,
            panY = panY,
            viewportW = viewportW,
            viewportH = viewportH,
        )

        WallPosterOverlay(
            items = items,
            layout = layout,
            state = state,
            panX = panX,
            panY = panY,
            viewportW = viewportW,
            viewportH = viewportH,
            onPlay = onPlay,
            onClose = onClosePoster,
        )

        // 缩放 + **锚定**：让焦点下的那块瓦片在缩放前后停在同一个屏幕位置。
        // 没有这一步，缩放时焦点下的内容会往屏幕外跑；而 Z3 海报"从瓦片位置长出来"
        // 也正是靠它 —— 把锚点设在该瓦片中心，缩放到海报层时它原地不动。
        val applyZoom: (Float, Float, Float) -> Unit = { targetZoom, focusX, focusY ->
            if (targetZoom != state.zoom) {
                val anchor = layout.rects.indices.minByOrNull { i ->
                    layout.rects[i].distanceSquaredTo(focusX - panX, focusY - panY)
                }
                if (anchor == null) {
                    state.zoom = targetZoom
                } else {
                    val before = layout.rects[anchor]
                    val offX = WallZoomMath.normalizedOffset(focusX - panX, before.x, before.w)
                    val offY = WallZoomMath.normalizedOffset(focusY - panY, before.y, before.h)
                    state.zoom = targetZoom
                    // 用新焦距重算一次装箱，才能拿到"锚定项在新布局里的矩形"。
                    // 比例分配若同时变了也没关系 —— FLIP 会在 WallTiles 里补偿其余瓦片。
                    val next = WallLayoutEngine.build(kinds, rankOf, targetZoom, viewportW, pad)
                    next.rects.getOrNull(anchor)?.let { after ->
                        state.panX = WallZoomMath.clampPan(
                            WallZoomMath.anchoredPan(focusX, offX, after.x, after.w),
                            next.contentWidth, viewportW, pad,
                        )
                        state.panY = WallZoomMath.clampPan(
                            WallZoomMath.anchoredPan(focusY, offY, after.y, after.h),
                            next.contentHeight, viewportH, pad,
                        )
                    }
                }
            }
        }

        // ── 交互：点击 / 拖拽平移 / 捏合缩放 / 滚轮缩放 ──
        // 点击放在画布上而不是每块瓦片各自 clickable：命中判定要读布局（含鱼眼放大后的
        // 实际矩形），只有画布这一层同时拿得到布局与指针位置。
        Box(
            Modifier
                .fillMaxSize()
                .wallPointerZoom(
                    onZoomFactor = { factor, pos ->
                        applyZoom(WallZoomMath.magnetic((state.zoom * factor).coerceIn(0f, 1f)), pos.x, pos.y)
                    },
                    onHover = { pos ->
                        state.focusX = pos.x
                        state.focusY = pos.y
                        state.focusLive = true
                    },
                )
                .pointerInput(items, layout) {
                    detectTapGestures { pos ->
                        val hit = hitTest(layout.rects, panX, panY, pos)
                        if (hit >= 0) {
                            items.getOrNull(hit)?.let { item ->
                                // 锚在瓦片**中心**（而不是点击点）：海报要"从这块瓦片里长出来"，
                                // 锚在点击点会让它从瓦片的某个角落撑开，观感是歪的。
                                val rect = layout.rects[hit]
                                // 先快照瓦片**当前**的屏幕矩形（海报要从这里长出来），
                                // 再缩放 —— 顺序反了拿到的是缩放后那个巨大的方块。
                                state.posterFrom = PosterOrigin(
                                    x = rect.x + panX,
                                    y = rect.y + panY,
                                    w = rect.w,
                                    h = rect.h,
                                )
                                applyZoom(
                                    maxOf(state.zoom, WallLevel.POSTER.zoom),
                                    rect.centerX + panX,
                                    rect.centerY + panY,
                                )
                                state.posterId = item.id
                                onOpenPoster(item)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        if (pan != Offset.Zero) {
                            state.panBy(pan)
                            state.panX = WallZoomMath.clampPan(state.panX, layout.contentWidth, size.width.toFloat(), layout.pad)
                            state.panY = WallZoomMath.clampPan(state.panY, layout.contentHeight, size.height.toFloat(), layout.pad)
                        }
                        if (gestureZoom != 1f) {
                            applyZoom(WallZoomMath.magnetic((state.zoom * gestureZoom).coerceIn(0f, 1f)), centroid.x, centroid.y)
                        }
                    }
                }
        )
    }
}

/** 只有可见范围内的瓦片才参与组合；配合 `graphicsLayer` 缩放，屏外一律跳过。 */
@Composable
private fun WallTiles(
    items: List<WallItem>,
    layout: WallLayout,
    state: WallState,
    panX: Float,
    panY: Float,
    viewportW: Float,
    viewportH: Float,
) {
    val density = LocalDensity.current
    val gain = WallZoomMath.fisheyeGain(state.zoom)
    val focusX = state.effectiveFocusX()
    val focusY = state.effectiveFocusY()

    // ── 重排的 FLIP 补偿 ──
    // 用"当前单位格宽"重算旧比例的装箱，两者之差才是**纯粹的重排位移**（不含缩放）。
    val flips = remember { mutableStateMapOf<String, Offset>() }
    val reflow = remember { Animatable(0f) }
    var prevSpans by remember { mutableStateOf<List<WallSpan>?>(null) }
    var prevCols by remember { mutableIntStateOf(0) }

    LaunchedEffect(layout.signature) {
        val ps = prevSpans
        if (ps != null && prevCols > 0 && (prevCols != layout.cols || ps.size != layout.spans.size)) {
            val before = WallLayoutEngine.repackRects(ps, prevCols, layout.unit, layout.gap, layout.pad)
            flips.clear()
            layout.rects.forEachIndexed { i, r ->
                val b = before.getOrNull(i) ?: return@forEachIndexed
                if (b.x != r.x || b.y != r.y) flips[items[i].id] = Offset(b.x - r.x, b.y - r.y)
            }
            if (flips.isNotEmpty()) {
                reflow.snapTo(1f)
                reflow.animateTo(0f, tween(durationMillis = 550, easing = LinearEasing))
            }
        }
        prevSpans = layout.spans
        prevCols = layout.cols
    }

    val progress = reflow.value
    val easeOut = progress * progress

    items.forEachIndexed { index, item ->
        val rect = layout.rects.getOrNull(index) ?: return@forEachIndexed

        val centerX = rect.x + panX + rect.w / 2f
        val centerY = rect.y + panY + rect.h / 2f
        val dx = centerX - focusX
        val dy = centerY - focusY
        val mul = WallZoomMath.fisheyeMul(
            distanceSquared = dx * dx + dy * dy,
            radius = WallZoomMath.fisheyeRadius(rect.w, rect.h),
            gain = gain,
        )
        val gw = rect.w * mul
        val gh = rect.h * mul

        val flip = flips[item.id]
        val ox = (flip?.x ?: 0f) * easeOut
        val oy = (flip?.y ?: 0f) * easeOut

        val left = rect.x + panX - (gw - rect.w) / 2f + ox
        val top = rect.y + panY - (gh - rect.h) / 2f + oy

        // 视口裁切（留 90px 余量，避免边缘瓦片弹出）
        val visible = left <= viewportW + 90f && top <= viewportH + 90f &&
            left + gw >= -90f && top + gh >= -90f
        if (!visible) return@forEachIndexed

        val baseW = (100 * rect.span.cols).dp
        val baseH = (100 * rect.span.rows).dp
        val baseWpx = with(density) { baseW.toPx() }
        val baseHpx = with(density) { baseH.toPx() }

        key(item.id) {
            WallTile(
                item = item,
                span = rect.span,
                zoom = state.zoom,
                isPlaying = state.playingId == item.id,
                modifier = Modifier
                    .offsetPx(left, top)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = gw / baseWpx
                        scaleY = gh / baseHpx
                    }
                    .size(baseW, baseH),
            )
        }
    }
}

/** `Modifier.offset` 的 px 版本；读的是布局阶段的值，不会触发重组。 */
private fun Modifier.offsetPx(x: Float, y: Float): Modifier =
    this.then(
        Modifier.offset {
            IntOffset(x.roundToInt(), y.roundToInt())
        },
    )

/**
 * 一块瓦片。
 *
 * **比例决定信息层级**（这是变比例瓦片存在的理由，不是装饰）：
 * - `1:1` 只有封面，Z1 时叠一行角标；
 * - `2:1` 左方封面 + 右侧标题 / 歌手；
 * - `1:2` 上方封面 + 下方标题 / 歌手；
 * - `2:2` 封面铺满 + 底部渐变上的标题 / 歌手 + 播放键。
 */
@Composable
private fun WallTile(
    item: WallItem,
    span: WallSpan,
    zoom: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val square = span == WallSpan.Square
    val corner = 18.dp
    val coverSize = coverRequestSize(zoom)

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (square) {
            Box(Modifier.fillMaxSize()) {
                WallCover(item.coverUrl, coverSize, Modifier.fillMaxSize())
                // Z1 角标：只在马赛克层出现，靠近 Z2 时让位给下方标题
                val tagAlpha = wallSmoothstep(0.22f, 0.34f, zoom) * (1f - wallSmoothstep(0.60f, 0.72f, zoom))
                if (tagAlpha > 0.01f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.42f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.62f * tagAlpha),
                                ),
                            ),
                    )
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(7.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            item.title,
                            color = Color.White.copy(alpha = tagAlpha),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            // 变比例瓦片：封面占一个单位格，多出来的那一格放信息
            if (span == WallSpan.Big) {
                Box(Modifier.fillMaxSize()) {
                    WallCover(item.coverUrl, coverSize, Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.42f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.84f),
                                ),
                            ),
                    )
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    TileMeta(item, Color.White, 13.sp, 10.5.sp)
                }
            } else {
                val horizontal = span == WallSpan.Wide
                val container = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerLow
                Box(Modifier.fillMaxSize().background(container)) {
                    Row(
                        Modifier.fillMaxSize(),
                    ) {
                        if (horizontal) {
                            WallCover(item.coverUrl, coverSize, Modifier.size(100.dp))
                            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                                TileMeta(item, MaterialTheme.colorScheme.onSurface, 12.5.sp, 10.5.sp)
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                WallCover(item.coverUrl, coverSize, Modifier.size(100.dp))
                                Box(Modifier.fillMaxSize().padding(11.dp), contentAlignment = Alignment.TopStart) {
                                    TileMeta(item, MaterialTheme.colorScheme.onSurface, 12.sp, 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isPlaying) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(corner))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            )
        }
    }
}

@Composable
private fun TileMeta(item: WallItem, color: Color, titleSize: TextUnit, subSize: TextUnit) {
    Column {
        Text(
            item.title,
            color = color,
            fontSize = titleSize,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                color = color.copy(alpha = 0.72f),
                fontSize = subSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WallCover(url: String?, requestSize: Int, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url.resized(requestSize),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 按焦距分级取图。
 *
 * ⚠️ 这里**只分级、不省图**：Z0 仍会请求 96px 的缩略图。
 * 「Z0 完全不加载图片、改用 `CoverSeedCache` 的主色画色块」是 P3 的优化 ——
 * 那条路要先解决 `CoverColor.kt` 里"缓存无锁、靠调用方串行"的约束（见方案 §9.4），
 * 不能在墙里随手调。
 */
private fun coverRequestSize(zoom: Float): Int = when {
    zoom < 0.20f -> 96
    zoom < 0.45f -> 200
    zoom < 0.68f -> 400
    else -> 800
}
