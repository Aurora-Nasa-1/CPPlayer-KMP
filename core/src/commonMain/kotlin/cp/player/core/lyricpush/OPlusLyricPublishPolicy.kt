/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/OPlusLyricPublishPolicy.kt
 * Changes: 删掉 `shouldKeepSongIdentityMetadata`（那是 Halcyon 的 SessionPresentationPlayer
 *          用来决定「要不要保留被歌词覆盖过的 TITLE/ARTIST」的，本仓库走
 *          LyricPushMetadataSink 显式恢复，不需要一个策略函数）；其余判定原样保留。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

/**
 * ColorOS 歌词「要不要往 MediaSession 里写一次」的判定。
 *
 * 为什么需要它：写 MediaMetadata 会连带刷新通知与所有 MediaController 的快照，
 * 每帧都写等于每帧重建通知。而歌词只在**切歌 / 模式切换 / 歌词加载完成**时才会变，
 * 所以绝大多数帧都应该被判定成 [None]。
 */
internal object OPlusLyricPublishPolicy {

    /**
     * ColorOS 侧有时会在我们写完 extras 之后又用旧快照覆盖一次，
     * 因此写完 [COMPAT_REAPPLY_DELAY_MS] 后要补发一次（force）。
     */
    const val COMPAT_REAPPLY_DELAY_MS = 800L

    /** 首帧准备的超时：宁可先发一个「无歌词」的快照，也不能让切歌卡住。 */
    const val INITIAL_PREPARE_TIMEOUT_MS = 1_500L

    val COMPAT_REAPPLY_DELAYS_MS = longArrayOf(COMPAT_REAPPLY_DELAY_MS)

    fun actionFor(
        currentLyricInfo: String?,
        currentRawLyric: String?,
        targetLyricInfo: String?,
        targetRawLyric: String?,
        force: Boolean = false,
    ): OPlusLyricPublishAction {
        return if (targetLyricInfo.isNullOrBlank()) {
            // 目标为空 ⇒ 之前写过就必须清掉，从没写过就不必动。
            if (currentLyricInfo != null || currentRawLyric != null) {
                OPlusLyricPublishAction.Clear
            } else {
                OPlusLyricPublishAction.None
            }
        } else if (!force && currentLyricInfo == targetLyricInfo && currentRawLyric == targetRawLyric) {
            OPlusLyricPublishAction.None
        } else {
            OPlusLyricPublishAction.Write
        }
    }
}

internal enum class OPlusLyricPublishAction {
    /** 什么都不做。 */
    None,

    /** 清除已发布的歌词字段。 */
    Clear,

    /** 写入新的歌词字段。 */
    Write,
}
