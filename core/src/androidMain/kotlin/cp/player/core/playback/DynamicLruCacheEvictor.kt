package cp.player.core.playback

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * 支持动态容量调整的 LRU 缓存淘汰器。
 *
 * 包装 Media3 的 CacheEvictor 逻辑，允许在运行时通过 [setMaxBytes] 动态修改容量上限
 * 并立刻触发超额淘汰，解决 Media3 内置 LeastRecentlyUsedCacheEvictor 上限只读的问题。
 */
class DynamicLruCacheEvictor(initialMaxBytes: Long) : CacheEvictor, Comparator<CacheSpan> {
    @Volatile
    var maxBytes: Long = initialMaxBytes
        private set

    private val leastRecentlyUsed = TreeSet<CacheSpan>(this)
    private var currentSize: Long = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    @Synchronized
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length > 0L && length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0L)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    @Synchronized
    fun setMaxBytes(cache: Cache, newMaxBytes: Long) {
        maxBytes = newMaxBytes
        evictCache(cache, 0L)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            val oldest = leastRecentlyUsed.first()
            try {
                cache.removeSpan(oldest)
            } catch (_: Exception) {
                break
            }
        }
    }

    override fun compare(lhs: CacheSpan, rhs: CacheSpan): Int {
        val lastTouchDiff = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
        if (lastTouchDiff == 0L) {
            return lhs.compareTo(rhs)
        }
        return if (lhs.lastTouchTimestamp < rhs.lastTouchTimestamp) -1 else 1
    }
}
