package cp.player.core.playback

import cp.player.core.music.CPMediaId
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.BackendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun isFavorite(mediaId: String?): Boolean {
        if (mediaId == null) return false
        val parsed = runCatching { CPMediaId.parse(mediaId) }.getOrNull() ?: return false
        return parsed.resourceId in _likedIds.value
    }

    fun syncCurrentMediaId(mediaId: String?) {
        currentMediaId = mediaId
        onSyncFavorite(isFavorite(mediaId))
    }

    suspend fun toggleFavoriteFor(mediaId: String) {
        val parsed = runCatching { CPMediaId.parse(mediaId) }.getOrNull() ?: return
        if (parsed.providerId == "local") return
        val id = parsed.resourceId
        ensureFavoritesLoaded()
        favoritesLoadingJob?.join()
        val currentlyLiked = id in _likedIds.value
        val target = !currentlyLiked
        applyLike(id, target)
        val ok = runCatching {
            (source.likeSong(mediaId, target) as? BackendResult.Success)?.data == true
        }.getOrDefault(false)
        if (!ok) {
            applyLike(id, currentlyLiked)
        }
    }

    suspend fun refreshFavorites() {
        favoritesLoaded = false
        favoritesLoadingJob?.cancel()
        ensureFavoritesLoadedInternal(force = true)
    }

    fun ensureFavoritesLoaded() {
        if (favoritesLoaded || favoritesLoadingJob?.isActive == true) return
        favoritesLoadingJob = scope.launch { ensureFavoritesLoadedInternal() }
    }

    private suspend fun ensureFavoritesLoadedInternal(force: Boolean = false) {
        if (favoritesLoaded && !force) return
        runCatching {
            when (val result = source.getLikeList("self")) {
                is BackendResult.Success -> {
                    _likedIds.value = result.data
                    favoritesLoaded = true
                    onSyncFavorite(isFavorite(currentMediaId))
                }
                is BackendResult.Error -> Unit
                is BackendResult.Unsupported -> Unit
            }
        }
    }

    private fun applyLike(id: String, liked: Boolean) {
        _likedIds.value = if (liked) _likedIds.value + id else _likedIds.value - id
        onSyncFavorite(isFavorite(currentMediaId))
    }

    fun cancel() {
        favoritesLoadingJob?.cancel()
    }
}
