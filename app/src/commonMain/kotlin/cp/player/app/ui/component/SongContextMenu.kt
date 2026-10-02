package cp.player.app.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
)

/** 按 [SongOptionsSheet] 的动线顺序构建右键菜单项；没有可用动作时返回空列表。 */
fun songContextMenuItems(actions: SongMenuActions): List<CpContextMenuItem> = buildList {
    actions.onPlay?.let {
        add(CpContextMenuItem("播放", Icons.Filled.PlayArrow, it))
    }
    actions.onRemoveFromQueue?.let {
        add(CpContextMenuItem("从队列移除", Icons.Filled.Close, it, danger = true))
    }
    if (actions.onToggleFavorite != null) {
        add(
            CpContextMenuItem(
                if (actions.isFavorite) "取消收藏" else "收藏",
                if (actions.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                actions.onToggleFavorite,
            )
        )
    }
    actions.onAddToQueue?.let {
        add(CpContextMenuItem("加入队列", Icons.Filled.QueueMusic, it))
    }
    actions.onPlayNext?.let {
        add(CpContextMenuItem("下一首播放", Icons.Filled.SkipNext, it))
    }
    if (actions.onDownload != null) {
        add(
            CpContextMenuItem(
                if (actions.isDownloaded) "已下载" else "下载",
                if (actions.isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                actions.onDownload,
                enabled = !actions.isDownloaded,
            )
        )
    }
    actions.onAddToPlaylist?.let {
        add(CpContextMenuItem("添加到歌单", Icons.Filled.PlaylistAdd, it))
    }
    actions.onShare?.let {
        add(CpContextMenuItem("分享", Icons.Filled.Share, it))
    }
    actions.onShowInfo?.let {
        add(CpContextMenuItem("歌曲信息", Icons.Filled.Info, it))
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
