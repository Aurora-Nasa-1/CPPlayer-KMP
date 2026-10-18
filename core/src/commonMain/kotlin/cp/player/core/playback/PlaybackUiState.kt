package cp.player.core.playback

import cp.player.core.music.TrackSummary
import cp.player.core.model.LyricsInfo

/**
 * 前端播放界面的完整渲染状态（不可变）。
 *
 * 由 [PlaybackController] 经 [PlaybackController.state] 暴露的 StateFlow 推送；
 * 前端**只** collect 此状态渲染，**不**做任何 Song ↔ MediaItem 转换、URL 解析、
 * 歌词格式转换或引擎类型判断。
 */
data class PlaybackUiState(
    val currentTrack: TrackSummary? = null,
    val currentIndex: Int = -1,
    val queue: List<QueueItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /**
     * 正在后台把无损流落地成本地文件（「边播边落盘」）。
     *
     * ⚠️ 这**不代表卡住了** —— 曲子已经在出声（引擎先放流），只是还没落盘，
     * 所以此刻拖动进度条不可用。UI 据此**禁用滑条并说明原因**（见 `SeekAvailability`），
     * 否则用户拖动没反应只会以为播放器坏了。
     */
    val isLocalizing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val lyrics: LyricsState = LyricsState.Idle,
    val lyricsInfo: LyricsInfo? = null,
    val activeLyricIndex: Int = -1,
    val formatInfo: AudioFormatInfo? = null,
    val error: String? = null,
    val sourceId: String? = null,
    /** 是否被收藏（接入 likeSong；未登录时恒为 false）。 */
    val isFavorite: Boolean = false,
    /** 当前在线播放音质等级（standard/exhigh/lossless/hires…）。 */
    val qualityLevel: String = "exhigh",
    /** 睡眠定时剩余毫秒；null 表示未设置。 */
    val sleepTimerRemainingMs: Long? = null,
    /** 睡眠定时模式为"播完当前曲目后暂停"。 */
    val sleepAfterTrack: Boolean = false,
    /**
     * 用户音量（`[0,1]`），即**用户设定的那个值**。
     *
     * 淡入淡出期间引擎的实际音量会从 0 爬到它 —— 那段过程**不**写回这里，
     * 否则淡出走完就成了「用户音量被清成 0」。UI 上的音量条读这个字段：
     * 之前它只能读 [PlaybackController.currentVolume]（普通 getter，不参与重组），
     * 于是桌面播放页与迷你播放器各持一份本地镜像，改动不会互相看见。
     *
     * 放在**参数表末尾**：这个 data class 有二十来项，新字段插在中间会让
     * 「按位置传参」的既有构造点静默错位（编译器只在类型不同时才报错）。
     */
    val volume: Float = 1f,
)