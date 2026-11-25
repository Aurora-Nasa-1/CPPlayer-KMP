package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatBytes
import cp.player.app.ui.util.formatPublishDate
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.BackendResult
import cp.player.core.media.LocalMediaItem
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
 *
 * ## 本地曲目
 *
 * `local://` 条目没有在线 `song/detail`，此前与在线曲目走同一条路径 ⇒ 请求必然
 * 失败 ⇒ 弹窗先转圈、再弹「获取歌曲信息失败」并自动关闭（用户看到的正是
 * 「Info 打不开」）。现在本地曲目**不发网络请求**，直接展示 [LocalMediaItem] 里
 * 的文件信息（大小 / 格式 / 路径 / 来源）；索引里找不到时也退化为列表已知字段，
 * **不会**因为查不到而关闭弹窗。
 */
@Composable
fun SongDetailInfoDialog(
    track: TrackSummary,
    onDismiss: () -> Unit,
) {
    val s = cpStrings()
    val localPath = remember(track.id) { localTrackPath(track.id) }
    val isLocal = localPath != null
    val localItem = remember(track.id) {
        localPath?.let { path ->
            runCatching {
                cp.player.app.AppModel.localMedia.items().value.firstOrNull { it.path == path }
            }.getOrNull()
        }
    }
    var info by remember(track.id) { mutableStateOf<SongDetailInfo?>(null) }
    LaunchedEffect(track.id) {
        // 本地曲目没有在线元数据可查：直接渲染本地文件信息，别去网上撞一次必然失败。
        if (isLocal) return@LaunchedEffect
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
    CpAlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(s.library.songDetails, maxLines = 1) },
        text = {
            val current = info
            if (isLocal) {
                // 整段多行文本：CpText 默认 maxLines=1 会只剩第一行（歌曲名），必须放开。
                CpText(
                    buildLocalTrackInfoText(track, localItem, localPath.orEmpty()),
                    maxLines = Int.MAX_VALUE,
                )
            } else if (current == null) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CpLoadingIndicator(Modifier.size(32.dp))
                }
            } else {
                // 整段多行文本：CpText 默认 maxLines=1 会只剩第一行（歌曲名），必须放开。
                CpText(
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
                    },
                    maxLines = Int.MAX_VALUE,
                )            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { CpText(s.common.dismiss) }
        },
    )
}

/** `local://audio/<path>` / `local://video/<path>` → 文件路径；非本地返回 null。 */
private fun localTrackPath(mediaId: String): String? {
    if (!mediaId.startsWith("local://")) return null
    return mediaId.removePrefix("local://audio/").removePrefix("local://video/")
        .takeIf { it.isNotBlank() }
}

/**
 * 本地曲目的信息正文。
 *
 * [item] 为 null（列表条目还没进索引 / 路径已失效）时只渲染列表已知字段，
 * 仍然保留路径 —— 那正是用户点「信息」最想确认的东西。
 */
@Composable
private fun buildLocalTrackInfoText(
    track: TrackSummary,
    item: LocalMediaItem?,
    path: String,
): String {
    val s = cpStrings()
    val metadata = item?.metadata
    return buildString {
        append(
            s.library.shareTrack(
                name = track.name,
                artist = track.artist,
                album = track.album?.takeIf { it.isNotBlank() } ?: s.player.unknownAlbum,
                duration = formatTimeMs(track.durationMs.takeIf { it > 0 } ?: item?.durationMs ?: 0L),
                extra = buildString {
                    item?.let { media ->
                        append(
                            s.library.songShareLine(
                                s.player.infoSource,
                                if (media.source == cp.player.core.media.LocalMediaOrigin.DOWNLOADED) {
                                    s.player.downloaded
                                } else {
                                    s.downloads.localImported
                                },
                            ),
                        )
                        if (media.sizeBytes > 0L) {
                            append(s.library.songShareLine(s.player.infoSize, formatBytes(media.sizeBytes)))
                        }
                    }
                    // 质量标签（`FLAC 24/96` / `320k MP3`）优先用已解析的元数据；
                    // 未解析时退回扩展名，至少不是空白。
                    val quality = metadata?.qualityLabel
                        ?: path.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase()
                    quality?.let { append(s.library.songShareLine(s.player.infoFormat, it)) }
                },
            ),
        )
        // 文件路径是标识符式数据，不翻译标签以外的内容。
        append(s.library.songShareLine(s.player.infoPath, path))
    }
}

