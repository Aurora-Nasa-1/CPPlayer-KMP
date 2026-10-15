/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/LyriconBridge.kt
 * Changes: 模型换成 LyricPushTrack / SyncedLyricLine；ProviderLogo 由 R.drawable 改为
 *          Canvas 合成的 Bitmap（core 无资源目录）；`backgroundText/backgroundWords`
 *          双轨合并到本仓库的单轨行模型；签名哈希去掉 path/song 可变字段。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.content.Context
import android.util.Log
import cp.player.core.playback.SyncedLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.service.addConnectionListener

/**
 * 词幕（Lyricon）投放。
 *
 * 协议：**AIDL 绑定服务**。本应用作为「Provider」把自己的播放器状态发布给
 * 词幕（`provider.register()`），词幕侧再渲染桌面歌词 / 状态栏 / 车机。
 * 曲目与整首歌词一次性 `setSong`，之后只需 `setPosition` 推进进度 —— 与
 * ColorOS 的「整首 LRC 一次给」是同一个思路，但这里是活的 IPC。
 *
 * ### 生命周期上的两个坑（上游踩过，这里保留处理）
 * 1. 词幕可能在应用启动之后才被拉起，**连接是异步的**。必须挂 connection listener，
 *    在 `onConnected` / `onReconnected` 时把当前曲目重发一次，否则「先开播放器、
 *    后开词幕」的路径上词幕永远是空的。
 * 2. `setSong` 参数量很大（整首逐字歌词），不能每帧发。用签名去重，只在
 *    曲目或歌词内容变化时才发。
 */
internal class LyriconBridge(private val context: Context) {

    private var provider: LyriconProvider? = null
    private var enabled = false
    private var secondary = LyricSecondaryMode.TRANSLATION

    private var lastTrack: LyricPushTrack? = null
    private var lastLines: List<SyncedLyricLine> = emptyList()
    private var lastSignature: String? = null
    private var lastPlaying: Boolean? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (enabled) {
            initialize()
        } else {
            runCatching { provider?.unregister() }
            runCatching { provider?.destroy() }
            provider = null
            resetState()
        }
    }

    fun setSecondary(mode: LyricSecondaryMode) {
        if (secondary == mode) return
        secondary = mode
        lastSignature = null
        resendLastTrack()
    }

    private fun initialize() {
        if (provider != null) return
        runCatching {
            val logo = ProviderLogo.fromBitmap(LyricPushArtwork.appIcon(96), false)
            val created = LyriconFactory.createProvider(context = context, logo = logo)
            created.service.addConnectionListener {
                onConnected {
                    // 词幕后启动 / 重连：把当前曲目补发一次。
                    resendLastTrack()
                }
                onReconnected { resendLastTrack() }
                onDisconnected { Log.d(TAG, "Lyricon disconnected") }
                onConnectTimeout { Log.w(TAG, "Lyricon connection timeout") }
            }
            created.register()
            provider = created
            Log.i(TAG, "Lyricon provider registered")
        }.onFailure { Log.w(TAG, "Unable to initialize Lyricon provider", it) }
    }

    /** 曲目或歌词变化时调用；返回后 bridge 持有最新快照，供重连时补发。 */
    fun sendSong(track: LyricPushTrack, lines: List<SyncedLyricLine>, force: Boolean = false) {
        if (!enabled) return
        lastTrack = track
        lastLines = lines
        val signature = signature(track, lines)
        // provider 非空 ≠ 已连上：注册是广播、回连是异步的。这里必须留痕，否则
        // 「整首歌词没过去」与「过去了但接收端没收」在日志里长得一模一样。
        val active = provider ?: run {
            Log.d(TAG, "setSong skipped: provider not created yet")
            return
        }
        if (!force && signature == lastSignature) {
            runCatching { active.player.setDisplayTranslation(secondary != LyricSecondaryMode.OFF) }
            return
        }
        runCatching {
            val accepted = active.player.setSong(
                io.github.proify.lyricon.lyric.model.Song(
                    id = track.id,
                    name = track.title,
                    artist = track.artist,
                    duration = track.durationMs,
                    lyrics = lines.mapIndexed { index, line -> line.toRichLyric(index, lines) },
                ),
            )
            active.player.setDisplayTranslation(secondary != LyricSecondaryMode.OFF)
            active.player.setDisplayRoma(secondary == LyricSecondaryMode.PRONUNCIATION)
            lastSignature = signature
            // 逐字是否真的进了接收端，只能靠「有多少行带 words」这一个数字判 ——
            // 接收端拿不到词级时间轴时，界面表现与「位置不动」几乎一样。
            Log.i(
                TAG,
                "setSong accepted=$accepted lines=${lines.size} " +
                    "linesWithWords=${lines.count { it.words.isNotEmpty() }} " +
                    "durationMs=${track.durationMs}",
            )
        }.onFailure { Log.w(TAG, "Unable to publish lyrics to Lyricon", it) }
    }

    fun sendPlaybackState(playing: Boolean) {
        if (!enabled) return
        if (lastPlaying == playing) return
        lastPlaying = playing
        runCatching { provider?.player?.setPlaybackState(playing) }
    }

    /**
     * 进度推进。词幕自己插值，所以这里**不必**每帧都发（上游用 41ms 间隔）。
     *
     * ### 为什么要看返回值（2026-10-15 补）
     * `RemotePlayer` 的每个写方法都返回 `Boolean`，而上游把 `setPosition` 的结果丢掉了。
     * 真机反馈「逐字歌词到了词幕、但高亮卡在第一个字前面」时，第一件要分清的事就是：
     * 位置**有没有被接收端接受**，以及**发出去的值是不是一直停在 0**。
     * 这两种原因的表象完全一样，只看界面分不出来，所以这里按节流打一行日志。
     */
    fun sendPosition(positionMs: Long) {
        if (!enabled) return
        val accepted = runCatching { provider?.player?.setPosition(positionMs) }.getOrNull()
        logPosition(positionMs, accepted)
    }

    private var lastPositionLogAtMs = 0L
    private var lastLoggedPositionAccepted: Boolean? = null

    /**
     * 位置推送的日志节流。
     *
     * 推送频率是 4Hz，逐帧打会把日志刷爆；但**只在「结果变化」时打又看不到位置在不在走**。
     * 所以两者取并集：结果一变就打，否则每 [POSITION_LOG_INTERVAL_MS] 打一次采样。
     */
    private fun logPosition(positionMs: Long, accepted: Boolean?) {
        val now = System.currentTimeMillis()
        if (accepted == lastLoggedPositionAccepted && now - lastPositionLogAtMs < POSITION_LOG_INTERVAL_MS) {
            return
        }
        lastPositionLogAtMs = now
        lastLoggedPositionAccepted = accepted
        Log.d(TAG, "setPosition($positionMs) accepted=$accepted connected=${provider != null}")
    }

    fun clearSong() {
        lastTrack = null
        lastLines = emptyList()
        lastSignature = null
        lastPlaying = null
        runCatching { provider?.player?.setSong(null) }
    }

    fun destroy() {
        enabled = false
        runCatching { provider?.unregister() }
        runCatching { provider?.destroy() }
        provider = null
        resetState()
    }

    private fun resetState() {
        lastTrack = null
        lastLines = emptyList()
        lastSignature = null
        lastPlaying = null
        // 日志节流状态跟着清：重新开启渠道后第一条位置日志必须是可见的。
        lastLoggedPositionAccepted = null
        lastPositionLogAtMs = 0L
    }

    private fun resendLastTrack() {
        val track = lastTrack ?: return
        sendSong(track, lastLines, force = true)
    }

    private fun SyncedLyricLine.toRichLyric(index: Int, lines: List<SyncedLyricLine>): RichLyricLine {
        val end = lineEndMs(lines, index)
        return RichLyricLine(
            begin = time.coerceAtLeast(0L),
            end = end,
            text = text,
            words = words.withLineSpacing(text)
                .map { LyricWord(begin = it.beginTime, end = it.endTime, text = it.text) }
                .takeIf { it.isNotEmpty() },
            translation = secondaryText(LyricSecondaryMode.TRANSLATION),
            roma = secondaryText(LyricSecondaryMode.PRONUNCIATION),
        )
    }

    /**
     * 内容签名。
     *
     * 用途只有一个：**避免每帧重建整首歌词**。因此它必须覆盖「改了会导致显示不同」的
     * 全部字段，同时不能包含会自然变化的字段（位置、播放态），否则去重失效。
     */
    private fun signature(track: LyricPushTrack, lines: List<SyncedLyricLine>): String {
        var hash = 17
        lines.forEach { line ->
            hash = 31 * hash + line.time.hashCode()
            hash = 31 * hash + line.text.hashCode()
            hash = 31 * hash + line.translation.hashCode()
            hash = 31 * hash + line.romanization.hashCode()
            line.words.forEach { word ->
                hash = 31 * hash + word.text.hashCode()
                hash = 31 * hash + word.beginTime.hashCode()
                hash = 31 * hash + word.endTime.hashCode()
            }
        }
        return listOf(
            track.trackKey(),
            track.title,
            track.artist,
            track.durationMs,
            lines.size,
            hash,
            secondary.name,
        ).joinToString("|")
    }

    private companion object {
        const val TAG = "LyriconBridge"

        /** 位置推送日志的采样间隔（见 [logPosition]）。 */
        const val POSITION_LOG_INTERVAL_MS = 3_000L
    }
}
