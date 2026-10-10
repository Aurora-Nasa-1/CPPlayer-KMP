package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine

/**
 * 对外歌词投放的**曲目身份**。
 *
 * 刻意只带「推送用得上的字段」而不是复用 [cp.player.core.music.TrackSummary]：
 * 投放渠道要的是「这是什么歌」，不是「怎么跳转到它的详情页」。少带字段能让
 * 两个平台的实现不必知道 UI 层的模型。
 *
 * @param sourceId 音源标识（对应 Halcyon 的 `onlineSource`）。与 [id] 一起组成
 *   ColorOS 的 `songId`，用于接收端判断「是不是同一首歌」。本地曲目为 null。
 */
data class LyricPushTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourceId: String? = null,
)

/**
 * 一次投放快照。
 *
 * [PlaybackController] 每帧（约 200ms，见 `PlatformPlayer` 的位置轮询）产出一个，
 * 各渠道 bridge 自行去重与节流 —— 这里**不做**节流，因为不同渠道的最优频率不同
 * （词幕要连续位置、状态栏只要换行、超级岛 1.5s 一次），统一节流必然有一方被牺牲。
 *
 * @param lineIndex 当前歌词行下标（-1 = 无歌词 / 未定位）。由 `PlaybackUiState.activeLyricIndex`
 *   直接给出，本体系**不重复计算**，避免出现第二个事实源。
 */
data class LyricPushFrame(
    val track: LyricPushTrack?,
    val lines: List<SyncedLyricLine>,
    val lineIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)

/**
 * 歌词对外投放的**唯一出口**。
 *
 * 平台无关：commonMain 只认这个接口，Android 实现放在 `core/src/androidMain`，
 * 桌面端是空实现（桌面没有「词幕 / 状态栏歌词」这类系统级接收方，但设置项仍然要
 * 能在桌面被读出来，否则换平台就丢配置）。
 *
 * ### 调用线程
 * 由 `PlaybackController` 所在协程域调用（Android 上是主线程）。实现方若要做 IPC，
 * 应自行切到 IO —— 这里不强制，因为「换行」这类事件必须按序到达。
 */
interface LyricPusher {
    /** 应用新的配置（设置页改动 / 启动恢复）。实现方应按开关启停各渠道。 */
    fun applyConfig(config: LyricPushConfig)

    /** 播放状态或曲目变化后要求全量重发（换歌、恢复播放、开关刚打开）。 */
    fun resend(force: Boolean)

    /** 每帧推送。实现方必须自己做去重，调用方不做节流。 */
    fun onFrame(frame: LyricPushFrame)

    /** 释放所有渠道（进程结束 / 后端 reset）。 */
    fun close()
}

/**
 * 「写回系统媒体会话」的通道（ColorOS 锁屏岛 + 媒体通知歌词专用）。
 *
 * 这两个渠道走的是 **MediaSession 的 MediaMetadata**，而 MediaSession 在
 * `app-android` 的 `PlaybackMediaSessionService` 里 —— `core` 反向依赖不到它。
 * 因此和 `PlatformNotifications` 一样用**回调桥**：core 定义接口，app-android 注册实现。
 */
interface LyricPushMetadataSink {
    /**
     * 覆盖当前播放项的 MediaMetadata extras。
     *
     * @param extras 要写入的键值；空 map 表示「清除之前写过的歌词字段」。
     */
    fun setMetadataExtras(extras: Map<String, String>)

    /**
     * 覆盖通知/蓝牙设备看到的标题与副标题（媒体通知歌词）。
     *
     * @param title null = 恢复成真实曲名。
     * @param subtitle null = 恢复成真实歌手名。
     */
    fun setNotificationLyric(title: String?, subtitle: String?)
}

/**
 * 平台工厂：Android 返回真实实现，桌面返回空实现。
 *
 * 与既有的 `createLyricsPluginService` / `createPlatformPlayer` 同一套 expect/actual 惯例。
 */
expect fun createLyricPusher(context: cp.player.core.util.PlatformContext): LyricPusher
