package cp.player.app.repository

/**
 * 歌单只读缓存的完整身份。分页与排序独立分桶，账号切换或 cookie/session 更新也不会命中旧值。
 * [sessionId] 由音源代际、账号代际和当前 provider cookie revision 组成，不保存 cookie 原文。
 */
internal data class PlaylistCacheKey(
    val kind: String,
    val providerId: String,
    val accountId: String,
    val sessionId: String,
    val playlistId: String,
    val limit: Int? = null,
    val offset: Int? = null,
    val sortKey: String = "DEFAULT",
) {
    fun encode(): String = playlistPrefix(playlistId) + encodeParts(
        kind,
        providerId,
        accountId,
        sessionId,
        limit?.toString().orEmpty(),
        offset?.toString().orEmpty(),
        sortKey,
    )

    companion object {
        /** Prefix shared by every page and sort variant of one canonical playlist. */
        fun playlistPrefix(playlistId: String): String = "playlist:" + encodeParts(playlistId) + "|"

        private fun encodeParts(vararg parts: String): String =
            parts.joinToString(separator = "") { "${it.length}:$it;" }
    }
}
