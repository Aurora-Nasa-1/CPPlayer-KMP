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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.resized
import kotlin.math.roundToInt

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
 * 只有比例变化（`1:1` → `2:2`）才改基座尺寸。
 *
 * ⚠️ 基座是 `100dp × 单位格数`，而实际瓦片宽是 `unitW`（按列数校正过的），
 * 所以 `scaleX` 与 `scaleY` 会因缝宽修正略有差异（<2%）。这点非等比缩放的文字拉伸
 * 肉眼不可见，换来的是"缩放只走 transform"。**不要为了消除它改成逐帧改 width/height。**
 *
 * ⚠️ 本函数**只负责呈现**：所有缩放都调 `state.zoomTo(...)`（内含锚定），
 * 平移只写 `state.panBy(...)`（不夹取，夹取在读的时候做）。这样
 * 手势 lambda 不需要捕获任何随组合变化的值，也就不会出现"捕获了过期布局"的老问题。
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
            // Kotlin 会把它解析成**上一句调用的尾随 lambda**，整个 remember 块的类型
            // 会变成 Unit，下游 build(...) 立刻报"期望 (Int) -> Int，实际是 Unit"。
            val lookup: (Int) -> Int = { i -> rank.getOrElse(i) { Int.MAX_VALUE } }
            lookup
        }

        val layout = remember(items, state.zoom, viewportW, pad) {
            WallLayoutEngine.build(kinds, rankOf, state.zoom, viewportW, pad)
        }

        // 把布局与它的输入回填给状态：`state.zoomTo` 要用它们做锚定重算，HUD 要用它读数。
        // ⚠️ 必须**同步**回填（不能只放 LaunchedEffect）：zoomTo 可能紧接着在同一次手势里
        // 被调用，晚一帧回填就会拿旧布局锚定 —— 那正是旧版位置错乱的根因。
        state.layout = layout
        state.kinds = kinds
        state.rankOf = rankOf

        val panX = state.clampedPanX()
        val panY = state.clampedPanY()

        WallTiles(
            items = items,
            layout = layout,
            state = state,
            panX = panX,
            panY = panY,
            viewportW = viewportW,
            viewportH = viewportH,
        )

        // ── 交互 ──
        // 点击放在画布上而不是每块瓦片各自 clickable：命中判定要读布局（含鱼眼放大后的
        // 实际矩形），只有画布这一层同时拿得到布局与指针位置。
        Box(
            Modifier
                .fillMaxSize()
                .wallPointerZoom(
                    onZoomFactor = { factor, pos ->
                        state.zoomTo(
                            WallZoomMath.magnetic((state.zoom * factor).coerceIn(0f, 1f)),
                            pos.x, pos.y,
                        )
                    },
                    onHover = { pos ->
                        state.focusX = pos.x
                        state.focusY = pos.y
                        state.focusLive = true
                    },
                )
                .pointerInput(items, layout.signature, state.posterId) {
                    detectTapGestures { pos ->
                        // ⚠️ 海报打开时画布**完全不响应**。少了这一条，点海报外的遮罩会
                        // 穿透到画布、命中底下那张瓦片，"关海报"就变成"又开一张海报"。
                        if (state.posterId != null) return@detectTapGestures
                        val hit = hitTest(layout.rects, panX, panY, pos)
                        if (hit < 0) return@detectTapGestures
                        val item = items.getOrNull(hit) ?: return@detectTapGestures
                        val rect = layout.rects[hit]
                        // 先快照瓦片**当前**的屏幕矩形（海报要从这里长出来），再缩放 ——
                        // 顺序反了拿到的是缩放后那个巨大的方块。
                        state.posterFrom = PosterOrigin(
                            x = rect.x + panX,
                            y = rect.y + panY,
                            w = rect.w,
                            h = rect.h,
                        )
                        // 锚在瓦片**中心**（而不是点击点）：海报要"从这块瓦片里长出来"，
                        // 锚在点击点会让它从瓦片的某个角落撑开，观感是歪的。
                        state.zoomTo(
                            maxOf(state.zoom, WallLevel.POSTER.zoom),
                            rect.centerX + panX,
                            rect.centerY + panY,
                        )
                        state.posterId = item.id
                        onOpenPoster(item)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        // 平移只记意图、不在这里夹取 —— 夹取在渲染与命中判定处统一做，
                        // 免得这里又要捕获 layout（那正是旧版锚定错乱的根因）。
                        if (pan != Offset.Zero) state.panBy(pan.x, pan.y)
                        if (gestureZoom != 1f) {
                            state.zoomTo(
                                WallZoomMath.magnetic((state.zoom * gestureZoom).coerceIn(0f, 1f)),
                                centroid.x, centroid.y,
                            )
                        }
                    }
                }
        )

        // ⚠️ 海报必须放在**交互层之上**。交互 Box 是 fillMaxSize 的：它若压在海报上面，
        // 海报里的播放键 / 关闭键就永远点不到 —— 事件先落到交互层，而交互层一见到
        // `posterId` 非空就提前返回。这个顺序是"海报能用"的必要条件，不要调换。
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
        // ⚠️ 判据必须是 `ps != layout.spans`（逐项比较比例），**不能**写
        // `ps.size != layout.spans.size` —— 两者尺寸永远相等，那个条件恒为 false，
        // 于是"列数没变、只有比例配额涨落"的重排完全不会补位移，整面墙是"跳"过去的。
        if (ps != null && prevCols > 0 && (prevCols != layout.cols || ps != layout.spans)) {
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

    val easeOut = reflow.value * reflow.value

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
                // ⚠️ 按**实际渲染像素**取图，而不是按焦距分档。
                // 焦距分档在沉浸层会失准：Z4 时瓦片被拉到 700px+，却仍只请求 800 的图，
                // 一放大就糊。按 gw/gh 分档在任何焦距下都刚好够用。
                coverSize = wallCoverRequestSize(maxOf(gw, gh)),
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
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

/**
 * 一块瓦片。
 *
 * **比例决定信息层级**（这是变比例瓦片存在的理由，不是装饰）：
 * - `1:1` 专辑：只有封面，Z1 时叠一行角标；
 * - `2:1` 歌曲：左方封面 + 右侧歌名 / 歌手（歌曲本来就是"一行"信息）；
 * - `1:2` 本地：上方封面 + 下方两行文件名（本地文件普遍封面缺失、名字很长）；
 * - `2:2` 大瓦片：封面铺满 + 底部渐变上的标题 / 歌手 + 播放键。
 */
@Composable
internal fun WallTile(
    item: WallItem,
    span: WallSpan,
    zoom: Float,
    isPlaying: Boolean,
    /** 取图边长（px）。由调用方按**瓦片实际渲染尺寸**给出，见 [wallCoverRequestSize]。 */
    coverSize: Int,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val corner = 18.dp

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(
                if (dark) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        when (span) {
            WallSpan.Square -> {
                Box(Modifier.fillMaxSize()) {
                    WallCover(item.coverUrl, coverSize, Modifier.fillMaxSize())
                    // Z1 角标：只在马赛克层出现，靠近 Z2 时让位
                    val tagAlpha = wallSmoothstep(0.22f, 0.34f, zoom) *
                        (1f - wallSmoothstep(0.60f, 0.72f, zoom))
                    if (tagAlpha > 0.01f) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0.42f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.62f * tagAlpha),
                                ),
                            ),
                        )
                        Column(
                            Modifier.fillMaxSize().padding(7.dp),
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
            }

            WallSpan.Big -> {
                Box(Modifier.fillMaxSize()) {
                    WallCover(item.coverUrl, coverSize, Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.42f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.84f),
                            ),
                        ),
                    )
                }
                Column(
                    Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    TileMeta(item, Color.White, 13.sp, 10.5.sp)
                }
            }

            WallSpan.Wide -> {
                // 2:1：封面占左半（一个单位格），右边放歌名 / 歌手
                Row(Modifier.fillMaxSize()) {
                    WallCover(item.coverUrl, coverSize, Modifier.size(100.dp))
                    Box(
                        Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        TileMeta(item, MaterialTheme.colorScheme.onSurface, 12.5.sp, 10.5.sp)
                    }
                }
            }

            WallSpan.Tall -> {
                // 1:2：封面在上，下方两行留给长文件名
                Column(Modifier.fillMaxSize()) {
                    WallCover(item.coverUrl, coverSize, Modifier.size(100.dp))
                    Box(
                        Modifier.fillMaxSize().padding(11.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        TileMeta(item, MaterialTheme.colorScheme.onSurface, 12.sp, 10.sp)
                    }
                }
            }
        }

        if (isPlaying) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(corner))
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
 * 取图边长：按**目标渲染像素**分档。
 *
 * ⚠️ 分档（而不是取任意值）是为了让 Coil 的缓存键收敛 —— 连续缩放时如果每帧都算一个
 * 新尺寸，缓存会瞬间被冲垮。这几档覆盖了从尘埃层到沉浸层的全部实际尺寸。
 *
 * ⚠️ 上限 1440：再大对封面没有意义（原图多数也就 500–1300），只是白烧流量。
 *
 * 「Z0 完全不加载图片、改用 `CoverSeedCache` 的主色画色块」是后续优化 ——
 * 那条路要先解决 `CoverColor.kt` 里"缓存无锁、靠调用方串行"的约束（见方案 §9.4），
 * 不能在墙里随手调。
 */
internal fun wallCoverRequestSize(targetPx: Float): Int = when {
    targetPx <= 0f -> 128
    targetPx < 128f -> 128
    targetPx < 256f -> 256
    targetPx < 512f -> 512
    targetPx < 1024f -> 1024
    else -> 1440
}
