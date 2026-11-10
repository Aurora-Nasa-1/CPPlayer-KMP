package cp.player.core.download

import kotlinx.serialization.Serializable

/**
 * 下载伴生云数据元数据（落盘至 `${basePath}.json`）。
 * 记录歌曲所属的原始媒体标识、标题、艺术家、专辑、时长、封面等云端数据，
 * 供离线环境下的曲库重建、快速索引及弱网切歌秒开。
 */
@Serializable
data class DownloadCloudMetadata(
    val mediaId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val qualityLevel: String? = null,
    val downloadedAt: Long = 0L,
)
