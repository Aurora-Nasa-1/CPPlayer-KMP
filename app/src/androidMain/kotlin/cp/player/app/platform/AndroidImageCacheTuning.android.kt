package cp.player.app.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.allowRgb565
import coil3.size.Dimension
import cp.player.app.ui.util.CpOriginalUrlExtraKey
import cp.player.app.ui.util.canonicalCoverBaseUrl
import cp.player.app.ui.util.quantizeCoverBucketPx
import cp.player.core.provider.HostTrafficPolicy
import okio.Path.Companion.toOkioPath
import kotlin.math.max

/**
 * 安卓端图片内存缓存上限：收紧为 32MB，并配置显式磁盘缓存与 RGB_565 优化。
 * 同时挂接本地高档位向下复用 Fetcher：若原图或更高尺寸已落盘，直接局部降采样复用，零网络请求。
 */
private const val ImageMemoryCacheBytes = 32L * 1024 * 1024
private const val ImageDiskCacheBytes = 256L * 1024 * 1024
private val AndroidBucketsDesc = intArrayOf(1440, 1024, 512, 256, 128)

/**
 * 给 Coil 单例装上固定上限的内存缓存、磁盘缓存与按需色彩降耗策略。
 */
fun installAndroidImageCacheLimit() {
    SingletonImageLoader.setSafe { ctx: PlatformContext ->
        ImageLoader.Builder(ctx)
            .allowRgb565(true)
            .memoryCache(
                MemoryCache.Builder()
                    .maxSizeBytes(ImageMemoryCacheBytes)
                    .build(),
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("coil_image_cache").toOkioPath())
                    .maxSizeBytes(ImageDiskCacheBytes)
                    .build()
            }
            .components {
                add(AndroidLocalReuseFetcher.Factory())
            }
            .build()
    }
}

private class AndroidLocalReuseFetcher(
    private val snapshot: DiskCache.Snapshot,
    private val options: Options,
    private val diskCacheKey: String,
) : Fetcher {
    override suspend fun fetch(): FetchResult = SourceFetchResult(
        source = ImageSource(
            file = snapshot.data,
            fileSystem = options.fileSystem,
            diskCacheKey = diskCacheKey,
            closeable = snapshot,
        ),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!options.diskCachePolicy.readEnabled) return null
            val diskCache = imageLoader.diskCache ?: return null
            val url = data.toString()
            if (url.isBlank()) return null

            val targetW = (options.size.width as? Dimension.Pixels)?.px ?: 512
            val targetH = (options.size.height as? Dimension.Pixels)?.px ?: 512
            val minNeeded = quantizeCoverBucketPx(max(targetW, targetH))
            val canonical = options.getExtra(CpOriginalUrlExtraKey) ?: canonicalCoverBaseUrl(url)
            val allowSmaller = HostTrafficPolicy.shouldDeferNetworkFallback()

            // 若当前槽位本身已有精确写入则交给 Coil 默认 DiskCache 流程，避免额外判断
            val origKey = "orig:$canonical"
            diskCache.openSnapshot(origKey)?.let { snap ->
                return AndroidLocalReuseFetcher(snap, options, origKey)
            }
            for (b in AndroidBucketsDesc) {
                if (b < minNeeded && !allowSmaller) continue
                if (b == minNeeded && url != canonical) continue
                val key = "cover_disk:$canonical:$b"
                diskCache.openSnapshot(key)?.let { snap ->
                    return AndroidLocalReuseFetcher(snap, options, key)
                }
            }
            return null
        }
    }
}
