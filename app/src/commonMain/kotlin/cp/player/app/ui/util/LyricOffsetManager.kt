package cp.player.app.ui.util

import androidx.compose.runtime.mutableStateMapOf
import cp.player.core.util.defaultSettingsStorage

/**
 * 歌词时间轴偏移管理器。
 *
 * 负责记录和持久化针对单曲的歌词时间轴微调（毫秒）。
 * 当歌词出现提前或滞后时，用户可手动增减偏移量，结果将自动按曲目 ID 记忆在专属的
 * `cp_lyric_offsets` 命名空间中。
 */
object LyricOffsetManager {
    private val storage by lazy { defaultSettingsStorage("cp_lyric_offsets") }
    private val offsetCache = mutableStateMapOf<String, Long>()

    /**
     * 获取指定曲目的歌词偏移量（毫秒）。正数表示歌词提前，负数表示歌词延后。
     */
    fun getOffset(trackId: String): Long {
        if (trackId.isBlank()) return 0L
        return offsetCache[trackId] ?: run {
            val saved = storage.getString("offset_$trackId")?.toLongOrNull() ?: 0L
            offsetCache[trackId] = saved
            saved
        }
    }

    /**
     * 设置指定曲目的歌词偏移量（毫秒）。
     */
    fun setOffset(trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        offsetCache[trackId] = offsetMs
        if (offsetMs == 0L) {
            storage.remove("offset_$trackId")
        } else {
            storage.putString("offset_$trackId", offsetMs.toString())
        }
    }

    /**
     * 微调指定曲目的歌词偏移量（增加或减少 deltaMs 毫秒）。
     */
    fun adjustOffset(trackId: String, deltaMs: Long): Long {
        val current = getOffset(trackId)
        val updated = current + deltaMs
        setOffset(trackId, updated)
        return updated
    }

    /**
     * 重置指定曲目的歌词偏移量为 0。
     */
    fun resetOffset(trackId: String) {
        setOffset(trackId, 0L)
    }
}
