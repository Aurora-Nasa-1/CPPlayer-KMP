package cp.player.kmp.music

/**
 * 统一的媒体 ID (MediaId) 规范。
 * 格式为: {providerId}://{resourceType}/{resourceId}
 * 
 * 示例:
 * - 本地音乐: local://song//storage/emulated/0/Music/song.mp3
 * - 网易云: netease://song/12345
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
        fun parse(mediaIdStr: String): CPMediaId {
            val parts = mediaIdStr.split("://", limit = 2)
            if (parts.size != 2) throw IllegalArgumentException("Invalid CPMediaId format: $mediaIdStr")
            
            val providerId = parts[0]
            val resourceParts = parts[1].split("/", limit = 2)
            if (resourceParts.size != 2) throw IllegalArgumentException("Invalid resource format in CPMediaId: $mediaIdStr")
            
            return CPMediaId(providerId, resourceParts[0], resourceParts[1])
        }
    }
}
