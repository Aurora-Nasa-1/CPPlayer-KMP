package cp.player.app

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import cp.player.core.lyricpush.LyricPushMetadataSink

/**
 * 把歌词写回**系统媒体会话**（ColorOS 锁屏岛 + 蓝牙 / 车机 / 媒体中心歌词）。
 *
 * ### 为什么需要它，且为什么只能在这里
 * 这两个渠道读的都是 MediaSession 的 `MediaMetadata`，而 MediaSession 由
 * [PlaybackMediaSessionService] 持有 —— `core` 反向依赖不到本模块，所以用
 * `core` 定义接口、本模块注册实现的方式（与 `PlatformNotifications` 同款）。
 *
 * ### 实现方式：改写当前播放项的 MediaMetadata
 * 通过 `Player.replaceMediaItem(index, patchedItem)` 把 extras / 标题写进**真实**播放项。
 * 这样做的原因是 media3 侧没有「只给会话换个 metadata 快照」的公开入口
 * （那个能力在 `SimpleBasePlayer.invalidateState()` 上，而本项目的引擎是裸 `ExoPlayer`）。
 * 好处是三个消费方（通知、蓝牙 AVRCP、ColorOS）**同时**拿到新的 metadata，不需要
 * 分别打补丁。
 *
 * ⚠️ 因此这里有两条自我约束：
 * 1. **只在内容真的变了才写**（[setMetadataExtras] / [setNotificationLyric] 都先比对）；
 *    调用方已按「换歌」和「换行」的粒度触发，不会逐帧到达。
 * 2. **必须保留原始值**。标题被歌词覆盖后要能恢复成真实曲名，否则用户在
 *    「媒体通知歌词」关掉之后，通知里会一直留着最后一句歌词。
 */
internal class LyricMetadataSink(
    private val playerProvider: () -> Player?,
) : LyricPushMetadataSink {

    private var lyricExtras: Map<String, String> = emptyMap()
    private var lyricTitle: String? = null
    private var lyricSubtitle: String? = null

    /** 已经打过补丁的那一项的 mediaId；变化 ⇒ 说明换歌了，要重新捕获原始值。 */
    private var patchedMediaId: String? = null
    private var baseTitle: String? = null
    private var baseArtist: String? = null
    private var baseExtras: Bundle? = null
    private var baseApplied = false

    override fun setMetadataExtras(extras: Map<String, String>) {
        val normalized = extras.filterValues { it.isNotBlank() }
        if (normalized == lyricExtras) return
        lyricExtras = normalized
        apply()
    }

    override fun setNotificationLyric(title: String?, subtitle: String?) {
        val nextTitle = title?.takeIf { it.isNotBlank() }
        val nextSubtitle = subtitle?.takeIf { it.isNotBlank() }
        if (nextTitle == lyricTitle && nextSubtitle == lyricSubtitle) return
        lyricTitle = nextTitle
        lyricSubtitle = nextSubtitle
        apply()
    }

    /**
     * 把当前状态落到播放项上。
     *
     * 全流程 `runCatching`：这是旁路功能，任何一步失败（播放器未就绪 / ROM 拒绝
     * 替换播放项）都不该影响播放本身。
     */
    private fun apply() {
        runCatching {
            val player = playerProvider() ?: return@runCatching
            val index = player.currentMediaItemIndex
            if (index < 0) return@runCatching
            val item = player.currentMediaItem ?: return@runCatching
            val mediaId = item.mediaId

            if (mediaId != patchedMediaId) {
                // 换歌：捕获原始值，作为之后「恢复」的基准。
                patchedMediaId = mediaId
                baseTitle = item.mediaMetadata.title?.toString()
                baseArtist = item.mediaMetadata.artist?.toString()
                baseExtras = item.mediaMetadata.extras
                baseApplied = false
            }

            val hasLyricTitle = lyricTitle != null
            val hasExtras = lyricExtras.isNotEmpty()
            if (!hasLyricTitle && !hasExtras && !baseApplied) return@runCatching

            val metadataBuilder = item.mediaMetadata.buildUpon()
            // 原始 extras 也要写回：replaceMediaItem 是**整体替换**，不带上原有的
            // extras 会把别的功能（例如封面来源标记）一起抹掉。
            val newExtras = Bundle()
            baseExtras?.let { newExtras.putAll(it) }
            lyricExtras.forEach { (key, value) -> newExtras.putString(key, value) }
            metadataBuilder.setExtras(newExtras)

            if (hasLyricTitle) {
                metadataBuilder.setTitle(lyricTitle)
                metadataBuilder.setArtist(lyricSubtitle ?: baseArtist)
            } else {
                metadataBuilder.setTitle(baseTitle)
                metadataBuilder.setArtist(baseArtist)
            }

            val patched = item.buildUpon()
                .setMediaMetadata(metadataBuilder.build())
                .build()
            player.replaceMediaItem(index, patched)
            baseApplied = true
        }
    }

    /**
     * 会话销毁时清干净。
     *
     * 不清的后果：下一次服务重建时 [patchedMediaId] 仍是旧值，而播放器里的项**已经**
     * 带着上次的歌词标题 ⇒ 新实例会把歌词当成「原始标题」捕获下来，之后再也恢复不了。
     */
    fun release() {
        patchedMediaId = null
        baseTitle = null
        baseArtist = null
        baseExtras = null
        baseApplied = false
        lyricExtras = emptyMap()
        lyricTitle = null
        lyricSubtitle = null
    }
}

/** 便捷：把 [MediaItem] 的 mediaId 打上补丁后的可读名称（仅调试用）。 */
internal fun MediaItem.debugLabel(): String =
    "$mediaId (${mediaMetadata.title ?: "-"} / ${mediaMetadata.artist ?: "-"})"
