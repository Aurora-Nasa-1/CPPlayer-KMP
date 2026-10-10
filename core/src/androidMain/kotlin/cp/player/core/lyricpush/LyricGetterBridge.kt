/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/LyricGetterBridge.kt
 * Changes: 模型换成 SyncedLyricLine；`isMusicSymbolOnly()` 复用回 commonMain 的版本
 *          （上游这里还额外查了 Character.UnicodeBlock.MUSICAL_SYMBOLS，本仓库按
 *          码点区间判定，见 LyricPushText）。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.content.Context
import android.util.Log
import cn.lyric.getter.api.API
import cn.lyric.getter.api.data.ExtraData
import cp.player.core.playback.SyncedLyricLine

/**
 * Lyric Getter 投放。
 *
 * 协议：`cn.lyric.getter.api` 是**被 Xposed 模块 hook 的公开 API** —— 本应用只负责
 * 把当前歌词文本交给它，由 Lyric Getter 模块决定投到状态栏还是别处。
 *
 * 因此：
 * - 传的是**纯文本**而不是带时间戳的 LRC（模块自己按 [ExtraData.delay] 决定停留多久）；
 * - 纯音乐符号行（"♪"）必须过滤，否则状态栏会闪一个孤零零的音符。
 */
internal class LyricGetterBridge(context: Context) {

    private val api = API()
    private val packageName = context.packageName
    private val appName = runCatching {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }.getOrDefault(packageName)

    private var enabled = false
    private var lastPayload: String? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        lastPayload = null
        if (!enabled) clearLyric()
    }

    fun sendLine(line: SyncedLyricLine?, lines: List<SyncedLyricLine>, index: Int, force: Boolean = false) {
        if (!enabled || line == null || index < 0) return
        val text = line.lineTextOrNull()?.takeIf { !it.isMusicSymbolOnly() } ?: return
        val payload = "${line.time}:$text"
        if (!force && payload == lastPayload) return
        lastPayload = payload

        runCatching {
            api.sendLyric(
                text,
                ExtraData().apply {
                    this.packageName = this@LyricGetterBridge.packageName
                    base64Icon = ""
                    useOwnMusicController = false
                    title = this@LyricGetterBridge.appName
                    // 停留时长 = 本行到下一行的间隔；模块用它决定何时清掉。
                    delay = (line.lineEndMs(lines, index) - line.time)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                },
            )
        }.onFailure {
            Log.w(TAG, "Unable to publish lyric through Lyric Getter", it)
            // 失败就把去重键清掉，下一帧重试（这个 API 没有重试预算的概念，开销也只是一次 IPC）。
            lastPayload = null
        }
    }

    fun clearLyric() {
        lastPayload = null
        runCatching { api.clearLyric() }
            .onFailure { Log.w(TAG, "Unable to clear Lyric Getter lyric", it) }
    }

    fun destroy() {
        enabled = false
        clearLyric()
    }

    private companion object {
        const val TAG = "LyricGetterBridge"
    }
}
