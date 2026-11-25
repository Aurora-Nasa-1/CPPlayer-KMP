package cp.player.core.provider

import cp.player.core.util.JavaSerializable
import kotlinx.serialization.Serializable

/**
 * 可选的音源多档封面裁剪声明（放在 `manifest.json` 的 `imageResize` 字段中）。
 *
 * Provider 作者无需编写任何代码即可支持多档封面尺寸：
 * - `mode = "netease_param"`：自动追加 `?param={w}y{h}`（网易云 CDN 默认兼容，未声明且命中网易云域名时宿主也会自动启用）；
 * - `mode = "query_template"`：按 [template] 替换 `{url}`、`{w}`、`{h}`、`{size}`；
 * - `mode = "none"`（或留空遇到非网易云第三方地址）：不改动原始 URL，由宿主通过本地高档图向下裁剪复用避免重复下载。
 */
@Serializable
data class ProviderImageResizeRule(
    val mode: String = "none",
    val template: String? = null,
    val stripQueryParams: List<String> = emptyList(),
    val supportedWidths: List<Int> = emptyList(),
) : JavaSerializable

/**
 * 统一的封面尺寸收敛、URL 规范化（去参数/临时签名提取资产主键）与多档尺寸解析器。
 */
object CoverUrlResolver {

    private val DEFAULT_SIGNATURE_PARAMS = setOf(
        "param",
        "sign",
        "sig",
        "t",
        "ts",
        "timestamp",
        "expires",
        "expire",
        "auth_key",
        "token",
        "x-amz-signature",
        "x-amz-expires",
        "x-amz-date",
        "x-amz-credential",
    )

    /** 将零散像素收敛到 5 个标准档位，防止同一张图出现几十份碎片缓存。 */
    fun quantizeBucketPx(sizePx: Int): Int = when {
        sizePx <= 0 -> 128
        sizePx <= 128 -> 128
        sizePx <= 256 -> 256
        sizePx <= 512 -> 512
        sizePx <= 1024 -> 1024
        else -> 1440
    }

    /**
     * 剥离 CDN 裁剪参数与常见临时签名参数，只保留「这是同一张封面」的稳定资产键。
     * 仅用于内存/磁盘缓存键匹配，绝不用于真实网络请求。
     */
    fun canonicalCoverKey(
        url: String,
        rule: ProviderImageResizeRule? = HostTrafficPolicy.activeImageResizeRule,
    ): String {
        if (!url.startsWith("http", ignoreCase = true)) return url
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val fragmentIdx = url.indexOf('#', qIdx)
        val queryEnd = if (fragmentIdx >= 0) fragmentIdx else url.length
        val base = url.substring(0, qIdx)
        val rawQuery = url.substring(qIdx + 1, queryEnd)
        if (rawQuery.isEmpty()) return base

        val customStrip = rule?.stripQueryParams?.map { it.lowercase() }?.toSet().orEmpty()
        val kept = ArrayList<String>(4)
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val eqIdx = part.indexOf('=')
            val key = (if (eqIdx >= 0) part.substring(0, eqIdx) else part).lowercase()
            if (key in DEFAULT_SIGNATURE_PARAMS || key in customStrip) continue
            kept.add(part)
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    /**
     * 为图片请求选择合适档位的 URL：
     * 1. 结合 [HostTrafficPolicy.preferredImagePx] 在省流/托盘后台收紧像素预算；
     * 2. 若活跃 SDK Provider 可选重写了 `resolveCoverUrl`，优先尊重插件返回值；
     * 3. 否则依据 [ModuleManifest.imageResize] 规则或网易云 CDN 安全白名单追加裁剪参数；
     * 4. 其余第三方陌生 URL 默认原样返回，避免误拼参数导致 403/404。
     */
    fun resizeCoverUrl(
        rawUrl: String?,
        requestedPx: Int,
        rule: ProviderImageResizeRule? = HostTrafficPolicy.activeImageResizeRule,
        customResolver: ((String, Int) -> String?)? = HostTrafficPolicy.activeCustomCoverResolver,
    ): String? {
        if (rawUrl.isNullOrBlank()) return rawUrl
        if (!rawUrl.startsWith("http", ignoreCase = true)) return rawUrl

        val effectivePx = HostTrafficPolicy.preferredImagePx(requestedPx)
        val bucket = snapSupportedWidth(quantizeBucketPx(effectivePx), rule?.supportedWidths)

        runCatching { customResolver?.invoke(rawUrl, bucket) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        val mode = rule?.mode?.trim()?.lowercase()
        return when (mode) {
            "none" -> rawUrl
            "query_template" -> applyTemplate(rawUrl, rule.template, bucket)
            "netease_param" -> appendNeteaseParam(rawUrl, bucket)
            else -> if (looksLikeNeteaseOrLocalTestCdn(rawUrl)) appendNeteaseParam(rawUrl, bucket) else rawUrl
        }
    }

    private fun snapSupportedWidth(bucket: Int, supportedWidths: List<Int>?): Int {
        if (supportedWidths.isNullOrEmpty()) return bucket
        val sorted = supportedWidths.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return bucket
        return sorted.firstOrNull { it >= bucket } ?: sorted.last()
    }

    private fun applyTemplate(rawUrl: String, template: String?, bucket: Int): String {
        if (template.isNullOrBlank()) return rawUrl
        val resolved = template
            .replace("{url}", rawUrl)
            .replace("{w}", bucket.toString())
            .replace("{h}", bucket.toString())
            .replace("{size}", bucket.toString())
        return if (resolved.startsWith("http", ignoreCase = true)) resolved else rawUrl
    }

    private fun appendNeteaseParam(url: String, bucket: Int): String {
        if (url.contains("param=")) return url
        val sep = if ('?' in url) '&' else '?'
        return "${url}${sep}param=${bucket}y${bucket}"
    }

    private fun looksLikeNeteaseOrLocalTestCdn(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return false
        val hostStart = schemeEnd + 3
        val hostEnd = url.indexOfAny(charArrayOf('/', '?', ':'), hostStart).let { if (it < 0) url.length else it }
        val host = url.substring(hostStart, hostEnd).lowercase()
        return host.endsWith("126.net") ||
            host.endsWith("127.net") ||
            host.endsWith("163.com") ||
            host == "127.0.0.1" ||
            host == "localhost"
    }
}
