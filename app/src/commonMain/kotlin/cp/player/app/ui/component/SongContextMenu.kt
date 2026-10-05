package cp.player.app.ui.component

import cp.player.app.i18n.cpStrings
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import cp.player.core.music.TrackSummary

/**
 * 标准化的「歌曲」右键菜单构建器：把各页面共同的动作集合
 * （播放 / 收藏 / 加入队列 / 下载 / 添加到歌单 / 分享 / 歌曲信息）收成一份，
 * 页面只声明自己**有**的动作，缺省为 null 的动作不出现在菜单里。
 * 顺序与 [SongOptionsSheet] 的动作瓦片保持一致，用户在两处之间切换
 * 不需要重新找位置。
 */
data class SongMenuActions(
    val onPlay: (() -> Unit)? = null,
    val isFavorite: Boolean = false,
    val onToggleFavorite: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    /** 「下一首播放」：插到当前曲目之后（电台/长队列里追加到末尾基本听不到）。 */
    val onPlayNext: (() -> Unit)? = null,
    val isDownloaded: Boolean = false,
    val onDownload: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onShowInfo: (() -> Unit)? = null,
    /** 从队列移除（仅队列场景）。 */
    val onRemoveFromQueue: (() -> Unit)? = null,
    /** 从云盘删除（仅云盘场景）。破坏性动作，调用方负责先走 CpConfirmHost 二次确认。 */
    val onDelete: (() -> Unit)? = null,
)

/**
 * 按 [SongOptionsSheet] 的动线顺序构建右键菜单项；没有可用动作时返回空列表。
 *
 * **标了 `@Composable`**：菜单标签要随语言切换，而调用点全在组合上下文里
 * （右键菜单、更多弹层）。标 @Composable 就能直接读 `cpStrings()`，
 * 12 个调用点一个都不用改签名。
 */
@Composable
fun songContextMenuItems(actions: SongMenuActions): List<CpContextMenuItem> {
    val s = cpStrings()
    return buildList {
    actions.onPlay?.let {
        add(CpContextMenuItem(s.library.play, Icons.Filled.PlayArrow, it))
    }
    actions.onRemoveFromQueue?.let {
        add(CpContextMenuItem(s.player.removeFromQueue, Icons.Filled.Close, it, danger = true))
    }
    if (actions.onToggleFavorite != null) {
        add(
            CpContextMenuItem(
                if (actions.isFavorite) s.library.unfavoritePlaylist else s.library.favoritePlaylist,
                if (actions.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                actions.onToggleFavorite,
            )
        )
    }
    actions.onAddToQueue?.let {
        add(CpContextMenuItem(s.library.addToQueue, Icons.Filled.QueueMusic, it))
    }
    actions.onPlayNext?.let {
        add(CpContextMenuItem(s.library.playNext, Icons.Filled.SkipNext, it))
    }
    if (actions.onDownload != null) {
        add(
            CpContextMenuItem(
                if (actions.isDownloaded) s.player.downloaded else s.player.download,
                if (actions.isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                actions.onDownload,
                enabled = !actions.isDownloaded,
            )
        )
    }
    actions.onAddToPlaylist?.let {
        add(CpContextMenuItem(s.player.addToPlaylist, Icons.Filled.PlaylistAdd, it))
    }
    actions.onShare?.let {
        add(CpContextMenuItem(s.player.share, Icons.Filled.Share, it))
    }
    actions.onShowInfo?.let {
        add(CpContextMenuItem(s.player.songInfo, Icons.Filled.Info, it))
    }
    actions.onDelete?.let {
        add(CpContextMenuItem(s.library.removeFromCloud, Icons.Filled.Delete, it, danger = true))
    }
    }
}

/**
 * 歌曲分享文案（桌面端落入剪贴板）。链接形状与 [SongOptionsSheet] /
 * [PlayerMoreBottomSheet] 既有实现一致，均指向网易云页面。
 */
fun songShareText(track: TrackSummary): String =
    "「${track.name}」 https://music.163.com/#/song?id=${track.id}"

/** 歌单分享文案（桌面端落入剪贴板）。 */
fun playlistShareText(playlistId: Any?, playlistName: String): String =
    "「$playlistName」 https://music.163.com/#/playlist?id=$playlistId"

/**
 * 标准化的「歌单」右键菜单构建器 —— [SongMenuActions] 的歌单版。
 *
 * ## 为什么需要它
 *
 * 「歌单的更多操作」原先在四个地方各写了一遍：侧栏（`MainScreen.playlistMenu`）、
 * 我喜欢的音乐入口、歌单详情页左栏、以及本轮修复前的**搜索页 / 用户主页**（那两处更彻底，
 * 直接是 `onOptionsClick = {}` —— 按钮画得出来、点下去什么都没有）。
 * 四份实现的动作集合与可见性规则已经漂了：侧栏把「删除歌单 / 取消收藏」做成一项、
 * 详情页拆成两项。提到这里之后各页只声明自己**有**的动作，缺省为 null 的不出现。
 *
 * ## 可见性规则（与 [PlaylistOptionsSheet] 的瓦片一致，别再各写一套）
 *
 * - 本地虚拟歌单（id ≤ 0）没有服务端实体：分享 / 收藏 / 删除的链接与接口都无效，
 *   调用方**不要**调本函数（详情页直接判 `isLocalPlaylist` 返回 null）。
 * - [isOwner] 才谈得上「删除歌单」；收藏来的只能「取消收藏」。
 * - 动作顺序与 [PlaylistOptionsSheet] 的瓦片顺序对齐（播放 → 分享 → 删除/收藏 → 队列），
 *   用户在两处之间切换不用重新找位置。
 */
data class PlaylistMenuActions(
    val isOwner: Boolean,
    val isFavorite: Boolean = false,
    val onPlay: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onDownload: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onToggleFavorite: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/** 按 [PlaylistOptionsSheet] 的瓦片顺序构建右键菜单项。同样标 `@Composable` 以读语言。 */
@Composable
fun playlistContextMenuItems(actions: PlaylistMenuActions): List<CpContextMenuItem> {
    val s = cpStrings()
    return buildList {
    actions.onPlay?.let {
        add(CpContextMenuItem(s.library.playAll, Icons.Filled.PlayArrow, it))
    }
    actions.onAddToQueue?.let {
        add(CpContextMenuItem(s.library.addToQueue, Icons.Filled.QueueMusic, it))
    }
    actions.onDownload?.let {
        add(CpContextMenuItem(s.library.downloadAll, Icons.Filled.Download, it))
    }
    actions.onShare?.let {
        add(CpContextMenuItem(s.library.sharePlaylist, Icons.Filled.Share, it))
    }
    // 收藏与删除是互斥的一对：owner 才有「删除歌单」，其余只能「取消收藏」。
    if (!actions.isOwner && actions.onToggleFavorite != null) {
        add(
            CpContextMenuItem(
                if (actions.isFavorite) s.library.unfavoritePlaylist else s.library.favoritePlaylist,
                if (actions.isFavorite) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd,
                actions.onToggleFavorite,
            )
        )
    }
    if (actions.isOwner && actions.onDelete != null) {
        add(CpContextMenuItem(s.library.deletePlaylist, Icons.Filled.Delete, actions.onDelete, danger = true))
    }
    }
}
