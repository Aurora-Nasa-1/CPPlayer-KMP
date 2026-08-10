package cp.player.kmp.local

/**
 * 本地音乐元信息（旧版最小子集）。
 *
 * 文件路径即唯一标识。已由通用媒体条目模型取代，仅为兼容保留。
 */
@Deprecated("由 cp.player.kmp.media.LocalMediaItem 取代")
data class LocalSongMetadata(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
)
