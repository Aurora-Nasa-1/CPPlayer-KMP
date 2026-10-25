package cp.player.core.playback

import android.content.Context
import cp.player.core.util.PlatformContext
import cp.player.core.util.androidContext
import java.io.File

/**
 * 安卓端的 [StreamLocalizer]：
 *
 * 1. **播放落盘路径保持空实现**：Media3 / ExoPlayer 能正常定位 HTTP FLAC（引擎侧的局限只在桌面 rodio 上），
 *    安卓无需在开播前整曲下载落盘（见实测矩阵）。
 * 2. **管理面真实有效**：接管 ExoPlayer 运行时的 512 MiB [androidx.media3.datasource.cache.SimpleCache]，
 *    统计容量占用、列出已缓存条目、支持单曲删除与全量/过期清理。
 */
class AndroidStreamLocalizer(private val context: Context) : StreamLocalizer {

    private val cache get() = SharedMedia3Player.getCache(context)
    private val index get() = SharedMedia3Player.getIndex(context)

    override fun isLocalizing(qualityLevel: String): Boolean = false

    override fun cachedPath(cacheKey: String): String? = null

    override suspend fun localize(
        url: String,
        cacheKey: String,
        headers: Map<String, String>,
        meta: SongCacheMeta?,
    ): String? = null

    override fun stats(): SongCacheStats {
        return runCatching {
            val c = cache
            SongCacheStats(
                entries = c.keys.size,
                bytes = c.cacheSpace,
                capacityBytes = SharedMedia3Player.CACHE_SIZE_BYTES,
            )
        }.getOrDefault(SongCacheStats(capacityBytes = SharedMedia3Player.CACHE_SIZE_BYTES))
    }

    override fun entries(): List<SongCacheEntry> {
        return runCatching {
            val c = cache
            val idx = index
            val keys = c.keys.toSet()
            idx.retainOnly(keys)
            keys.mapNotNull { key ->
                val spans = c.getCachedSpans(key)
                if (spans.isNullOrEmpty()) return@mapNotNull null
                val bytes = spans.sumOf { it.length }
                val lastAccessMs = spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L
                val record = idx.metaOf(key)
                val level = record?.qualityLevel ?: key.substringAfterLast('@', "").takeIf { it.isNotBlank() && it != key }
                val mediaId = record?.mediaId ?: if (level != null) key.substringBeforeLast('@') else key
                SongCacheEntry(
                    id = key,
                    mediaId = mediaId,
                    qualityLevel = level,
                    title = record?.title,
                    artist = record?.artist,
                    bytes = bytes,
                    lastAccessMs = lastAccessMs,
                )
            }.sortedByDescending { it.lastAccessMs }
        }.getOrDefault(emptyList())
    }

    override fun remove(id: String): Boolean {
        return runCatching {
            val c = cache
            val spans = c.getCachedSpans(id)
            val existed = !spans.isNullOrEmpty()
            c.removeResource(id)
            index.remove(id)
            existed
        }.getOrDefault(false)
    }

    override fun clear(): Int {
        return runCatching {
            val c = cache
            val keys = c.keys.toList()
            for (key in keys) {
                c.removeResource(key)
            }
            index.retainOnly(emptySet())
            keys.size
        }.getOrDefault(0)
    }

    override fun clearOlderThan(olderThanMs: Long): Int {
        return runCatching {
            val c = cache
            val idx = index
            val cutoff = System.currentTimeMillis() - olderThanMs
            var count = 0
            for (key in c.keys.toList()) {
                val spans = c.getCachedSpans(key)
                val lastTouch = spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L
                if (lastTouch in 1..<cutoff) {
                    c.removeResource(key)
                    idx.remove(key)
                    count++
                }
            }
            count
        }.getOrDefault(0)
    }

    override fun cacheDirPath(): String? =
        File(context.cacheDir, SharedMedia3Player.CACHE_DIR_NAME).absolutePath
}

actual fun createStreamLocalizer(context: PlatformContext): StreamLocalizer {
    val android = context.androidContext() ?: return NoOpStreamLocalizer
    return AndroidStreamLocalizer(android.applicationContext)
}
