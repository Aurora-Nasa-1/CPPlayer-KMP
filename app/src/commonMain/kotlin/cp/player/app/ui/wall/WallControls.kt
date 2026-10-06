package cp.player.app.ui.wall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings

/** 命名层的中文名。做成函数而不是顶层 `val`：顶层属性在类加载时求值，那时还没有语言状态。 */
internal fun WallLevel.label(s: CpStrings): String = when (this) {
    WallLevel.DUST -> s.wall.levelDust
    WallLevel.MOSAIC -> s.wall.levelMosaic
    WallLevel.COVER -> s.wall.levelCover
    WallLevel.POSTER -> s.wall.levelPoster
    WallLevel.IMMERSIVE -> s.wall.levelImmersive
}

/** 拖动一格表冠对应的焦距增量。数值决定"转多少才走一层"，是全应用唯一一处表冠灵敏度。 */
private const val CrownSensitivity = 0.0026f

/**
 * **缩放谱**：右缘的竖向轨道 + 四个命名层刻度。
 *
 * 它的职责是**可发现性** —— 墙的全部操作压在"无极缩放"上，而用户不会去猜；
 * 一条带刻度的轨道一眼就说明"这里有四档、而且中间是连续的"。
 *
 * 拖动整条轨道可连续跳转，点单个刻度落到该层。竖拖与点按共用一个 y → zoom 映射，
 * 所以两者手感一致。
 */
@Composable
internal fun WallZoomLadder(
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val levels = WallLevel.Ordered
    val active = WallZoomMath.levelIndex(zoom)
    var trackHeight by remember { mutableFloatStateOf(1f) }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Box(
            Modifier
                .width(46.dp)
                .onSizeChanged { trackHeight = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val t = (change.position.y / trackHeight).coerceIn(0f, 1f)
                        onZoom(t)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        onZoom((pos.y / trackHeight).coerceIn(0f, 1f))
                    }
                },
        ) {
            Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
                levels.forEachIndexed { index, level ->
                    val on = index == active
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(if (on) 12.dp else 10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            level.label(s),
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                }
            }
            // 轨道底槽 + 进度填充（画在最底层，用 alpha 让刻度点仍可见）
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 13.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)),
            )
        }
    }
}

/**
 * **表冠**（Apple Watch 隐喻）。
 *
 * 墙的手势入口在触摸端是捏合、桌面端是滚轮，但两者都"不显眼" ——
 * 表冠是一个**看得见、可以拧**的无极入口，也是这个模式的签名控件。
 *
 * 上下拖动改变焦距；双击回到默认层。旋钮上那道指示线随焦距转动，
 * 让"连续"这件事在静止时也可见。
 */
@Composable
internal fun WallCrown(
    zoom: Float,
    onZoomDelta: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val density = LocalDensity.current
    Surface(
        modifier = modifier
            .size(74.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    // 向上拖 = 放大。`drag.y` 向下为正，取负号。
                    onZoomDelta(-drag.y * CrownSensitivity)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onReset() })
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ),
                    ),
            )
            Surface(
                modifier = Modifier.size(34.dp).rotate(zoom * 300f - 150f),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(contentAlignment = Alignment.TopCenter) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(width = 3.dp, height = 11.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Text(
                s.wall.crownLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * 焦距读数条。
 *
 * 把"无极"这件事量化给用户看：当前层名、单位格边长、列行数、以及**比例配比**
 * （大 / 横 / 竖 / 标准各多少）。最后一项是变比例马赛克唯一的可视化证据 ——
 * 缩放时它会实时变化，跨过阈值时整面墙会洗一次牌。
 */
@Composable
internal fun WallHud(
    zoom: Float,
    layout: WallLayout,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val mix = layout.mix()
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${s.wall.hudZoom} ${WallZoomMath.levelOf(zoom).label(s)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HudDivider()
            HudNumber("${layout.unit.toInt()} px ${s.wall.hudUnit}")
            HudDivider()
            HudNumber("${layout.cols} ${s.wall.hudCols} · ${layout.rows} ${s.wall.hudRows}")
            HudDivider()
            HudNumber(
                s.wall.hudMix(
                    mix[WallSpan.Big] ?: 0,
                    mix[WallSpan.Wide] ?: 0,
                    mix[WallSpan.Tall] ?: 0,
                    mix[WallSpan.Square] ?: 0,
                ),
            )
        }
    }
}

@Composable
private fun HudDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 16.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun HudNumber(text: String) {
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 视口内命中测试（与 `AlbumWall` 里点击判定同源，抽出来便于单测）。 */
internal fun hitTest(rects: List<WallRect>, panX: Float, panY: Float, pos: Offset): Int =
    rects.indexOfFirst { it.distanceSquaredTo(pos.x - panX, pos.y - panY) == 0f }
