package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatPublishDate
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.BackendResult
import cp.player.core.music.SongDetailInfo
import cp.player.core.music.TrackSummary
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「歌曲信息」弹窗 —— 全应用**唯一**一份实现。
 *
 * ## 为什么要抽出来
 *
 * 歌曲的「更多」入口在手机端有**五处**（首页推荐 / 首页最近播放 / 歌单详情 /
 * 专辑详情 / 搜索），它们共用同一个 [SongOptionsSheet]。此前只有歌单详情
 * 传了 `onShowInfo`，于是「歌曲信息」这一格只在歌单详情里出现 —— 同一个弹层
 * 在不同页面长得不一样（详见 [SongOptionsSheet] 的 KDoc）。收口时把这份
 * AlertDialog 从 `PlaylistDetailScreen` 内联代码搬到这里，五处复用同一份：
 * 加载态、失败通知、字段顺序、文案都只有一处可改。
 *
 * ## 内容与来源
 *
 * 解析收敛在 `MusicRepository.getSongDetailInfo`（`song/detail` 接口），
 * UI 只拿强类型 [SongDetailInfo]。拿不到时通知用户并自行关闭 —— 停在一个
 * 永远转圈的弹窗上比什么都不做更糟。
 *
 * ⚠️ 播放页的「歌曲信息」（`SongInfoDialog`）**不是**这个：那份展示的是
 * **播放链路**信息（编码 / 采样率 / 位深 / 码率 / 声道 / 歌词源 / MIME），
 * 数据来自正在播放的会话；这一份展示的是**曲库元数据**（发行时间 / 评论数 /
 * MV / 付费类型）。两者数据源不同，别合并。
 */
@Composable
fun SongDetailInfoDialog(
    track: TrackSummary,
    onDismiss: () -> Unit,
) {
    val s = cpStrings()
    var info by remember(track.id) { mutableStateOf<SongDetailInfo?>(null) }
    LaunchedEffect(track.id) {
        info = withContext(Dispatchers.IO) {
            val fallback = SongDetailInfo(
                songId = track.id,
                name = track.name,
                artist = track.artist,
                album = track.album ?: "",
                durationMs = track.durationMs,
            )
            (runCatching { AppModel.musicRepository.getSongDetailInfo(track.id, fallback) }.getOrNull()
                    as? BackendResult.Success)?.data
        }
        if (info == null) {
            UiEvents.notify(s.library.loadTrackInfoFailed)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(s.library.songDetails, maxLines = 1) },
        text = {
            val current = info
            if (current == null) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CpLoadingIndicator(Modifier.size(32.dp))
                }
            } else {
                Text(
                    buildString {
                        // 整段进剪贴板 ⇒ 逐行都是文案。`extra` 收「有值才出现」的那些行
                        // （发行时间 / 评论数 / MV / 码率 / 付费类型），调用方只管判断有没有值。
                        append(
                            s.library.shareTrack(
                                name = current.name,
                                artist = current.artist,
                                album = current.album.ifBlank { s.player.unknownAlbum },
                                duration = formatTimeMs(current.durationMs),
                                extra = buildString {
                                    current.publishTimeMs?.let {
                                        append(
                                            s.library.songShareLine(
                                                s.library.publishDate,
                                                formatPublishDate(it),
                                            ),
                                        )
                                    }
                                    current.commentCount?.let {
                                        append(s.library.songShareLine(s.library.commentCount, "$it"))
                                    }
                                    // MV ID 是标识符，不翻译。
                                    current.mvId?.let { append(s.library.songShareLine("MV ID", "$it")) }
                                    current.maxBitrate?.let {
                                        append(
                                            s.library.songShareLine(
                                                s.library.maxBitrate,
                                                "${it / 1000}kbps",
                                            ),
                                        )
                                    }
                                    current.fee?.let { fee ->
                                        append(
                                            s.library.songShareLine(
                                                s.library.paidType,
                                                when (fee) {
                                                    0 -> s.library.paidFree
                                                    // 「VIP」是音源侧的等级名，两端一致，不翻译。
                                                    1 -> "VIP"
                                                    4 -> s.library.paidPurchased
                                                    8 -> s.library.paidLowQualityFree
                                                    else -> s.library.paidUnknown
                                                },
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                        // 「歌曲 ID」是标识符标签，两端都用 ID。
                        append(s.library.songShareLine("ID", current.songId))
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { CpText(s.common.dismiss) }
        },
    )
}

