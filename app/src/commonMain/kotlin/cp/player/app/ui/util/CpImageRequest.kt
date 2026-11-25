package cp.player.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.Extras
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import cp.player.core.provider.CoverUrlResolver
import cp.player.core.provider.HostTrafficPolicy
import kotlin.math.roundToInt

/** 是否在解码期执行居中裁剪（Square / Fill）。 */
val CpCropSquareExtraKey = Extras.Key(default = true)

/** 剥离缩图参数后的原始资源基准键（用于「原始图 + 裁剪图」双层磁盘缓存关联）。 */
val CpOriginalUrlExtraKey = Extras.Key<String?>(default = null)

/** 规范内存缓存键，保证同图同档位在不同页面/弹层/飞行动画之间 100% 共享内存命中。 */
fun coverMemoryCacheKey(
    canonicalUrl: String,
    bucketPx: Int,
    cropSquare: Boolean = true,
): String = "cover:$canonicalUrl:$bucketPx:${if (cropSquare) "c" else "f"}"

private fun findCachedPlaceholderKey(
    context: PlatformContext,
    canonicalUrl: String,
    targetBucket: Int,
    cropSquare: Boolean,
    preferredBucketPx: Int?,
): String? {
    val memCache = runCatching { SingletonImageLoader.get(context).memoryCache }.getOrNull()
    if (preferredBucketPx != null) {
        val preferred = quantizeCoverBucketPx(preferredBucketPx)
        if (preferred != targetBucket) {
            val preferredKey = coverMemoryCacheKey(canonicalUrl, preferred, cropSquare)
            if (memCache == null || memCache[MemoryCache.Key(preferredKey)] != null) {
                return preferredKey
            }
        }
    }
    if (memCache == null) return null
    for (bucket in CoverUrlResolver.STANDARD_BUCKETS_DESC) {
        if (bucket == targetBucket) continue
        val candidate = coverMemoryCacheKey(canonicalUrl, bucket, cropSquare)
        if (memCache[MemoryCache.Key(candidate)] != null) {
            return candidate
        }
    }
    return null
}

private fun hasExplicitParamQuery(url: String): Boolean {
    val qIdx = url.indexOf('?')
    if (qIdx < 0) return false
    val fragIdx = url.indexOf('#', qIdx)
    val query = if (fragIdx >= 0) url.substring(qIdx + 1, fragIdx) else url.substring(qIdx + 1)
    return query.split('&').any { part ->
        val eqIdx = part.indexOf('=')
        val key = if (eqIdx >= 0) part.substring(0, eqIdx) else part
        key.equals("param", ignoreCase = true)
    }
}

/**
 * 构建按目标像素分桶并显式约束解码尺寸的 [ImageRequest]：
 * - 所有图片（含本地 `file://` / `content://` 与无 CDN 参数的外链）都写入精确像素 `size(bucket, bucket)`
 *   与 `Precision.INEXACT`，确保只有真正需要的像素尺寸进入内存；
 * - 结合 [HostTrafficPolicy.preferredImagePx] 在托盘/省流状态下收紧请求尺寸；
 * - 原图与各分档缩略图通过 `diskCacheKey` 与 `CpOriginalUrlExtraKey` 协同本地高转低衍生，避免重复下载；
 * - 若内存中已存在同一封面的其他档位（如列表卡片的 256px），自动绑定 `placeholderMemoryCacheKey` 无缝过渡。
 */
fun buildCoverImageRequest(
    context: PlatformContext,
    url: String?,
    targetPx: Int,
    cropSquare: Boolean = true,
    memoryCacheWrite: Boolean = true,
    placeholderBucketPx: Int? = null,
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
    val canonical = canonicalCoverBaseUrl(url)
    val effectiveData = if (cropSquare) url.resized(bucket) ?: url else url
    val isUnresizedOriginal = effectiveData == canonical ||
        (effectiveData == url && !hasExplicitParamQuery(url))
    val memKey = coverMemoryCacheKey(canonical, bucket, cropSquare)
    val placeholderKey = findCachedPlaceholderKey(
        context = context,
        canonicalUrl = canonical,
        targetBucket = bucket,
        cropSquare = cropSquare,
        preferredBucketPx = placeholderBucketPx,
    )
    val builder = ImageRequest.Builder(context)
        .data(effectiveData)
        .size(bucket, bucket)
        .precision(Precision.INEXACT)
        .memoryCacheKey(memKey)
        .diskCacheKey(if (isUnresizedOriginal) "orig:$canonical" else "cover_disk:$canonical:$bucket")
        .apply {
            if (placeholderKey != null) {
                placeholderMemoryCacheKey(placeholderKey)
            }
            extras[CpCropSquareExtraKey] = cropSquare
            extras[CpOriginalUrlExtraKey] = canonical
        }
    if (!memoryCacheWrite) {
        builder.memoryCachePolicy(CachePolicy.READ_ONLY)
    }
    return builder.build()
}

@Composable
fun rememberCoverImageRequest(
    url: String?,
    targetSizeDp: Dp,
    cropSquare: Boolean = true,
    maxPx: Int = 1024,
    placeholderBucketPx: Int? = null,
): ImageRequest? {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current.density
    val targetPx = (targetSizeDp.value * density).roundToInt().coerceIn(64, maxPx)
    val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
    return remember(context, url, bucket, cropSquare, placeholderBucketPx) {
        buildCoverImageRequest(
            context = context,
            url = url,
            targetPx = bucket,
            cropSquare = cropSquare,
            placeholderBucketPx = placeholderBucketPx,
        )
    }
}

@Composable
fun rememberCoverImageRequest(
    url: String?,
    targetPx: Int,
    cropSquare: Boolean = true,
    placeholderBucketPx: Int? = null,
): ImageRequest? {
    val context = LocalPlatformContext.current
    val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
    return remember(context, url, bucket, cropSquare, placeholderBucketPx) {
        buildCoverImageRequest(
            context = context,
            url = url,
            targetPx = bucket,
            cropSquare = cropSquare,
            placeholderBucketPx = placeholderBucketPx,
        )
    }
}
