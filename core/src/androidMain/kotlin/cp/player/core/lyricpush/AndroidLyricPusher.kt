/*
 * 新增文件（非直接移植）。
 * Halcyon 里这套逻辑分散在 PlayerViewModel / PlayerViewModelHelpers 的
 * collect 回调里（每个渠道各写一段「变化了吗 / 该发什么」）。本仓库改成一个
 * 单一协调器，原因是：跨平台核心层只能看到一个 [LyricPusher] 出口，
 * 而「哪些字段变了 → 哪些渠道要重发」这个判断必须只存在一份，否则新增一个渠道
 * 就要回头改播放控制器。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.content.Context
import cp.player.core.playback.SyncedLyricLine
import cp.player.core.util.PlatformContext
import cp.player.core.util.androidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 各渠道共用的「写回系统」出口注册表。
 *
 * 为什么是全局可变单例：`LyricPushMetadataSink` 的实现在 `app-android` 的
 * `PlaybackMediaSessionService` 里，它的创建**晚于** `MusicBackend`（服务是
 * 用户第一次播放时才起来的）。用依赖注入会在服务起来之前拿不到实例，
 * 而「服务还没起」恰恰是最需要静默跳过的时刻 —— 所以这里用可空注册 + 静默跳过。
 */
object LyricPushSinks {
    @Volatile
    var metadataSink: LyricPushMetadataSink? = null

    /** 进程退出 / 后端 reset 时清理，避免持有已销毁的 MediaSession。 */
    fun clear() {
        metadataSink = null
    }
}

/**
 * ColorOS / OPPO 锁屏岛的投放实现。
 *
 * **不走广播**：它写在当前播放项的 `MediaMetadata.extras` 里（见 [OPlusLyricPayload]），
 * 所以这里只负责「算出该写什么」和「什么时候写」，真正落到 MediaSession 的动作交给
 * [LyricPushMetadataSink]（app-android 实现）。
 */
internal class ColorOsLyricPublisher {

    private var enabled = false
    private var mode = OPlusLyricMode.SYSTEM

    private var lastLyricInfo: String? = null
    private var lastRawLyric: String? = null
    private var lastTrackKey: String? = null
    private var lastMode: OPlusLyricMode? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            lastLyricInfo = null
            lastRawLyric = null
            lastTrackKey = null
            lastMode = null
            write(null, null)
        }
    }

    fun setMode(mode: OPlusLyricMode) {
        if (this.mode == mode) return
        this.mode = mode
        // 模式变了就得重发：系统模式要求「没有 rawLyric 字段」，模块模式要求「有」。
        lastMode = null
        publish(lastTrackKey, lastLyrics, force = true)
    }

    private var lastTrack: LyricPushTrack? = null
    private var lastLyrics: List<SyncedLyricLine> = emptyList()

    fun updateSong(track: LyricPushTrack?, lines: List<SyncedLyricLine>) {
        if (!enabled) return
        lastTrack = track
        lastLyrics = lines
        publish(track?.trackKey(), lines, force = track?.trackKey() != lastTrackKey)
    }

    /** 换歌后的补发窗口：ColorOS 有时会用旧快照覆盖一次，稍后强制重写。 */
    fun reapplyIfNeeded() {
        if (!enabled || lastTrack == null) return
        publish(lastTrack?.trackKey(), lastLyrics, force = true)
    }

    private fun publish(trackKey: String?, lines: List<SyncedLyricLine>, force: Boolean) {
        if (!enabled) return
        val track = lastTrack
        if (track == null || trackKey == null) {
            if (lastLyricInfo != null || lastRawLyric != null) {
                write(null, null)
                lastLyricInfo = null
                lastRawLyric = null
                lastTrackKey = null
                lastMode = null
            }
            return
        }

        val lyricInfo = OPlusLyricPayload.build(track, lines, mode)
        val rawLyric = OPlusLyricPayload.rawLyric(lyricInfo)

        // 模式切换后即使 JSON 相同也必须重发（接收端按字段是否存在判断模式）。
        val modeChanged = lastMode != mode
        val action = OPlusLyricPublishPolicy.actionFor(
            currentLyricInfo = lastLyricInfo,
            currentRawLyric = lastRawLyric,
            targetLyricInfo = lyricInfo,
            targetRawLyric = rawLyric,
            force = force || modeChanged,
        )
        when (action) {
            OPlusLyricPublishAction.None -> Unit
            OPlusLyricPublishAction.Clear -> {
                write(null, null)
                lastLyricInfo = null
                lastRawLyric = null
            }
            OPlusLyricPublishAction.Write -> {
                write(lyricInfo, rawLyric)
                lastLyricInfo = lyricInfo
                lastRawLyric = rawLyric
            }
        }
        lastTrackKey = trackKey
        lastMode = mode
    }

    private fun write(lyricInfo: String?, rawLyric: String?) {
        val sink = LyricPushSinks.metadataSink ?: return
        val extras = buildMap {
            if (!lyricInfo.isNullOrBlank()) put(OPlusLyricPayload.LYRIC_INFO_KEY, lyricInfo)
            if (!rawLyric.isNullOrBlank()) put(OPlusLyricPayload.RAW_LYRIC_INFO_KEY, rawLyric)
        }
        runCatching { sink.setMetadataExtras(extras) }
    }
}

/**
 * Android 侧的 [LyricPusher]：把一帧歌词分发给所有已启用的渠道。
 *
 * ### 为什么去重逻辑集中在这里
 * 每个渠道关心的事件粒度不同（词幕要整首 + 进度；SuperLyric 只要当前句；
 * 超级岛 1.5s 一次；锁屏岛只在换歌时写一次）。如果让每个渠道自己从
 * [LyricPushFrame] 里判断「这次该不该动」，那么「歌词换行了」「同一句但进度走了」
 * 「换歌了」这三种变化的判定会被复制 8 份。这里统一算好三个布尔值再分发。
 */
internal class AndroidLyricPusher(
    private val context: Context,
    private val artworkProvider: LyricPushArtworkProvider?,
) : LyricPusher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val lyricon = LyriconBridge(context)
    private val superLyric = SuperLyricBridge()
    private val lyricGetter = LyricGetterBridge(context)
    private val statusBar = StatusBarLyricBridge(context)
    private val liveUpdate = LiveUpdateLyricBridge(context)
    private val island = XiaomiSuperIslandBridge(context, scope, artworkProvider)
    private val colorOs = ColorOsLyricPublisher()

    private var config = LyricPushConfig()
    private var lastTrackKey: String? = null
    private var lastLinesRef: List<SyncedLyricLine>? = null
    private var lastLineIndex = Int.MIN_VALUE
    private var lastPlaying: Boolean? = null
    private var lastPositionPushAtMs = 0L
    private var reapplyJob: Job? = null

    override fun applyConfig(config: LyricPushConfig) {
        val previous = this.config
        this.config = config

        lyricon.setEnabled(config.lyriconEnabled)
        lyricon.setSecondary(config.lyriconSecondary)
        superLyric.setEnabled(config.superLyricEnabled)
        superLyric.setSecondary(config.superLyricSecondary)
        lyricGetter.setEnabled(config.lyricGetterEnabled)
        island.setEnabled(config.xiaomiSuperIslandEnabled)
        island.setConfig(config.xiaomiSuperIsland)
        colorOs.setEnabled(config.colorOsEnabled)
        colorOs.setMode(config.colorOsMode)
        statusBar.setEnabled(config.statusBarLyricEnabled)
        statusBar.setHeadsUpEnabled(config.headsUpLyricEnabled)
        statusBar.setSecondary(config.statusBarSecondary)
        liveUpdate.setEnabled(config.liveUpdateEnabled)
        liveUpdate.setConfig(config.liveUpdateContent, config.liveUpdateDisplay, config.liveUpdateSecondary)

        // 「媒体通知歌词」开关切换时要立刻把通知标题恢复/覆盖，不能等下一帧。
        if (previous.mediaNotificationLyricEnabled != config.mediaNotificationLyricEnabled &&
            !config.mediaNotificationLyricEnabled
        ) {
            LyricPushSinks.metadataSink?.setNotificationLyric(null, null)
        }

        // 任何一个渠道刚被打开：把当前曲目整份重发（否则要等下一次换歌才生效）。
        if (config.anyEnabled && config != previous) {
            forceResend()
        }
        if (!config.anyEnabled) {
            clearAll()
        }
    }

    override fun resend(force: Boolean) {
        forceResend()
    }

    override fun onFrame(frame: LyricPushFrame) {
        if (!config.anyEnabled) return

        val track = frame.track
        val trackKey = track?.trackKey()
        val trackChanged = trackKey != lastTrackKey
        val linesChanged = frame.lines !== lastLinesRef
        val lineChanged = frame.lineIndex != lastLineIndex
        val playChanged = frame.isPlaying != lastPlaying

        // 渠道各自需要的「一次整首」动作。
        if (trackChanged || linesChanged) {
            if (track != null) {
                lyricon.sendSong(track, frame.lines, force = trackChanged)
                superLyric.sendSong(track)
            } else {
                lyricon.clearSong()
            }
            colorOs.updateSong(track, frame.lines)
            if (config.colorOsEnabled) scheduleColorOsReapply()
        }

        lastTrackKey = trackKey
        lastLinesRef = frame.lines
        lastLineIndex = frame.lineIndex
        lastPlaying = frame.isPlaying

        if (playChanged) {
            lyricon.sendPlaybackState(frame.isPlaying)
            if (!frame.isPlaying) {
                liveUpdate.clear()
                island.onPlaybackPaused()
            } else {
                // 恢复播放：实时活动与岛都需要重新出现（换行事件可能不会马上到）。
                liveUpdate.sendLine(track, frame.lines.getOrNull(frame.lineIndex), frame.lines, frame.lineIndex, frame.positionMs)
            }
        }

        val line = frame.lines.getOrNull(frame.lineIndex)
        if (lineChanged || trackChanged || playChanged) {
            superLyric.sendLine(line, frame.lines, frame.lineIndex, force = trackChanged)
            lyricGetter.sendLine(line, frame.lines, frame.lineIndex, force = trackChanged)
            statusBar.sendLine(line, force = trackChanged || playChanged)
            publishMediaNotificationLyric(line, track)
        }

        // 实时活动与岛自带节流/去重，逐帧交给它们即可（词窗口要跟着词走）。
        liveUpdate.sendLine(track, line, frame.lines, frame.lineIndex, frame.positionMs)
        island.sendFrame(
            track = track,
            lines = frame.lines,
            index = frame.lineIndex,
            positionMs = frame.positionMs,
            durationMs = frame.durationMs,
            isPlaying = frame.isPlaying,
        )

        // 词幕是「整首一次 + 我推位置」的模型，位置必须持续给。
        val now = System.currentTimeMillis()
        if (now - lastPositionPushAtMs >= POSITION_PUSH_INTERVAL_MS) {
            lastPositionPushAtMs = now
            lyricon.sendPosition(frame.positionMs)
        }
    }

    override fun close() {
        config = LyricPushConfig()
        clearAll()
        runCatching { scope.cancel() }
    }

    private fun forceResend() {
        lastTrackKey = null
        lastLinesRef = null
        lastLineIndex = Int.MIN_VALUE
        lastPlaying = null
    }

    private fun clearAll() {
        reapplyJob?.cancel()
        reapplyJob = null
        runCatching { lyricon.clearSong() }
        runCatching { superLyric.sendStop() }
        runCatching { lyricGetter.clearLyric() }
        runCatching { statusBar.clearLyric() }
        runCatching { liveUpdate.clear() }
        runCatching { island.clear() }
        runCatching { colorOs.setEnabled(false) }
        runCatching { LyricPushSinks.metadataSink?.setMetadataExtras(emptyMap()) }
        runCatching { LyricPushSinks.metadataSink?.setNotificationLyric(null, null) }
        lastTrackKey = null
        lastLinesRef = null
        lastLineIndex = Int.MIN_VALUE
        lastPlaying = null
    }

    /**
     * 把当前歌词写进媒体通知的标题（蓝牙 / 车机 / 媒体中心读的就是它）。
     *
     * 停止时不写 null：由 [applyConfig] 关开关时统一恢复，避免「暂停时通知标题闪回曲名、
     * 播放时又变歌词」的抖动。
     */
    private fun publishMediaNotificationLyric(line: SyncedLyricLine?, track: LyricPushTrack?) {
        if (!config.mediaNotificationLyricEnabled) return
        val sink = LyricPushSinks.metadataSink ?: return
        val main = line?.lineTextOrNull()?.takeIf { !it.isMusicSymbolOnly() }
        if (main == null) {
            runCatching { sink.setNotificationLyric(null, null) }
            return
        }
        val secondary = line.secondaryText(config.mediaNotificationSecondary)
            ?: track?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" - ") }
        runCatching { sink.setNotificationLyric(main, secondary) }
    }

    private fun scheduleColorOsReapply() {
        reapplyJob?.cancel()
        reapplyJob = scope.launch {
            delay(OPlusLyricPublishPolicy.COMPAT_REAPPLY_DELAY_MS)
            runCatching { colorOs.reapplyIfNeeded() }
        }
    }

    private companion object {
        /**
         * 词幕位置推送间隔。
         *
         * 上游的 `DEFAULT_POSITION_UPDATE_INTERVAL` 是 41ms，但那个值是为**它自己**的
         * 高频轮询准备的；本仓库的帧率上限就是播放器的位置轮询（约 200ms），
         * 再密也只是重复同一个值。这里按 250ms 节流，词幕侧靠插值补帧。
         */
        const val POSITION_PUSH_INTERVAL_MS = 250L
    }
}

actual fun createLyricPusher(context: PlatformContext): LyricPusher {
    val android = context.androidContext() ?: return NoopLyricPusher
    return AndroidLyricPusher(android.applicationContext, LyricPushArtworkProviders.current())
}

/**
 * 封面来源的可选注册点（同 [LyricPushSinks] 的理由：app 侧晚于 core 初始化）。
 */
object LyricPushArtworkProviders {
    @Volatile
    private var provider: LyricPushArtworkProvider? = null

    fun register(provider: LyricPushArtworkProvider?) {
        this.provider = provider
    }

    fun current(): LyricPushArtworkProvider? = provider
}

/** 桌面端与「未注入 ArtworkProvider」时的空实现。 */
internal object NoopLyricPusher : LyricPusher {
    override fun applyConfig(config: LyricPushConfig) = Unit
    override fun resend(force: Boolean) = Unit
    override fun onFrame(frame: LyricPushFrame) = Unit
    override fun close() = Unit
}
