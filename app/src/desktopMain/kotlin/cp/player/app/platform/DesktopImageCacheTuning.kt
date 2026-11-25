package cp.player.app.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import cp.player.core.util.DesktopDataDir
import okio.Path.Companion.toOkioPath

/**
 * 桌面端图片内存缓存上限：默认收紧为 32MB（可通过 -Dcp.coil.cacheMb=16..128 覆盖）。
 *
 * 结合裁剪解码管线（按槽位目标像素分桶解码、居中裁剪）与双层磁盘缓存，
 * 只将真正需要的像素尺寸送入原生内存，显著降低常驻 Working Set。
 */
private const val DefaultDesktopImageMemoryCacheMb = 32L
private const val MinDesktopImageMemoryCacheMb = 16L
private const val MaxDesktopImageMemoryCacheMb = 128L
private const val DesktopOriginalDiskCacheBytes = 256L * 1024 * 1024

private fun desktopImageMemoryCacheBytes(): Long {
    val requestedMb = System.getProperty("cp.coil.cacheMb")
        ?.toLongOrNull()
        ?.coerceIn(MinDesktopImageMemoryCacheMb, MaxDesktopImageMemoryCacheMb)
        ?: DefaultDesktopImageMemoryCacheMb
    return requestedMb * 1024L * 1024L
}

/**
 * 给 Coil 单例装上收紧后的内存缓存、双层磁盘缓存（原始 + 裁剪）及按需裁剪解码器。
 */
fun installDesktopImageCacheLimit() {
    val cacheBytes = desktopImageMemoryCacheBytes()
    println(
        "[CPPlayer] Coil desktop memory cache = ${cacheBytes / (1024L * 1024L)}MB " +
            "(override with -Dcp.coil.cacheMb=16..128)",
    )
    SingletonImageLoader.setSafe { ctx: PlatformContext ->
        ImageLoader.Builder(ctx)
            .memoryCache(
                MemoryCache.Builder()
                    .maxSizeBytes(cacheBytes)
                    .build(),
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(DesktopDataDir.directory("cache/images/original").toOkioPath())
                    .maxSizeBytes(DesktopOriginalDiskCacheBytes)
                    .build()
            }
            .components {
                add(DesktopCachedCroppedFetcher.Factory())
                add(DesktopCroppingDecoder.Factory())
            }
            .build()
    }
}

/** 隐藏到托盘 / 内存紧张时清空内存图片缓存（保留磁盘缓存，恢复窗口后瞬时重载）。 */
internal fun trimDesktopImageMemoryCache() {
    runCatching {
        SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
    }
}

internal fun clearDesktopCroppedImageCache() {
    DesktopCroppedThumbDiskCache.clear()
}

internal fun desktopCroppedImageCacheSizeBytes(): Long =
    DesktopCroppedThumbDiskCache.totalBytes()
