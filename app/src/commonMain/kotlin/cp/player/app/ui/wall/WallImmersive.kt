package cp.player.app.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.core.playback.PlaybackUiState

/** 沉浸层的底色。**必须不透明**，理由见 [WallImmersive] 的 KDoc。 */
private val ImmersiveBase = Color(0xFF0A0A10)

/**
 * **Z4 沉浸播放器** —— 墙在最深一层焦距上的样子。
 *
 * ## 它不是"另一个播放页"
 *
 * 它没有独立的路由、没有自己的数据源：曲目、进度、队列全部来自同一个
 * [PlaybackUiState]，而它的出现完全由 `zoom` 驱动 —— 焦距走到 0.86 以上才浮现，
 * 缩回去就消失。这正是方案 §7 的主张：**播放器是墙的一个焦距，不是盖在墙上的另一层 UI**。
 *
 * ## 与 Z3 海报的接力
 *
 * 海报负责"从瓦片里长出来"（0.68 → 0.78），沉浸层接着把封面**继续放大**到居中大图
 * （封面从 `scale(0.88)` 长到 `1.0`），中间没有跳变。所以用户看到的是同一张封面
 * 一路放大，而不是"关掉海报、打开播放器"。
 *
 * ## 两个"少了就出事"的细节
 *
 * 1. **不透明底色**。沉浸层是全屏的，底下就是画布 —— 只用半透明遮罩的话，墙上那些
 *    300–680px 的大瓦片会**透上来和歌名叠在一起**（第一版出图里就是这样）。
 * 2. **根节点吞指针事件**。不吞的话，点"下一首"会同时命中画布上的某张瓦片、
 *    把海报又打开一次。这里的 `consumeAllPointerInput` 是**必需**的，不是保险。
 *
 * @param upNext 墙上的邻居 —— 队列在墙上的样子（见方案 §7.3）。
 */
@Composable
fun WallImmersive(
    playback: PlaybackUiState,
    zoom: Float,
    upNext: List<WallItem>,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayUpNext: (WallItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val amount = wallSmoothstep(0.86f, 1.0f, zoom)
    if (amount <= 0.004f) return

    val track = playback.currentTrack ?: return
    val s = cpStrings()
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = amount }
            .consumeAllPointerInput(),
    ) {
        // ── 不透明底 ──
        Box(Modifier.fillMaxSize().background(ImmersiveBase))

        // ── 背景：当前封面的放大模糊版 ──
        // 取小图（256）就够了 —— 它会被 blur 掉，请求大图纯属浪费流量，
        // 而且"模糊"本身就掩盖了低分辨率。
        if (!track.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.coverUrl.resized(256),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(52.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.58f),
                    0.45f to Color.Black.copy(alpha = 0.38f),
                    1f to Color.Black.copy(alpha = 0.84f),
                ),
            ),
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val coverSide = minOf(maxHeight * 0.32f, 300.dp)
            // 封面按**实际渲染像素**取图：沉浸层的封面比海报更大，用海报那档会糊。
            val coverRequest = with(density) { wallCoverRequestSize(coverSide.toPx()) }

            Surface(
                onClick = onCollapse,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = s.wall.posterClose,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // ⚠️ 用 weight 把"播放器主体"和"队列"分成上下两块，而不是让主体居中、
            // 队列绝对定位在底部 —— 后者在矮窗口（手机横屏、小窗）上会重叠。
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(coverSide)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            // 封面从 0.88 长到 1：接住海报层的最后一段，读起来是"同一张图继续放大"
                            .graphicsLayer {
                                val k = wallSmoothstep(0.86f, 1.0f, zoom)
                                scaleX = 0.88f + 0.12f * k
                                scaleY = 0.88f + 0.12f * k
                            },
                    ) {
                        if (!track.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = track.coverUrl.resized(coverRequest),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        track.name,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        track.artist,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )

                    Spacer(Modifier.height(22.dp))
                    ImmersiveProgress(
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        onSeek = onSeek,
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    )

                    Spacer(Modifier.height(18.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                        ImmersiveButton(Icons.Filled.SkipPrevious, s.wall.skipPrevious, 52.dp, 24.dp, onSkipPrevious)
                        Surface(
                            onClick = onPlayPause,
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(68.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF101014),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        ImmersiveButton(Icons.Filled.SkipNext, s.wall.skipNext, 52.dp, 24.dp, onSkipNext)
                    }
                }

                // ── 队列 = 墙上的邻居 ──
                if (upNext.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            s.wall.immersiveQueue,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            upNext.take(8).forEachIndexed { index, item ->
                                Box(
                                    Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .pointerInput(item.id) {
                                            detectTapGestures { onPlayUpNext(item) }
                                        },
                                ) {
                                    if (!item.coverUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = item.coverUrl.resized(128),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    Text(
                                        "${index + 1}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmersiveButton(
    icon: ImageVector,
    label: String,
    boxSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(boxSize)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * 可拖动的进度条。
 *
 * 自己画而不是复用 `CpSeekBar`：那条是为浅色播放页设计的（带 track 容器与主题色），
 * 放在铺满封面的沉浸层上会显得像贴上去的一块 UI。这里只要一根白线 + 一个圆点。
 */
@Composable
private fun ImmersiveProgress(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    var trackWidth by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(duration) {
                    detectTapGestures { pos -> onSeek((pos.x / trackWidth * duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onSeek((change.position.x / trackWidth * duration).toLong())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.24f)),
            )
            Box(
                Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = (fraction * trackWidth) - with(density) { 5.5.dp.toPx() }
                    }
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTimeMs(positionMs), color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
            Text(formatTimeMs(durationMs), color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
        }
    }
}
