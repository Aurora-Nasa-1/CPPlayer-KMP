package cp.player.core.playback

import cp.player.core.music.TrackSummary
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 前端播放控制的**唯一入口**。
 *
 * UI 只与此接口对话；内部由 [PlaybackControllerImpl] 实现，
 * 协调 [PlatformPlayer]（播放）+ [cp.player.core.music.UnifiedMusicSource]（取 URL/详情）
 * + [cp.player.core.api.MusicApiService]（歌词/scrobble）。
 *
 * 前端禁止：直接调 [cp.player.core.music.UnifiedMusicSource]、做 Song ↔ MediaItem 转换、
 * 感知引擎类型、轮询位置。一切差异由此接口及其 StateFlow 屏蔽。
 */
interface PlaybackController {
    /** 完整渲染状态流。前端 collect 后直接渲染。 */
    val state: StateFlow<PlaybackUiState>

    /**
     * seek 最终没能生效的事件流（宽限期已过、乐观值已放弃）。
     *
     * 前端应据此提示用户。**不消费它**的话，一次失败的 seek 只能表现为
     * 「进度条自己弹回原位」——用户无法区分「我拖错了」和「这个音源不能定位」。
     * 是事件流不是状态：重新订阅时不该被重放。
     */
    val seekFailures: SharedFlow<SeekFailure>

    // ============ 单曲/队列 ============

    /** 播放单首曲目（替换当前队列，仅含此曲）。 */
    suspend fun play(mediaId: String) {
        playQueue(listOf(mediaId), startIndex = 0)
    }

    /** 播放指定曲目列表（替换队列），从 [startIndex] 起。 */
    suspend fun playQueue(mediaIds: List<String>, startIndex: Int = 0, sourceId: String? = null)

    /** 设置队列但不立即播放。 */
    suspend fun setQueue(mediaIds: List<String>, startIndex: Int = 0, sourceId: String? = null)

    /**
     * 在当前队列尾部追加。
     *
     * 队列条目按 `mediaId` **唯一**（队列弹层用它做 LazyColumn 的 key）：
     * 已在队列里的同一首不会再追加。
     */
    suspend fun addToQueue(mediaId: String)

    /**
     * 「下一首播放」：插到**当前曲目之后**，本曲播完立刻轮到它。
     *
     * 随机模式下同样插在当前曲的下一个播放位（不是队列末尾 —— 电台/长队列里
     * 追加到末尾意味着几小时后才轮到，等于没加）。该曲已在队列其他位置时，
     * 等价于把它**移到**下一首，而不是重复添加。没有在播曲目时退化为普通追加。
     *
     * ⚠️ 刻意**不给默认实现**：默认成 `addToQueue` 会与本条语义完全相反，任何未覆写的
     * 实现 / 测试替身都会把「下一首播放」静默做成「追加队尾」。抽象化让编译器强制实现方
     * 表态（本仓唯一实现 [PlaybackControllerImpl] 已覆写）。
     */
    suspend fun addNextToQueue(mediaId: String)

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

    // ============ 音效 ============

    /**
     * 本平台对音效各能力的支持情况（见 [AudioEffectCapabilities]）。
     *
     * 由平台播放器直接透出。设置页据此**明示禁用**不支持的项，而不是让用户
     * 拖一个什么都不发生的滑杆（桌面 rodio 栈只有音量，没有效果链）。
     *
     * 默认 [AudioEffectCapabilities.NONE]：与 [setAudioEffect] 一样给默认实现，
     * 是为了让测试假播放器与最小装配路径**不必改动**
     * （与 [PlatformPlayer.audioEffectCapabilities] 同一套思路）。
     */
    val audioEffectCapabilities: AudioEffectCapabilities
        get() = AudioEffectCapabilities.NONE

    /**
     * 应用一份音效配置（全量覆盖）。见 [PlatformPlayer.applyAudioEffect] 的契约。
     *
     * 配置的**持久化不在这里**：写盘是前端的事（`AppModel` 读设置页的改动落盘），
     * 控制器只负责把当前配置作用到音频链上。这样重启后的恢复路径只有一条
     * （启动时读盘 → 调本方法），不会出现「控制器自己也记一份」的双份事实源。
     *
     * 默认空实现：不支持音效的实现无需覆写。
     */
    fun setAudioEffect(config: AudioEffectConfig) {}

    // ============ 淡入淡出 ============

    /**
     * 应用一份淡入淡出配置。设置页改完调这里。
     *
     * 与 [setAudioEffect] 的区别：淡入淡出是**事件驱动**的（切歌 / 曲末才动），
     * 不是一条常驻的效果链，所以不存在"换曲后要重放"的问题 ——
     * 一次设置立刻生效到**下一次**过渡事件上。
     *
     * 默认空实现：让测试假播放器与最小装配路径不必改动。
     */
    fun setFade(config: FadeConfig) {}

    /** 当前生效的淡入淡出配置（设置页用来回填控件）。默认关闭。 */
    fun fadeConfiguration(): FadeConfig = FadeConfig.OFF

    // ============ 其它 ============

    fun setVolume(volume: Float)
    fun release()

    companion object {
        /** [setSleepTimer] 特殊值：播完当前曲目后暂停。 */
        const val SLEEP_AFTER_TRACK = 0
    }
}