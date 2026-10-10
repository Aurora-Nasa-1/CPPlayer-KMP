package cp.player.core.media

import kotlinx.serialization.Serializable

/**
 * 本地音频条目的**深度元数据**（可空子对象）。
 *
 * ## 为什么不直接平铺进 [LocalMediaItem]
 *
 * `LocalMediaItem` 是通用媒体条目，同时表示音频与视频。把 genre / year / trackNumber
 * / bitrate 这类音频专属字段平铺进去，会让视频条目被迫携带一堆恒为 null 的字段，
 * 语义变胖。因此收进一个可空子对象：音频条目填充，视频条目为 null。
 *
 * ## 兼容性
 *
 * 全部字段带默认值，保证旧版 `index.json`（无 metadata 键）反序列化不崩。
 */
@Serializable
data class LocalTrackMetadata(
    /** 专辑艺人（合辑场景与 [LocalMediaItem.artist] 不同） */
    val albumArtist: String? = null,
    /** 流派 */
    val genre: String? = null,
    /** 发行年份 */
    val year: Int? = null,
    /** 音轨号 */
    val trackNumber: Int? = null,
    /** 碟片号 */
    val discNumber: Int? = null,
    /** 标称码率（kbps）；VBR 为平均值 */
    val bitrateKbps: Int? = null,
    /** 采样率（Hz） */
    val sampleRateHz: Int? = null,
    /** 位深（bit）；有损格式无此概念，为 null */
    val bitDepth: Int? = null,
    /** 声道数 */
    val channels: Int? = null,
    /** 是否带内嵌封面（决定封面是否值得懒提取） */
    val hasEmbeddedCover: Boolean = false,
    /** 音频编码名，如 `FLAC` / `MP3` / `AAC` / `Opus` / `PCM` */
    val codec: String? = null,
) {
    /**
     * 音质短标签，如 `FLAC 24/96`、`320k MP3`、`Opus`。
     *
     * UI 列表副标题直接展示；无损优先显示位深/采样率，有损显示码率。
     */
    val qualityLabel: String?
        get() {
            val codecName = codec
            val depth = bitDepth
            val rate = sampleRateHz
            val kbps = bitrateKbps
            return when {
                codecName == null && kbps == null -> null
                // 无损：位深 / 采样率比码率更有信息量（44.1k 这类非整数要保留一位小数）
                depth != null && rate != null -> "${codecName ?: "无损"} $depth/${formatKhz(rate)}"
                kbps != null -> "$kbps${if (kbps <= 0) "" else "k"}${codecName?.let { " $it" } ?: ""}"
                codecName != null -> codecName
                else -> null
            }
        }

    companion object {
        /** 采样率转 kHz 文案：44100 → `44.1`，48000 → `48`。commonMain 无 String.format，手写。 */
        private fun formatKhz(sampleRateHz: Int): String {
            if (sampleRateHz % 1000 == 0) return "${sampleRateHz / 1000}"
            val tenths = sampleRateHz / 100
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}

/**
 * 扫描期从音频文件读出的原始标签（不持久化，仅用于构造条目）。
 *
 * 与 [LocalTrackMetadata] 的差别：它也承载 title / artist / album / duration ——
 * 这几个字段在 [LocalMediaItem] 上是顶层字段，组装后不再重复存。
 */
data class AudioTagInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long = 0L,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val hasEmbeddedCover: Boolean = false,
    val codec: String? = null,
)

/** 由 [AudioTagInfo] 组装可持久化的深度元数据。 */
fun AudioTagInfo.toTrackMetadata(): LocalTrackMetadata = LocalTrackMetadata(
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    channels = channels,
    hasEmbeddedCover = hasEmbeddedCover,
    codec = codec,
)
