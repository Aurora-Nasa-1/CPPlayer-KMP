package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.AlbumCoverThumb
import cp.player.app.ui.component.CpActionButtonGroup
import cp.player.app.ui.component.CpActionGroupItem
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.epochMillisToYear
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.music.AlbumDetail

/**
 * 专辑详情**头部**：封面 + 名称 + 歌手 / 发行信息 + 说明 + 收藏 + 动作组。
 *
 * 拆到这个文件里（而不是留在 `AlbumDetailScreen.kt`）的唯一理由是**可出图**：
 * 整页要 `AppModel` + 网络才能起来，而这一块是纯数据驱动的 —— 收藏开关的选中态
 * 与文案宽度只能靠离屏渲染核对（见 `AlbumHeaderPreviewTest`）。
 *
 * 按 M3 Expressive 的「hero 详情头」组织（与 `AboutScreen` 的 hero 同一套语言）：
 * 1. **标题用主色 + Bold** —— 一页的门面要一眼认出来；普通 `onSurface` 标题与列表行
 *    里的正文没有层级差，整页看起来像「一堆没排序的文字」；
 * 2. 封面圆角走 `shapes.extraLarge`（28dp），大图配大圆角；
 * 3. 动作操作整合为连体动作组（[CpActionButtonGroup]），主次分明且自适应横向布局；
 * 4. 收藏是**有状态的开关**而不是普通动作，所以走 [CpToggleChip]（选中时形状本身
 *    从圆角方变胶囊），排在动作组**下面**单独一行 —— 塞进动作组会让它和
 *    「播放 / 打乱」看起来同级，但它其实不会起播任何东西。
 *
 * ⚠️ 曲目为空时四个播放类动作**全部禁用**。拿不到曲目时头部仍会画出来（错误态也保留
 * 封面与名称），可点的按钮会让用户以为「点了没反应」—— 实际上队列里什么都没有。
 * 收藏**不受曲目有无影响**：空曲目专辑同样可以被收藏，所以它不跟 [hasTracks] 走。
 */
@Composable
internal fun AlbumHeader(
    detail: AlbumDetail,
    trackCount: Int,
    totalDurationMs: Long,
    wide: Boolean,
    isFavorite: Boolean,
    favoritePending: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val s = cpStrings()
    val metaLine = buildList {
        detail.artistName?.takeIf { it.isNotBlank() }?.let(::add)
        detail.publishTimeMs?.let { ms ->
            epochMillisToYear(ms)?.let { year -> add(s.album.releasedYear(year)) }
        }
        detail.company?.takeIf { it.isNotBlank() }?.let(::add)
        if (trackCount > 0) add(s.album.albumMeta(trackCount, formatTimeMs(totalDurationMs)))
    }.joinToString(" · ")

    val hasTracks = trackCount > 0
    val actions = listOf(
        CpActionGroupItem(s.library.playAll, Icons.Filled.PlayArrow, onPlayAll, enabled = hasTracks),
        CpActionGroupItem(s.album.shufflePlay, Icons.Filled.Shuffle, onShuffle, enabled = hasTracks),
        CpActionGroupItem(s.library.addToQueue, Icons.AutoMirrored.Filled.PlaylistAdd, onAddToQueue, enabled = hasTracks),
        CpActionGroupItem(s.library.downloadAll, Icons.Filled.Download, onDownloadAll, enabled = hasTracks),
    )

    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AlbumCoverThumb(
                coverUrl = detail.coverUrl,
                corner = AlbumCoverCorner,
                modifier = Modifier.size(220.dp),
            )
            Column(Modifier.weight(1f)) {
                AlbumTitle(detail.name, MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                AlbumMetaLine(metaLine)
                AlbumDescriptionBlock(detail.description, maxLines = 6)
                Spacer(Modifier.height(16.dp))
                CpActionButtonGroup(actions, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                AlbumFavoriteChip(isFavorite = isFavorite, pending = favoritePending, onClick = onToggleFavorite)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            AlbumCoverThumb(
                coverUrl = detail.coverUrl,
                corner = AlbumCoverCorner,
                // ⚠️ `widthIn` 必须在 `fillMaxWidth` **之前**：顺序反了约束已被钉死，
                // `widthIn` 会变成空操作（见 AGENTS.md 的宽高约束约定）。
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(16.dp))
            AlbumTitle(detail.name, MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            AlbumMetaLine(metaLine)
            AlbumDescriptionBlock(detail.description, maxLines = 5)
            Spacer(Modifier.height(16.dp))
            CpActionButtonGroup(actions, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            AlbumFavoriteChip(isFavorite = isFavorite, pending = favoritePending, onClick = onToggleFavorite)
        }
    }
}

/**
 * 专辑收藏开关。
 *
 * 文案随状态切换（「收藏专辑」→「已收藏专辑」）而**不是**只靠图标变色：这个控件带文字，
 * 文字本身就是最便宜的状态提示，图标只是辅助。
 *
 * @param pending 收藏请求在途时置灰 —— 连点会叠出多个 `album/sub` 请求，
 *   而它们的目标状态是按**点击那一刻的本地状态**算出来的，叠起来就是反复收藏 / 取消。
 */
@Composable
internal fun AlbumFavoriteChip(
    isFavorite: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val s = cpStrings()
    CpToggleChip(
        checked = isFavorite,
        onCheckedChange = { onClick() },
        label = if (isFavorite) s.album.favoriteAlbumActive else s.album.favoriteAlbum,
        enabled = !pending,
        icon = if (isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkAdd,
    )
}

/**
 * 专辑名。**主色 + Bold** —— 详情页的门面。
 *
 * 宽度（`headlineMedium` / `headlineSmall`）由调用方按宽窄屏选：同一个值在手机上
 * 会一行只放得下三四个字，专辑名普遍偏长，会立刻折成三行把封面挤下去。
 */
@Composable
private fun AlbumTitle(name: String, style: TextStyle) {
    CpText(
        name,
        style = style,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 3,
    )
}

/** 元信息行：歌手 · 发行年 · 发行方 · N 首 / 时长。全空时**不画**（否则留一条空行）。 */
@Composable
private fun AlbumMetaLine(text: String) {
    if (text.isBlank()) return
    CpText(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 专辑说明。**不套卡片**：说明本身是长文，再包一层容器只会多一道边框和一层内边距。 */
@Composable
private fun AlbumDescriptionBlock(text: String?, maxLines: Int) {
    val desc = text?.takeIf { it.isNotBlank() } ?: return
    Spacer(Modifier.height(12.dp))
    CpText(
        desc,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
    )
}

/**
 * 专辑封面圆角，取 M3 形状刻度的 `extraLarge`（28dp）—— 与卡片 / 弹层同一档。
 *
 * 不沿用列表缩略图的 12dp：那个值是按 60dp 的图定的，套到 220dp 的封面上会显得很方。
 */
private val AlbumCoverCorner = 28.dp
