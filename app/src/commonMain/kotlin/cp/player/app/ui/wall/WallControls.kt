package cp.player.app.ui.wall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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

/**
 * **吞掉落在本节点上的所有指针事件。**
 *
 * 用于墙上的浮层（HUD / 缩放谱 / 表冠 / 排序条 / 海报）。它们画在画布之上，但 Compose
 * 只会把事件交给**声明了手势**的节点 —— 没有手势的浮层（比如只显示数字的 HUD）会让
 * 点击**穿透**到画布，于是"点 HUD 上的读数"会打开 HUD 底下那张专辑的海报。
 *
 * 用法：把它挂在一个**包住控件的容器**上，而不是控件自己身上 ——
 * 挂在容器上时，控件自己的手势（子节点，Main pass 先跑）照常工作，
 * 容器只负责把剩下的变化标记为已消费，挡在画布之前。
 */
internal fun Modifier.consumeAllPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/** 把控件包一层"事件拦截"容器。见 [consumeAllPointerInput]。 */
@Composable
internal fun WallOverlaySlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.consumeAllPointerInput()) { content() }
}

/** 拖动一格表冠对应的焦距增量。数值决定"转多少才走一层"，是全应用唯一一处表冠灵敏度。 */
private const val CrownSensitivity = 0.0026f

/** 缩放谱每行的高度。 */
private val LadderRowHeight = 42.dp

/** 圆点占位方框的边长。圆心恒在它的一半处，轨道按这个值对齐。 */
private val DotBoxSize = 12.dp

/**
 * 纵向比例（0..1，相对**行区域**）→ 目标焦距。
 *
 * ⚠️ 不能直接 `zoom = ratio`：5 行是**等距**排布的，圆心落在 `0.1 / 0.3 / 0.5 / 0.7 / 0.9`，
 * 而命名层的焦距是 `0 / 0.30 / 0.55 / 0.78 / 1.00` —— 两者都**不是**线性均分。
 * 这里先把 ratio 映射到"第几行"（行圆心对齐到整数行号），再在相邻两层的焦距之间插值，
 * 于是"拖到海报那一行"就真的落在海报层（直接均分会差 3% 左右，表现为滑块和层名对不上）。
 */
internal fun zoomAtLadderRatio(ratio: Float): Float {
    val levels = WallLevel.Ordered
    val n = levels.size
    val firstCentre = 0.5f / n
    val lastCentre = 1f - firstCentre
    val rowF = ((ratio.coerceIn(firstCentre, lastCentre) - firstCentre) / (lastCentre - firstCentre)) * (n - 1)
    val i = rowF.toInt().coerceIn(0, n - 2)
    return levels[i].zoom + (levels[i + 1].zoom - levels[i].zoom) * (rowF - i)
}

/**
 * **缩放谱**：右缘的竖向轨道 + 五个命名层刻度。
 *
 * 它的职责是**可发现性** —— 墙的全部操作压在"无极缩放"上，而用户不会去猜；
 * 一条带刻度的轨道一眼就说明"这里有五档、而且中间是连续的"。
 *
 * ⚠️ 宽度**由内容撑开**，不要写死。第一版写死 46dp，而"马赛克"三个字在 12sp 下就要
 * 36dp，加上圆点与间距必然溢出 —— 出图里表现为标签被裁掉一半。
 *
 * @param onZoom 传**目标焦距**（不是 0..1 的比例）。调用方一律走
 *   `WallState.zoomToCentre`，否则滑块会与画面不同步。
 */
@Composable
internal fun WallZoomLadder(
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val s = cpStrings()
    val levels = WallLevel.Ordered
    val active = WallZoomMath.levelIndex(zoom)
    var trackHeight by remember { mutableFloatStateOf(1f) }
    val zoomCallback by rememberUpdatedState(onZoom)
    val railColour = MaterialTheme.colorScheme.surfaceContainerHighest
    val horizontal = if (compact) 10.dp else 12.dp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier
                .onSizeChanged { trackHeight = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        zoomCallback(zoomAtLadderRatio(change.position.y / trackHeight))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        zoomCallback(zoomAtLadderRatio(pos.y / trackHeight))
                    }
                }
                .padding(horizontal = horizontal, vertical = 12.dp)
                // ⚠️ 轨道必须用 drawBehind **画**，不能用 `fillMaxHeight()` 的兄弟 Box：
                // 那个 Box 会把整个 Surface 撑到父容器给的最大高度（= 整屏），
                // 于是缩放谱变成一根贯穿上下的长条（第一版出图里就是这样）。
                // drawBehind 不参与测量，尺寸仍由 5 行内容决定。
                .drawBehind {
                    val cx = DotBoxSize.toPx() / 2f
                    val half = LadderRowHeight.toPx() / 2f
                    drawLine(
                        color = railColour,
                        start = Offset(cx, half),
                        end = Offset(cx, size.height - half),
                        strokeWidth = 2.dp.toPx(),
                    )
                },
        ) {
            levels.forEachIndexed { index, level ->
                val on = index == active
                Row(
                    Modifier.height(LadderRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 圆点固定占 DotBoxSize 的方框、小圆居中 —— 这样圆心恒在 DotBoxSize/2，
                    // 与 drawBehind 画的轨道对齐；选中态只把圆放大，不改占位。
                    Box(Modifier.size(DotBoxSize), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(if (on) DotBoxSize else DotBoxSize * 0.66f)
                                .clip(CircleShape)
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else railColour,
                                ),
                        )
                    }
                    if (!compact) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            level.label(s),
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
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
 *
 * @param onZoom 传**目标焦距**。调用方一律走 `WallState.zoomToCentre` ——
 *   第一版这里直接改 `state.zoom` 不走锚定，结果拧表冠时画面会持续往左上角漂。
 */
@Composable
internal fun WallCrown(
    zoom: Float,
    onZoom: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val zoomCallback by rememberUpdatedState(onZoom)
    val resetCallback by rememberUpdatedState(onReset)
    val current by rememberUpdatedState(zoom)
    Surface(
        modifier = modifier
            .size(74.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    // 向上拖 = 放大。`drag.y` 向下为正，取负号。
                    zoomCallback((current - drag.y * CrownSensitivity).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { resetCallback() })
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
 *
 * @param compact 窄屏（手机）时只留"层名 + 配比"，其余挤不下。
 */
@Composable
internal fun WallHud(
    zoom: Float,
    layout: WallLayout,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
                maxLines = 1,
                softWrap = false,
            )
            HudDivider()
            if (!compact) {
                HudNumber("${layout.unit.toInt()} px ${s.wall.hudUnit}")
                HudDivider()
                HudNumber("${layout.cols} ${s.wall.hudCols} · ${layout.rows} ${s.wall.hudRows}")
                HudDivider()
            }
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
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * 视口内命中测试（与 `AlbumWall` 里点击判定同源，抽出来便于单测）。
 *
 * 落在瓦片之间的**缝里**返回 -1：宁可不响应，也不要跳错一张。
 */
internal fun hitTest(rects: List<WallRect>, panX: Float, panY: Float, pos: Offset): Int =
    rects.indexOfFirst { it.distanceSquaredTo(pos.x - panX, pos.y - panY) == 0f }
