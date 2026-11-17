package cp.player.core.playback.drm

/**
 * Apple 增强 HLS 兼容性归一化。
 *
 * hls.js 1.6.x 与 Media3 1.11.x 都不认识 `METHOD=ISO-23001-7`（Apple 对
 * Widevine 增强流使用的新写法），只认语义相同的 `METHOD=SAMPLE-AES-CTR`；
 * 同时清单里的相对 URI 会相对代理地址解析，必须改写成绝对地址。因此
 * 桌面播放页与 Android 直连都要先过本归一化器：KEY/SESSION-KEY 行替换
 * method、URI 改绝对；variant 与 audio group 等 `.m3u8` 引用改写回代理
 * 地址（继续归一化），分片（`.mp4` 等）直接指向 CDN。加密分片本身的
 * scheme 由 init segment 的 tenc box 声明，改 method 名不影响 EME 解密。
 */
object HlsPlaylistNormalizer {

    private val playlistUriRegex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val methodRegex = Regex("""METHOD=ISO-23001-7""", RegexOption.IGNORE_CASE)

    fun normalize(text: String, upstreamUrl: String, proxyFor: (absoluteUrl: String) -> String): String {
        val result = StringBuilder(text.length + 256)
        for (rawLine in text.lines()) {
            val line = rawLine.trim().trimStart('\uFEFF')
            when {
                line.isEmpty() -> result.appendLine()
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    var rewritten = methodRegex.replace(line, "METHOD=SAMPLE-AES-CTR")
                    rewritten = rewriteTagUris(rewritten, upstreamUrl, proxyFor, allowProxy = false)
                    result.appendLine(rewritten)
                }
                line.startsWith("#") -> {
                    result.appendLine(rewriteTagUris(line, upstreamUrl, proxyFor, allowProxy = true))
                }
                else -> {
                    val absolute = resolveAgainst(upstreamUrl, line)
                    result.appendLine(mapPlaylistOrSegment(absolute, proxyFor))
                }
            }
        }
        return result.toString()
    }

    private fun rewriteTagUris(
        line: String,
        upstreamUrl: String,
        proxyFor: (String) -> String,
        allowProxy: Boolean,
    ): String = playlistUriRegex.replace(line) { match ->
        val raw = match.groupValues[1]
        val resolved = resolveAgainst(upstreamUrl, raw)
        val mapped = when {
            resolved.startsWith("data:") || resolved.startsWith("skd:") -> resolved
            allowProxy && isPlaylistReference(resolved) -> proxyFor(resolved)
            else -> resolved
        }
        "URI=\"$mapped\""
    }

    private fun mapPlaylistOrSegment(absolute: String, proxyFor: (String) -> String): String =
        if (isPlaylistReference(absolute)) proxyFor(absolute) else absolute

    private fun isPlaylistReference(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8", ignoreCase = true)
    }

    private fun resolveAgainst(base: String, value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://") ||
            value.startsWith("data:") || value.startsWith("skd:")
        ) {
            return value
        }
        return try {
            java.net.URI(base).resolve(value).toString()
        } catch (_: Exception) {
            value
        }
    }
}
