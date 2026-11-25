package cp.player.app.ui.util

import coil3.PlatformContext
import coil3.SingletonImageLoader
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import cp.player.core.provider.HostTrafficPolicy

/**
 * 前台图片预取器：在非省流、非托盘后台、非离线状态下，
 * 利用页面请求或曲目播放间隙提前把高概率可见封面预热到 Coil 磁盘与内存缓存中。
 */
internal object CoverPrefetcher {

    private const val MAX_DEDUP_ENTRIES = 192
    private val lock = Any()
    private val recentKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_ENTRIES, 0.75f, true)

    fun clear() {
        synchronized(lock) { recentKeys.clear() }
    }

    fun prefetchCover(
        context: PlatformContext,
        url: String?,
        targetPx: Int,
        writeMemoryCache: Boolean = true,
    ) {
        if (url.isNullOrBlank()) return
        if (HostTrafficPolicy.shouldDeferNetworkFallback()) return
        val bucket = quantizeCoverBucketPx(HostTrafficPolicy.preferredImagePx(targetPx))
        val canonical = canonicalCoverBaseUrl(url)
        val dedupKey = "$canonical|$bucket"
        synchronized(lock) {
            if (recentKeys.containsKey(dedupKey)) return
            recentKeys[dedupKey] = Unit
            while (recentKeys.size > MAX_DEDUP_ENTRIES) {
                recentKeys.remove(recentKeys.keys.first())
            }
        }
        val request = buildCoverImageRequest(
            context = context,
            url = url,
            targetPx = bucket,
            cropSquare = true,
            memoryCacheWrite = writeMemoryCache,
        ) ?: return
        runCatching { SingletonImageLoader.get(context).enqueue(request) }
    }

    fun prefetchCovers(
        context: PlatformContext,
        urls: Iterable<String?>,
        targetPx: Int,
        limit: Int = 12,
        writeMemoryCache: Boolean = true,
    ) {
        if (HostTrafficPolicy.shouldDeferNetworkFallback()) return
        var count = 0
        val seenCanonical = HashSet<String>(limit)
        for (url in urls) {
            if (url.isNullOrBlank()) continue
            val canonical = canonicalCoverBaseUrl(url)
            if (!seenCanonical.add(canonical)) continue
            prefetchCover(context, url, targetPx, writeMemoryCache = writeMemoryCache)
            if (++count >= limit) break
        }
    }

    fun resolveNextTrackCoverUrl(state: PlaybackUiState): String? {
        if (state.queue.isEmpty() || state.currentIndex < 0) return null
        if (state.repeatMode == RepeatMode.ONE) return null
        val nextIndex = when {
            state.currentIndex + 1 < state.queue.size -> state.currentIndex + 1
            state.repeatMode == RepeatMode.ALL && state.queue.size > 1 -> 0
            else -> return null
        }
        val nextUrl = state.queue[nextIndex].coverUrl
        return nextUrl?.takeIf { it.isNotBlank() && it != state.currentTrack?.coverUrl }
    }
}
