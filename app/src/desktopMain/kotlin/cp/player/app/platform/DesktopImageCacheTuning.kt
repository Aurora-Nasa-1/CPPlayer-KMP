package cp.player.app.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import cp.player.core.util.DesktopDataDir
import okio.Path.Companion.toOkioPath

/**
 * 桌面端图片内存缓存上限：
 * - GraalVM Native Image（`-Xmx100m`）保持 **32MB**，守住低堆预算；
 * - 普通 JVM 桌面端默认放宽至 **64MB**，减少长列表滚动与频繁切换页面时的 LRU 逐出重解码；
 * - 均支持通过 `-Dcp.coil.cacheMb=16..128` 覆盖。
 */
private const val NativeImageDesktopMemoryCacheMb = 32L
private const val DefaultJvmDesktopImageMemoryCacheMb = 64L
private const val MinDesktopImageMemoryCacheMb = 16L
private const val MaxDesktopImageMemoryCacheMb = 128L
private const val DesktopOriginalDiskCacheBytes = 256L * 1024 * 1024

private fun isGraalNativeImage(): Boolean =
    System.getProperty("org.graalvm.nativeimage.imagecode") != null

private fun desktopImageMemoryCacheBytes(): Long {
    val defaultMb = if (isGraalNativeImage()) {
        NativeImageDesktopMemoryCacheMb
    } else {
        DefaultJvmDesktopImageMemoryCacheMb
    }
    val requestedMb = System.getProperty("cp.coil.cacheMb")
        ?.toLongOrNull()
        ?.coerceIn(MinDesktopImageMemoryCacheMb, MaxDesktopImageMemoryCacheMb)
        ?: defaultMb
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
