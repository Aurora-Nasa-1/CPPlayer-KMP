package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightTarget
import cp.player.app.ui.component.BentoCard
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.TrackListMetrics
import cp.player.app.ui.component.TrackPrimaryAction
import cp.player.app.ui.component.TrackPrimaryActionRow
import cp.player.app.ui.component.TrackSecondaryActionRow
import cp.player.app.ui.component.TrackSecondarySpec
import cp.player.app.ui.util.resized
import cp.player.core.music.PlaylistSummary

/**
 * 歌单详情的**信息卡** —— 列表壳的 `header` 槽。
 *
 * ## 版面来自「云盘」页（2026-10-29）
 *
 * 云盘页的头部是**一条紧凑的横向卡片**：左边图标、中间「标题 + 一行副信息」、
 * 右边一个动作。歌单页照它搬过来，只把图标换成封面：
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ ┌──────┐  夜里跑步时听的歌                    │
 * │ │ 封面 │  阿猫 · 24 首歌曲 · 1:24:00     🔖  │
 * │ └──────┘                                     │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 改造前这里是 **200dp 居中大封面 + 歌名 + 三枚元数据胶囊 + 再重复一遍曲目数**，
 * 头部块吃掉约 400dp —— 手机一屏只放得下 6 行曲目，而每日推荐 / 心动模式这类
 * 虚拟歌单的封面还是「第一首歌的封面」，放这么大既不准确也没有信息量。
 * 现在整块降到 92dp（64dp 封面 + 上下 14dp 内边距），同屏多出三行多曲目。
 *
 * ⚠️ 这里只放信息，**没有任何操作按钮**：
 *
 * - 主播放（全部播放 / 随机播放）由壳按 [TrackPrimaryAction] 枚举统一生成；
 * - 分享 / 重命名 / 删除 / 取消收藏 / 排序 / 下载全部一律走顶栏的「更多」；
 * - 收藏态只是**标记**（右上角一枚 `Bookmark`），不是按钮 —— 切换收藏仍在「更多」里。
 *
 * @param trackCountLabel 已含时长的曲目数文案（`库` 的 `trackCountLabel`）。
 */
@Composable
internal fun PlaylistHeaderInfo(
    summary: PlaylistSummary,
    trackCountLabel: String,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier
                .size(108.dp)
                .coverFlightTarget(CoverFlight.TARGET_PLAYLIST_HEADER, 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 4.dp,
        ) {
            val hideCover = CoverFlight.isFlyingTo(CoverFlight.TARGET_PLAYLIST_HEADER)
            if (!summary.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = summary.coverUrl.resized(300),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (hideCover) 0f else 1f },
                )
            } else {
                CpCoverPlaceholder(
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (hideCover) 0f else 1f },
                    corner = 16.dp,
                    animated = false,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CpText(
                    text = summary.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = cpStrings().library.favorited,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            val creator = summary.creatorName
            if (!creator.isNullOrBlank()) {
                CpText(
                    text = creator,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            CpText(
                text = trackCountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 信息卡副标题：`作者 · 曲目数（含时长）`。虚拟歌单（每日推荐 / 心动模式）也有作者，不必特判。 */
private fun headerMetaLine(creatorName: String?, trackCountLabel: String): String {
    val creator = creatorName?.trim().orEmpty()
    return if (creator.isEmpty()) trackCountLabel else "$creator · $trackCountLabel"
}

/**
 * 歌单详情的**顶栏标题** —— 列表壳 `title` 槽。
 *
 * 高度固定由壳给（[TrackListMetrics.topBarHeight]），这一格里只排一行歌名。
 *
 * **不再放 44dp 封面**：封面已经进了下方信息卡，同一个封面在一屏里出现两次
 * 既冗余又让顶栏变挤（云盘页的顶栏同样只有「云盘」两个字）。
 * 曲目数 / 时长 / 作者也都在信息卡里，顶栏只承担「滚动时仍然知道是哪个歌单」。
 * **不做**滚动收缩、不做大标题展开 —— `LargeTopAppBar` 的 152dp 展开高度正是 G4 的来源。
 */
@Composable
internal fun PlaylistTopBarTitle(summary: PlaylistSummary) {
    CpText(
        text = summary.name,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 双栏左栏（信息栏）。宽屏桌面端大封面与沉浸式信息展示。
 */
@Composable
internal fun PlaylistInfoRail(
    summary: PlaylistSummary,
    isFavorite: Boolean,
    trackCountLabel: String,
    primaryActions: List<TrackPrimaryAction>,
    onPrimaryAction: (TrackPrimaryAction) -> Unit,
    primaryEnabled: Boolean,
    secondary: TrackSecondarySpec? = null,
    selectionMode: Boolean,
    selectedCount: Int,
    onToggleSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollColumn(
        modifier = modifier.fillMaxSize().padding(
            horizontal = 24.dp,
            vertical = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        // 桌面端宽屏大封面（208dp，extraLarge 28dp 圆角，8dp 阴影）
        Surface(
            modifier = Modifier
                .size(208.dp)
                .coverFlightTarget(CoverFlight.TARGET_PLAYLIST_HEADER, 28.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
        ) {
            val hideCover = CoverFlight.isFlyingTo(CoverFlight.TARGET_PLAYLIST_HEADER)
            if (!summary.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = summary.coverUrl.resized(600),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (hideCover) 0f else 1f },
                )
            } else {
                CpCoverPlaceholder(
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (hideCover) 0f else 1f },
                    corner = 28.dp,
                    animated = false,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CpText(
                    text = summary.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = cpStrings().library.favorited,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            val creator = summary.creatorName
            if (!creator.isNullOrBlank()) {
                CpText(
                    text = creator,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            CpText(
                text = trackCountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(6.dp))

        TrackPrimaryActionRow(
            actions = primaryActions,
            enabled = primaryEnabled,
            onAction = onPrimaryAction,
        )
        if (secondary != null && secondary.actions.isNotEmpty()) {
            TrackSecondaryActionRow(secondary)
        }
        if (selectionMode) {
            SelectionSummary(
                selectedCount = selectedCount,
                onToggleSelectAll = onToggleSelectAll,
                onExitSelection = onExitSelection,
            )
        }
    }
}

/**
 * 双栏右栏的顶栏标题：只有一行文字 + 曲目数。
 *
 * 右栏的封面与元数据都在左栏，这里再放一遍封面就会在同一个视口里出现两次。
 */
@Composable
internal fun PlaylistPaneTitle(trackCount: Int, durationStr: String) {
    Column(verticalArrangement = Arrangement.Center) {
        CpText(
            text = "曲目",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        CpText(
            text = cpStrings().library.trackCountLabel(trackCount, durationStr),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SelectionSummary(
    selectedCount: Int,
    onToggleSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CpText(
            text = s.library.selectedCount(selectedCount),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onToggleSelectAll) { CpText(s.library.selectAll) }
        androidx.compose.material3.TextButton(onClick = onExitSelection) { CpText(s.library.exitSelection) }
    }
}
