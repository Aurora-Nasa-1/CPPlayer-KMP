package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.ui.screen.VideoPlayerScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cp.player.app.AppModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.resized
import cp.player.core.music.TrackSummary

/**
 * M3 Expressive 风格歌曲列表项。
 *
 * 封面 52dp + 歌名/歌手 + 右侧 MoreVert 圆形按钮。
 * 点击主体区域触发 [onClick]，点击 MoreVert 触发 [onOptionsClick]。
 * [selectionMode] 为 true 时以 Checkbox 替代封面、隐藏 MoreVert，用于多选场景。
 */
@Composable
fun SongItem(
    track: TrackSummary,
    onClick: () -> Unit,
    onOptionsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showIndex: Boolean = false,
    index: Int = 0,
    total: Int = 0,
    isCurrentlyPlaying: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    /**
     * 右键菜单项（桌面端）。非空且非多选时，整行包在 [CpContextMenu] 里，
     * 右键弹出标准动作菜单；构建方法见 [songContextMenuItems]。
     * 多选模式下忽略（长按已被多选语义占用，右键同时弹菜单会打架）。
     */
    contextMenu: List<CpContextMenuItem>? = null,
    /**
     * 是否在副标题显示所属专辑。传 null 时跟随系统全局设置 [AppModel.showSongAlbumFlow]（默认关闭）。
     */
    showAlbum: Boolean? = null,
    /**
     * 自定义副标题文本（非空时覆盖默认的 "歌手 · 专辑" 格式，用于本地歌曲的时长/码率展示）。
     */
    supportingText: String? = null,
) {
    val globalShowAlbum by AppModel.showSongAlbumFlow.collectAsState()
    val actualShowAlbum = showAlbum ?: globalShowAlbum

    // 桌面端（窗口 chrome 接管）且传入了菜单项 ⇒ MoreVert 走锚定菜单而不是底部弹层。
    // 判据用 LocalWindowChromeActive 而不是平台，理由见它的 KDoc。
    val anchoredMenu = LocalWindowChromeActive.current && !contextMenu.isNullOrEmpty()

    val targetContainerColor = when {
        selectionMode && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer
        // ⚠️ 读 LocalIsDarkTheme（已解析的明暗），不要读 isSystemInDarkTheme() ——
        // 用户显式选深色而系统是浅色时后者会给出错的色板，行与背景撞色。
        // 浅色分支取 surfaceContainerLow 而非 surface：后者与页面背景同色，整列会糊成一片。
        else -> if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerLow
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = CpMotion.effects(),
        label = "songItemContainerColor",
    )

    // 正在播放时封面圆角从 12dp 平滑 morph 变形成 26dp（52dp 的正圆 Pill）
    val coverCorner by animateDpAsState(
        targetValue = if (isCurrentlyPlaying) 26.dp else 12.dp,
        animationSpec = CpMotion.spatial(),
        label = "songCoverCornerMorph",
    )
    val coverShape = remember(coverCorner) { RoundedCornerShape(coverCorner) }

    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        LegacyListItem(
            index = index.coerceAtLeast(0),
            total = total.coerceAtLeast(1),
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = rowModifier,
            emphasized = isCurrentlyPlaying,
            containerColor = animatedContainerColor,
        leadingContent = {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                if (showIndex) {
                    CpText(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Box(
                    Modifier.size(52.dp).clip(coverShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .coverFlightSource(CoverFlight.trackKey(track.id), coverCorner),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverUrl.resized(180),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        CpCoverPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            corner = coverCorner,
                            animated = false,
                        )
                    }
                    // 正在播放：封面上浮一个跳动的均衡器。它比「标题变主题色 + 加粗」
                    // 强得多 —— 后者要逐字读文字才知道，前者余光就能扫到。
                    if (isCurrentlyPlaying) {
                        CpPlayingEqualizer(
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        )
                    }
                }
                }
            }
        },
        // CpText 而不是 Text：歌名 / 歌手被截断后仍可读全（悬停浮出完整文本）。
        // 静态观感与治理前一致 —— 默认走尾部省略，且只在真的溢出时才挂额外能力。
        //
        // ⚠️ 只有**正在播放**的那一行是焦点位（emphasized）：列表里若每行都滚，
        // 一屏十几个动画同时跑既吃帧率又像屏保。isCurrentlyPlaying 在一次渲染里
        // 天然只有一个为真，跑马灯等于自带限流（见 CpTextReveal.Marquee）。
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CpText(
                    text = track.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    emphasized = isCurrentlyPlaying,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val mvId = track.mvId
                if (mvId != null && mvId > 0) {
                    val nav = LocalNavigator.current
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable {
                            nav?.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV"))
                        },
                    ) {
                        CpText(
                            text = "MV",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        },
        supportingContent = {
            CpText(
                text = supportingText ?: buildString {
                    append(track.artist)
                    if (actualShowAlbum && !track.album.isNullOrBlank()) {
                        append(" · ")
                        append(track.album)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                emphasized = isCurrentlyPlaying,
            )
        },
        trailingContent = if (!selectionMode && (onOptionsClick != null || anchoredMenu)) {{
            // 桌面端：MoreVert 点击弹出锚定菜单（与右键同一份 items，见 CpAnchoredMenu），
            // 不再走底部弹层；移动端保持 SongOptionsSheet 动线不变。
            var menuExpanded by remember { mutableStateOf(false) }
            CpAnchoredMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                items = contextMenu,
            ) {
                IconButton(
                    onClick = {
                        if (anchoredMenu) menuExpanded = true else onOptionsClick?.invoke()
                    },
                    colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }} else null,
        )
    }

    // 桌面右键菜单：非多选且传入了菜单项时才生效（见参数 KDoc）。
    if (contextMenu != null && !selectionMode) {
        CpContextMenu(items = contextMenu, modifier = modifier) {
            row(Modifier)
        }
    } else {
        row(modifier)
    }
}
