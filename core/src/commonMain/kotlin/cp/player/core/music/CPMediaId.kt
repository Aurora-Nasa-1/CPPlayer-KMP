package cp.player.core.music

/**
 * 统一的媒体 ID (MediaId) 规范。
 * 格式为: {providerId}://{resourceType}/{resourceId}
 * 
 * 示例:
 * - 本地音乐: local://song//storage/emulated/0/Music/song.mp3
 * - 网易云: netease://song/12345
 *
 * 本地媒体条目（下载 / 导入的本地文件）mediaId 规则约定为:
 * `local://{audio|video}/{path}`，其中 path 为文件绝对路径或 content:// uri。
 */
data class CPMediaId(
    val providerId: String,
    val resourceType: String,
    val resourceId: String
) {
    override fun toString(): String {
        return "$providerId://$resourceType/$resourceId"
    }

    companion object {
        /** resourceType: 在线歌曲条目 */
        const val RESOURCE_SONG = "song"

        /** resourceType: 本地音频条目 */
        const val RESOURCE_AUDIO = "audio"

        /** resourceType: 本地视频条目 */
        const val RESOURCE_VIDEO = "video"

        fun parse(mediaIdStr: String): CPMediaId {
            val parts = mediaIdStr.split("://", limit = 2)
            if (parts.size != 2) throw IllegalArgumentException("Invalid CPMediaId format: $mediaIdStr")
            
            val providerId = parts[0]
            val resourceParts = parts[1].split("/", limit = 2)
            if (resourceParts.size != 2) throw IllegalArgumentException("Invalid resource format in CPMediaId: $mediaIdStr")
            
            return CPMediaId(providerId, resourceParts[0], resourceParts[1])
        }

        /**
         * 尝试安全解析 mediaId，无效或畸形时返回 null 而不抛异常。
         */
        fun parseOrNull(mediaIdStr: String?): CPMediaId? {
            if (mediaIdStr.isNullOrBlank()) return null
            return try {
                parse(mediaIdStr)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 提取用于特定音源 API 的裸资源 ID（如网易云数字 id）。
         *
         * - 若是规范 CPMediaId（如 `netease://song/12345`），返回 `12345`；
         * - 若已是裸 ID（如 `12345`），去除两端空白后原样返回；
         * - 若为 null 或空，返回空串。
         */
        fun toRawResourceId(mediaIdStr: String?): String {
            if (mediaIdStr.isNullOrBlank()) return ""
            val parsed = parseOrNull(mediaIdStr)
            return parsed?.resourceId ?: mediaIdStr.trim()
        }

        /**
         * 将可能为裸 ID 或已为规范 mediaId 的字符串，规范化为 Canonical CPMediaId。
         *
         * - 若已包含 `://`（如 `netease://song/123` 或 `local://audio/...`），原样返回；
         * - 否则按指定 providerId 包装为 `{providerId}://{resourceType}/{idOrMediaId}`。
         */
        fun toCanonicalMediaId(
            idOrMediaId: String,
            providerId: String = "netease",
            resourceType: String = RESOURCE_SONG,
        ): String {
            val trimmed = idOrMediaId.trim()
            if (trimmed.contains("://")) return trimmed
            return "$providerId://$resourceType/$trimmed"
        }

        /**
         * 判定该 mediaId 是否为本地资源。
         */
        fun isLocal(mediaIdStr: String?): Boolean =
            mediaIdStr?.startsWith("local://") == true
    }
}

/**
 * 获取 TrackSummary 的规范 mediaId。
 * 若 [TrackSummary.id] 本身已包含 `://`（如已规范化的 mediaId），原样返回；
 * 否则以 [providerId]（默认 `netease`）包装成规范 `CPMediaId`。
 */
fun TrackSummary.canonicalMediaId(providerId: String? = null): String {
    val provider = providerId?.takeIf { it.isNotBlank() } ?: "netease"
    return CPMediaId.toCanonicalMediaId(id, providerId = provider)
}
