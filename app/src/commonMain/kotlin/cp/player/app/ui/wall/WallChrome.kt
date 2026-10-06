package cp.player.app.ui.wall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.cpStrings

/**
 * 墙的**构图方式**（排序）。
 *
 * 排序在墙模式里不是设置项，而是构图手段 —— 换一次排序整面墙重新排布，
 * 但**谁是大瓦片不变**（大瓦片由"最近"决定，见 `AlbumWallScreen` 的 `buildWallItems`）。
 * 于是换排序是一次纯粹的"洗牌"，而不是"重新评价一遍谁重要"。
 */
enum class WallSort { RECENT, TITLE, TYPE }

/** 窄屏阈值：低于它缩放谱收成纯圆点、表冠抬到读数条上方。 */
private val CompactWidth = 560.dp

/**
 * 墙的浮层组合：缩放谱 / 表冠 / 排序条 / 读数条。
 *
 * ## 为什么必须抽成一个 composable
 *
 * 第一版把这段堆叠**在页面与出图夹具里各写了一份**，结果两边漂了：
 * 页面里加了"海报打开时收起缩放谱"，夹具没加 —— 于是出图看到的和真机看到的不是一回事，
 * 预览给出的"通过"是假的。抽出来之后，出图与真页面走的是同一份代码。
 *
 * 调用方只需保证它**排在 `AlbumWall` 之后**（同级、更晚绘制 = 在上面），
 * 且**不能**把它塞进 `AlbumWall` 内部 —— 画布里的交互层是 `fillMaxSize` 的，
 * 会把浮层的点击全吃掉。
 */
@Composable
fun WallChrome(
    state: WallState,
    sort: WallSort,
    onSortChange: (WallSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < CompactWidth

        // ⚠️ Z4 沉浸播放器占满整屏，四个浮层全部让位 —— 否则缩放谱、表冠、排序条
        // 会压在播放器控件上（出图里能看到表冠盖住"下一首"）。
        // 退出沉浸走播放器自己的收起键 / 缩回手势，不需要浮层常驻。
        if (state.zoom >= WallLevel.ImmersiveFrom) return@BoxWithConstraints

        // 海报打开时收起缩放谱：窄屏上海报卡几乎占满宽度，右缘的谱会压在卡片边上。
        // 退出海报的路子本来就有三条（关闭键 / Esc / 缩小手势），不需要它一直占着。
        if (state.posterId == null) {
            WallOverlaySlot(
                Modifier.align(Alignment.CenterEnd)
                    .padding(end = if (compact) 10.dp else 18.dp),
            ) {
                WallZoomLadder(
                    zoom = state.zoom,
                    onZoom = { state.zoomToCentre(it) },
                    compact = compact,
                )
            }
        }

        WallOverlaySlot(
            Modifier.align(Alignment.BottomEnd).padding(
                end = if (compact) 18.dp else 78.dp,
                // 窄屏把表冠抬到读数条上方：两者都在底部时会重叠。
                bottom = if (compact) 84.dp else 30.dp,
            ),
        ) {
            WallCrown(
                zoom = state.zoom,
                onZoom = { state.zoomToCentre(it) },
                onReset = { state.zoomToCentre(WallLevel.MOSAIC.zoom) },
            )
        }

        WallOverlaySlot(
            Modifier.align(Alignment.TopStart)
                .padding(start = if (compact) 12.dp else 20.dp, top = 12.dp),
        ) {
            WallSortChips(sort = sort, onSelect = onSortChange)
        }

        WallOverlaySlot(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
        ) {
            state.layout?.let { layout ->
                WallHud(zoom = state.zoom, layout = layout, compact = compact)
            }
        }
    }
}

@Composable
private fun WallSortChips(
    sort: WallSort,
    onSelect: (WallSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val options = listOf(
        WallSort.RECENT to s.wall.sortRecent,
        WallSort.TITLE to s.wall.sortTitle,
        WallSort.TYPE to s.wall.sortType,
    )
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (key, label) ->
                val on = key == sort
                Surface(
                    onClick = { onSelect(key) },
                    shape = CircleShape,
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                    contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
