package cp.player.kmp.playback

/**
 * 当前播放音频的格式信息（由平台播放器提取；用于 UI 展示 "Hi-Res / 24bit 96kHz" 等）。
 *
 * 任何字段不可解析时为 null；前端只读取，不感知底层引擎差异。
 */
data class AudioFormatInfo(
    val codecName: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    val mimeType: String? = null,
) {
    /** 归一化的音质等级标签，用于 UI 摘要展示。 */
    val qualityLabel: String
        get() = buildList {
            codecName?.let { add(it) }
            bitDepth?.let { add("${it}bit") }
            sampleRate?.let { add("${it / 1000}kHz") }
        }.joinToString(" · ").ifEmpty { "标准" }
}