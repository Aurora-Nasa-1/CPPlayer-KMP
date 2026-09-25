package cp.player.kmp.playback

import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * 前端播放控制的**唯一入口**。
 *
 * UI 只与此接口对话；内部由 [PlaybackControllerImpl] 实现，
 * 协调 [PlatformPlayer]（播放）+ [cp.player.kmp.music.UnifiedMusicSource]（取 URL/详情）
 * + [cp.player.kmp.api.MusicApiService]（歌词/scrobble）。
 *
 * 前端禁止：直接调 [cp.player.kmp.music.UnifiedMusicSource]、做 Song ↔ MediaItem 转换、
 * 感知引擎类型、轮询位置。一切差异由此接口及其 StateFlow 屏蔽。
 */
interface PlaybackController {
    /** 完整渲染状态流。前端 collect 后直接渲染。 */
    val state: StateFlow<PlaybackUiState>

    // ============ 单曲/队列 ============

    /** 播放单首曲目（替换当前队列，仅含此曲）。 */
    suspend fun play(mediaId: String) {
        playQueue(listOf(mediaId), startIndex = 0)
    }

    /** 播放指定曲目列表（替换队列），从 [startIndex] 起。 */
    suspend fun playQueue(mediaIds: List<String>, startIndex: Int = 0, sourceId: String? = null)

    /** 设置队列但不立即播放。 */
    suspend fun setQueue(mediaIds: List<String>, startIndex: Int = 0, sourceId: String? = null)

    /** 在当前队列尾部追加。 */
    suspend fun addToQueue(mediaId: String)

    /** 从队列中移除指定索引；若移除的是当前曲目，按规则跳到下一首。 */
    suspend fun removeQueueItem(index: Int)

    /** 在队列内移动条目（拖拽重排），from 与 to 均为当前队列索引。 */
    suspend fun moveQueueItem(from: Int, to: Int)

    /** 清空队列并停止。 */
    fun clearQueue()

    /** 跳到队列指定索引并播放。 */
    suspend fun playAt(index: Int)

    // ============ 播控 ============

    fun togglePlayPause()
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun setRepeatMode(mode: RepeatMode)
    fun toggleShuffle()

    // ============ 收藏 ============

    /** 当前账号已收藏的歌曲 ID 集合（裸资源 ID，非 mediaId）。未登录/未加载时为空集。 */
    val likedIds: StateFlow<Set<String>>

    /** 切换当前播放曲目的收藏状态（乐观更新，失败自动回滚）。 */
    suspend fun toggleFavorite()

    /** 切换指定 mediaId 曲目的收藏状态（供列表/弹层使用）。 */
    suspend fun toggleFavoriteFor(mediaId: String)

    /** 强制重新拉取收藏列表（登录/登出/切换账号后调用）。 */
    suspend fun refreshFavorites()

    // ============ 音质 ============

    /** 设置在线播放音质等级（standard/exhigh/lossless/hires…），作用于后续加载的曲目。 */
    fun setQuality(level: String)

    // ============ 睡眠定时 ============

    /**
     * 设置睡眠定时：[minutes] > 0 表示 N 分钟后自动暂停；
     * 传 [SLEEP_AFTER_TRACK] 表示播完当前曲目后暂停。
     */
    fun setSleepTimer(minutes: Int)

    /** 取消睡眠定时。 */
    fun cancelSleepTimer()

    // ============ 歌词 ============

    /**
     * 从 [PlaybackUiState.lyrics] Flow 已合入主状态；
     * 此方法强制重新拉取当前曲目的歌词。
     */
    suspend fun refreshLyrics()

    // ============ 其它 ============

    fun setVolume(volume: Float)
    fun release()

    companion object {
        /** [setSleepTimer] 特殊值：播完当前曲目后暂停。 */
        const val SLEEP_AFTER_TRACK = 0
    }
}