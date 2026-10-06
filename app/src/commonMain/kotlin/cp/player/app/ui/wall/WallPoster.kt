package cp.player.app.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.resized
import kotlin.math.roundToInt

/**
 * Z3 海报层。
 *
 * ## 为什么不是弹窗
 *
 * 参考图里的"音乐海报"是一个居中弹窗 + 遮罩。这里**刻意不这么做** ——
 * 弹窗意味着"墙被盖住了"，而墙模式的核心主张是"从来没有离开过墙"。
 *
 * 做法是让海报矩形**从焦点瓦片的屏幕矩形连续插值到目标海报矩形**，
 * 瓦片自身同步淡出（避免"两层封面"），遮罩透明度也由同一个连续量驱动。
 * 于是海报是"墙在某个焦距上的样子"，而不是"盖在墙上的另一层 UI"。
 *
 * 缩小回 Z2 以下，海报自然消失（由调用方清 `posterId`）。
 */
@Composable
internal fun WallPosterOverlay(
    items: List<WallItem>,
    layout: WallLayout,
    state: WallState,
    /** 夹取后的平移。**渲染一律用它**，理由见 `AlbumWall` 里同名的局部变量。 */
    panX: Float,
    panY: Float,
    viewportW: Float,
    viewportH: Float,
    onPlay: (WallItem) -> Unit,
    onClose: () -> Unit,
) {
    val posterId = state.posterId ?: return
    val index = items.indexOfFirst { it.id == posterId }
    if (index < 0) return
    val item = items[index]
    // 起点用**打开海报那一刻**快照下来的屏幕矩形，而不是当前布局里的瓦片矩形 ——
    // 理由见 `PosterOrigin` 的 KDoc（用实时矩形会让海报从屏幕外撑开）。
    // 兜底：没有快照（例如状态被外部直接设了 posterId）时退化为当前瓦片矩形。
    val origin = state.posterFrom ?: run {
        val r = layout.rects.getOrNull(index) ?: return
        PosterOrigin(r.x + panX, r.y + panY, r.w, r.h)
    }

    val zoom = state.zoom
    val amount = wallSmoothstep(0.68f, 0.78f, zoom)
    if (amount <= 0.004f) return

    // 出生矩形 → 目标海报矩形 的连续插值。
    // ⚠️ 终点必须**正好落在海报层焦距（0.78）**上：区间若拖到 0.80/0.88 才走完，
    // 用户停在 Z3 时拿到的是一张还没长成的卡 —— 出图里表现为卡片明显偏右不居中
    // （起点在哪边就偏哪边）。收在这里，Z3 就是一张完全落定的居中海报。
    val morph = wallSmoothstep(0.68f, WallLevel.POSTER.zoom, zoom)

    val density = LocalDensity.current
    val isNarrow = viewportW < 820f
    val cardW = if (isNarrow) viewportW * 0.88f else 430f
    val cardH = minOf(if (isNarrow) viewportH * 0.74f else cardW * 1.36f, cardW * 1.36f)
    val toX = (viewportW - cardW) / 2f
    val toY = (viewportH - cardH) / 2f

    val x0 = origin.x + (toX - origin.x) * morph
    val y0 = origin.y + (toY - origin.y) * morph
    val w0 = origin.w + (cardW - origin.w) * morph
    val h0 = origin.h + (cardH - origin.h) * morph

    // ── 第二阶段：Z4 沉浸（P0 极简版）──
    // 海报继续铺满整屏，成为"墙在最大焦距上的样子"。
    // ⚠️ 完整的沉浸播放器（队列环、表冠双语义、歌词）是 P2；这里只把**几何**做连续 ——
    // 否则 Z4 会停在"巨大网格 + 一张海报卡"，是个说不通的状态。
    // 也因此：Z4 目前需要先点开一张专辑（有 `posterId`）才成立。
    val imm = wallSmoothstep(0.86f, 1.0f, zoom)
    val x = x0 + (0f - x0) * imm
    val y = y0 + (0f - y0) * imm
    val w = w0 + (viewportW - w0) * imm
    val h = h0 + (viewportH - h0) * imm

    // 遮罩：墙在身后变暗，而不是被"盖住"
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f * amount * (1f - wallSmoothstep(0.86f, 0.96f, zoom)))),
    )

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .clip(RoundedCornerShape((26f * (1f - wallSmoothstep(0.86f, 1.0f, zoom))).dp))
            .background(MaterialTheme.colorScheme.surface)
            .graphicsAlpha(amount),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            if (!item.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.coverUrl.resized(900),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.38f to Color.Black.copy(alpha = 0.34f),
                            1f to Color.Black.copy(alpha = 0.82f),
                        ),
                    ),
            )
        }

        // 文案与动作随 morph 淡入：海报不是"啪"地出现，而是从封面里长出来
        val bodyAlpha = wallSmoothstep(0.74f, 0.82f, zoom)
        val s = cpStrings()
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .graphicsAlpha(bodyAlpha),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                item.title,
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { onPlay(item) },
                    shape = CircleShape,
                    color = Color.White,
                    contentColor = Color(0xFF101014),
                ) {
                    Row(
                        Modifier.padding(start = 20.dp, end = 26.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.wall.posterPlay, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MoreVert, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 关闭：缩回 Z2。对齐右上 —— 左下角已经是播放键的位置，两个动作分居两角。
        Surface(
            onClick = onClose,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** 不引入 `animateFloatAsState` 的轻量透明度 —— 传进来的值本身已经是连续的。 */
private fun Modifier.graphicsAlpha(alpha: Float): Modifier =
    this.graphicsLayer { this.alpha = alpha }
