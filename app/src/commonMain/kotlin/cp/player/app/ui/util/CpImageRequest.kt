package cp.player.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.Extras
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import cp.player.core.provider.HostTrafficPolicy
import kotlin.math.roundToInt

/** 是否在解码期执行居中裁剪（Square / Fill）。 */
val CpCropSquareExtraKey = Extras.Key(default = true)

/** 剥离缩图参数后的原始资源基准键（用于「原始图 + 裁剪图」双层磁盘缓存关联）。 */
val CpOriginalUrlExtraKey = Extras.Key<String?>(default = null)

/**
 * 构建按目标像素分桶并显式约束解码尺寸的 [ImageRequest]：
 * - 所有图片（含本地 `file://` / `content://` 与无 CDN 参数的外链）都写入精确像素 `size(bucket, bucket)`
 *   与 `Precision.INEXACT`，确保只有真正需要的像素尺寸进入内存；
 * - 结合 [HostTrafficPolicy.preferredImagePx] 在托盘/省流状态下收紧请求尺寸；
 * - 原图与各分档缩略图通过 `diskCacheKey` 与 `CpOriginalUrlExtraKey` 协同本地高转低衍生，避免重复下载。
 */
fun buildCoverImageRequest(
    context: PlatformContext,
    url: String?,
    targetPx: Int,
    cropSquare: Boolean = true,
    memoryCacheWrite: Boolean = true,
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
    val canonical = canonicalCoverBaseUrl(url)
    val effectiveData = if (cropSquare) url.resized(bucket) ?: url else url
    val builder = ImageRequest.Builder(context)
        .data(effectiveData)
        .size(bucket, bucket)
        .precision(Precision.INEXACT)
        .memoryCacheKey("cover:$canonical:$bucket:${if (cropSquare) "c" else "f"}")
        .diskCacheKey(if (effectiveData == canonical) "orig:$canonical" else "cover_disk:$canonical:$bucket")
        .extras {
            it[CpCropSquareExtraKey] = cropSquare
            it[CpOriginalUrlExtraKey] = canonical
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
): ImageRequest? {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current.density
    val targetPx = (targetSizeDp.value * density).roundToInt().coerceIn(64, maxPx)
    val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
    return remember(context, url, bucket, cropSquare) {
        buildCoverImageRequest(context, url, bucket, cropSquare = cropSquare)
    }
}

@Composable
fun rememberCoverImageRequest(
    url: String?,
    targetPx: Int,
    cropSquare: Boolean = true,
): ImageRequest? {
    val context = LocalPlatformContext.current
    val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
    return remember(context, url, bucket, cropSquare) {
        buildCoverImageRequest(context, url, bucket, cropSquare = cropSquare)
    }
}
