/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/SuperLyricBridge.kt
 * Changes: 模型换成 LyricPushTrack / SyncedLyricLine；退避曲线提到 commonMain
 *          （SuperLyricRetryPolicy.kt）以便单测；`backgroundText` 双轨合并到单轨模型；
 *          对外只暴露 onFrame 需要的三个动作（song / line / stop）。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import cp.player.core.playback.SyncedLyricLine

/**
 * SuperLyric 投放。
 *
 * 协议：HChenX 的 SuperLyric 通过 `SuperLyricHelper` 提供**注册发布者 + 发当前句**的
 * 静态入口（底层是系统级广播），接收方是状态栏 / 灵动通知歌词模块。
 *
 * ### 与词幕的关键差别
 * 词幕是「一次性给整首 + 让我推进度」，SuperLyric 是「**只推当前句**」——
 * 所以这里必须有句子级去重（同一句不重复发），而不是内容签名。
 *
 * ### 为什么要有退避
 * 没有安装 / 没激活 SuperLyric 时，`isAvailable()` 返回 false，而注册调用本身
 * 是一次跨进程广播。每帧重试 = 持续广播风暴。见 [superLyricRetryDelayMs]。
 */
internal class SuperLyricBridge {

    private var enabled = false
    private var registered = false
    private var secondary = LyricSecondaryMode.TRANSLATION

    private var lastLineKey: String? = null
    private var lastTrack: LyricPushTrack? = null

    private var consecutiveFailures = 0
    private var retryAfterElapsedMs = 0L

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        lastLineKey = null
        resetRetryState()
        if (enabled) register() else { sendStop(); unregister() }
    }

    fun setSecondary(mode: LyricSecondaryMode) {
        if (secondary == mode) return
        secondary = mode
        lastLineKey = null
    }

    fun sendSong(track: LyricPushTrack) {
        if (!enabled) return
        if (track.trackKey() == lastTrack?.trackKey()) return
        lastTrack = track
        lastLineKey = null
        register()
    }

    /**
     * 推当前句。
     *
     * @param force 用户在设置页手动切换后强制重发（例如刚打开开关，而当前句没变）。
     */
    fun sendLine(line: SyncedLyricLine?, lines: List<SyncedLyricLine>, index: Int, force: Boolean = false) {
        if (!enabled || line == null || index < 0) return
        val track = lastTrack
        val mainText = line.lineTextOrNull() ?: return
        val translation = line.secondaryText(secondary)
        val key = listOf(
            track?.trackKey().orEmpty(),
            line.time,
            index,
            secondary.name,
            translation.orEmpty(),
        ).joinToString(":")

        // 刚注册成功（或用户强制）时清掉去重键，否则「第一句永远是上一首的最后一句」。
        if (force && !registered) lastLineKey = null
        if (key == lastLineKey) return
        if (!register()) return

        runCatching {
            val end = line.lineEndMs(lines, index)
            SuperLyricHelper.sendLyric(
                SuperLyricData()
                    .setTitle(track?.title)
                    .setArtist(track?.artist)
                    .setAlbum(track?.album)
                    .setLyric(
                        SuperLyricLine(
                            mainText,
                            line.words.withLineSpacing(line.text).toSuperWords(),
                            line.time.coerceAtLeast(0L),
                            end,
                        ),
                    )
                    .setTranslation(
                        translation?.takeIf { it.isNotBlank() }?.let {
                            SuperLyricLine(it, line.time.coerceAtLeast(0L), end)
                        },
                    ),
            )
            lastLineKey = key
            resetRetryState()
        }.onFailure { recordFailure("send", it) }
    }

    fun sendStop() {
        if (!registered) return
        runCatching {
            val track = lastTrack
            SuperLyricHelper.sendStop(
                SuperLyricData()
                    .setTitle(track?.title)
                    .setArtist(track?.artist)
                    .setAlbum(track?.album),
            )
        }
        lastLineKey = null
    }

    fun destroy() {
        enabled = false
        sendStop()
        unregister()
        lastTrack = null
        lastLineKey = null
        resetRetryState()
    }

    private fun List<SyncedLyricLine.SyncedWord>.toSuperWords(): Array<SuperLyricWord>? {
        if (isEmpty()) return null
        return map { SuperLyricWord(it.text, it.beginTime, it.endTime) }.toTypedArray()
    }

    private fun register(): Boolean {
        if (!enabled) return false
        if (registered) return true
        if (SystemClock.elapsedRealtime() < retryAfterElapsedMs) return false

        val available = runCatching { SuperLyricHelper.isAvailable() }.getOrElse {
            recordFailure("availability check", it)
            return false
        }
        if (!available) {
            recordFailure("attach")
            return false
        }
        return runCatching {
            SuperLyricHelper.registerPublisher()
            SuperLyricHelper.setSystemPlayStateListenerEnabled(true)
            registered = true
            true
        }.getOrElse {
            recordFailure("register", it)
            false
        }
    }

    private fun unregister() {
        if (!registered) return
        runCatching { SuperLyricHelper.unregisterPublisher() }
        registered = false
    }

    private fun recordFailure(operation: String, cause: Throwable? = null) {
        registered = false
        consecutiveFailures++
        val delayMs = superLyricRetryDelayMs(consecutiveFailures)
        retryAfterElapsedMs = SystemClock.elapsedRealtime() + delayMs
        Log.w(
            TAG,
            "SuperLyric $operation unavailable; retry in ${delayMs / 1000}s" +
                cause?.let { " (${it.javaClass.simpleName}: ${it.message.orEmpty()})" }.orEmpty(),
        )
    }

    private fun resetRetryState() {
        consecutiveFailures = 0
        retryAfterElapsedMs = 0L
    }

    private companion object {
        const val TAG = "SuperLyricBridge"
    }
}
