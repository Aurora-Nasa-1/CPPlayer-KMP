package cp.player.core.playback

import cp.player.core.music.CPMediaId
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.music.MusicSourceIdentity
import cp.player.core.BackendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 收藏状态协同组件。
 *
 * 职责：
 * 1. 维护已收藏歌曲 ID 集合 ([likedIds]) 并对外暴露。
 * 2. 处理歌单收藏/取消收藏网络请求（含乐观更新与网络失败回滚）。
 * 3. 登录/登出状态下的收藏列表自动加载与同步。
 */
internal class PlaybackFavoritesManager(
    private val source: UnifiedMusicSource,
    private val scope: CoroutineScope,
    private val onSyncFavorite: (isFav: Boolean) -> Unit,
) {
    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private var favoritesLoaded = false
    private var favoritesLoadingJob: Job? = null
    private var currentMediaId: String? = null
    private var identity: MusicSourceIdentity? = source.accountIdentity()
    private var generation = 0L
    private val toggleMutex = Mutex()
    private val identityJob = scope.launch {
        source.accountChanges.collectLatest { refreshFavorites() }
    }

    fun isFavorite(mediaId: String?): Boolean {
        checkIdentity()
        if (mediaId == null) return false
        val parsed = runCatching { CPMediaId.parse(mediaId) }.getOrNull() ?: return false
        return parsed.providerId == identity?.providerId && parsed.resourceType in setOf("song", "audio") &&
            parsed.resourceId in _likedIds.value
    }

    fun syncCurrentMediaId(mediaId: String?) {
        currentMediaId = mediaId
        onSyncFavorite(isFavorite(mediaId))
    }

    suspend fun toggleFavoriteFor(mediaId: String): Unit = toggleMutex.withLock {
        checkIdentity()
        val parsed = runCatching { CPMediaId.parse(mediaId) }.getOrNull() ?: return
        if (parsed.providerId != identity?.providerId || parsed.resourceType !in setOf("song", "audio")) return
        val account = identity
        val id = parsed.resourceId
        ensureFavoritesLoaded()
        favoritesLoadingJob?.join()
        checkIdentity()
        val token = generation
        if (account != identity) return
        val currentlyLiked = id in _likedIds.value
        val target = !currentlyLiked
        applyLike(id, target)
        val ok = try {
            (source.likeSong(mediaId, target) as? BackendResult.Success)?.data == true
        } catch (e: CancellationException) {
            if (token == generation && account == source.accountIdentity()) applyLike(id, currentlyLiked)
            throw e
        } catch (_: Exception) { false }
        checkIdentity()
        if (!ok && token == generation && account == identity) {
            applyLike(id, currentlyLiked)
        }
    }

    suspend fun refreshFavorites() {
        generation++
        identity = source.accountIdentity()
        favoritesLoaded = false
        favoritesLoadingJob?.cancel()
        favoritesLoadingJob = null
        _likedIds.value = emptySet()
        onSyncFavorite(false)
        ensureFavoritesLoadedInternal(force = true)
    }

    fun ensureFavoritesLoaded() {
        checkIdentity()
        if (favoritesLoaded || favoritesLoadingJob?.isActive == true) return
        favoritesLoadingJob = scope.launch { ensureFavoritesLoadedInternal() }
    }

    private suspend fun ensureFavoritesLoadedInternal(force: Boolean = false) {
        if (favoritesLoaded && !force) return
        val token = generation
        val account = identity
        try {
            when (val result = source.getLikeList("self")) {
                is BackendResult.Success -> {
                    if (token != generation || account != source.accountIdentity()) return
                    _likedIds.value = result.data
                    favoritesLoaded = true
                    onSyncFavorite(isFavorite(currentMediaId))
                }
                is BackendResult.Error -> Unit
                is BackendResult.Unsupported -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A subsequent refresh may retry a transient failure.
        }
    }

    private fun checkIdentity() {
        val current = source.accountIdentity()
        if (current == identity) return
        generation++
        identity = current
        favoritesLoadingJob?.cancel()
        favoritesLoadingJob = null
        favoritesLoaded = false
        _likedIds.value = emptySet()
        onSyncFavorite(false)
    }

    private fun applyLike(id: String, liked: Boolean) {
        _likedIds.value = if (liked) _likedIds.value + id else _likedIds.value - id
        onSyncFavorite(isFavorite(currentMediaId))
    }

    fun cancel() {
        favoritesLoadingJob?.cancel()
        identityJob.cancel()
    }
}
