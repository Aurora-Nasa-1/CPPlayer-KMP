package cp.player.app.repository

import cp.player.app.AppModel
import cp.player.core.BackendResult
import cp.player.core.music.AlbumDetail
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistProfile
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.Comment
import cp.player.core.music.CommentFloorPage
import cp.player.core.music.CommentPage
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistDetail
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.PlaylistTracksPage
import cp.player.core.music.ProfileBundle
import cp.player.core.music.RankingSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.SongDetailInfo
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class HotSearch(
    val keyword: String,
    val description: String = "",
)

/**
 * Application-lifetime caches for read-only screens. Kept outside [MusicRepository] because
 * AppModel exposes a lightweight repository wrapper on each access; screen data must survive
 * those wrapper instances. Account/session identity is part of every key (see [cacheRead]).
 */
private object MusicReadCaches {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val FRESH = 60_000L
    private const val STALE = 10 * 60_000L

    val tracks = SwrCache<List<TrackSummary>>(FRESH, STALE, maxEntries = 64)
    val playlists = SwrCache<List<PlaylistSummary>>(FRESH, STALE, maxEntries = 64)
    val banners = SwrCache<List<BannerItem>>(FRESH, STALE, maxEntries = 16)
    val rankings = SwrCache<List<RankingSummary>>(FRESH, STALE, maxEntries = 16)
    val albums = SwrCache<List<AlbumSummary>>(FRESH, STALE, maxEntries = 32)
    val artists = SwrCache<List<ArtistSummary>>(FRESH, STALE, maxEntries = 32)
    val searches = SwrCache<SearchResult>(15_000L, 5 * 60_000L, maxEntries = 128)
    val similarSongs = SwrCache<List<TrackSummary>>(60_000L, 15 * 60_000L, maxEntries = 64)
    val profiles = SwrCache<ProfileBundle>(30_000L, 5 * 60_000L, maxEntries = 64)
}

/** Application-facing music facade. UI models do not depend on raw Provider/API wiring. */
class MusicRepository(
    private val unifiedSource: cp.player.core.music.UnifiedMusicSource? = null,
) {

    private companion object {
        const val PLAYLIST_CACHE_FRESH_TTL_MS = 30_000L
        const val PLAYLIST_CACHE_STALE_TTL_MS = 5 * 60_000L
        const val FIRST_PLAYLIST_PAGE_SIZE = 300
    }

    private data class PlaylistCacheContext(
        val providerId: String,
        val accountId: String,
        val sessionId: String,
    )

    // Repository 是应用级单例；缓存换新不依赖某个页面的生命周期。
    private val playlistCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playlistDetailCache = SwrCache<PlaylistDetail>(
        PLAYLIST_CACHE_FRESH_TTL_MS,
        PLAYLIST_CACHE_STALE_TTL_MS,
    )
    private val playlistTracksCache = SwrCache<PlaylistTracksPage>(
        PLAYLIST_CACHE_FRESH_TTL_MS,
        PLAYLIST_CACHE_STALE_TTL_MS,
    )

    private fun playlistCacheContext(providerId: String): PlaylistCacheContext {
        val accountId = runCatching { cp.player.app.auth.AccountStore.activeUid(providerId) }.getOrNull()
            ?: AppModel.userProfileFlow.value?.resourceId
            ?: "anonymous"
        // revision 只增量标识 cookie 会话，不把 cookie/token 放入内存 key。
        val sessionId = listOf(
            runCatching { AppModel.sourceGeneration.value }.getOrDefault(0),
            runCatching { AppModel.accountGeneration.value }.getOrDefault(0),
            runCatching { AppModel.cookieStorage.revision(providerId, "default") }.getOrDefault(0L),
        ).joinToString(":")
        return PlaylistCacheContext(providerId, accountId, sessionId)
    }

    private fun readCacheKey(resource: String): String {
        val context = playlistCacheContext(AppModel.activeProviderId())
        return listOf(context.providerId, context.accountId, context.sessionId, resource).joinToString("|")
    }

    private suspend fun <T : Any> cacheRead(
        cache: SwrCache<T>,
        resource: String,
        shouldCache: (T) -> Boolean = { true },
        onRefresh: ((MusicResult<T>) -> Unit)? = null,
        fetch: suspend () -> MusicResult<T>,
    ): MusicResult<T> {
        val key = readCacheKey(resource)
        val guardedRefresh: ((MusicResult<T>) -> Unit)? = onRefresh?.let { callback ->
            { refreshed -> if (readCacheKey(resource) == key) callback(refreshed) }
        }
        return cache.get(
            key = key,
            scope = MusicReadCaches.scope,
            shouldCache = shouldCache,
            onRefresh = guardedRefresh,
            fetch = fetch,
        )
    }

    suspend fun getHotSearches(): List<HotSearch> {
        return when (val result = unifiedSource?.getHotSearches()) {
            is BackendResult.Success -> result.data.map { HotSearch(it.keyword, it.description) }
            else -> emptyList()
        }
    }

    suspend fun getSearchSuggestions(keyword: String, exclude: String = ""): List<String> {
        return when (val result = unifiedSource?.getSearchSuggestions(keyword)) {
            is BackendResult.Success -> result.data
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { exclude.isNotBlank() && it.equals(exclude, ignoreCase = true) }
                .distinct()
                .take(8)
            else -> emptyList()
        }
    }

    suspend fun search(
        keyword: String,
        type: Int,
        onRefresh: ((MusicResult<SearchResult>) -> Unit)? = null,
    ): MusicResult<SearchResult> {
        val normalized = keyword.trim()
        return cacheRead(
            MusicReadCaches.searches,
            "search:$type:$normalized",
            onRefresh = onRefresh,
        ) {
            unifiedSource?.search(normalized, type)
                ?: BackendResult.Unsupported("Unified music source is not configured")
        }
    }

    suspend fun search(keyword: String, type: Int, limit: Int, offset: Int): MusicResult<SearchResult> =
        unifiedSource?.searchPage(keyword, type, limit, offset) ?: BackendResult.Unsupported("Unified music source is not configured")

    suspend fun getPlaylistDetail(id: String): MusicResult<PlaylistDetail> =
        getPlaylistDetail(id, "DEFAULT")

    internal suspend fun getPlaylistDetail(id: String, sortKey: String): MusicResult<PlaylistDetail> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        val providerId = AppModel.activeProviderId()
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, providerId, "playlist"
        )
        val context = playlistCacheContext(providerId)
        val key = PlaylistCacheKey(
            kind = "detail",
            providerId = context.providerId,
            accountId = context.accountId,
            sessionId = context.sessionId,
            playlistId = canonicalId,
            sortKey = sortKey,
        ).encode()
        return playlistDetailCache.get(
            key,
            playlistCacheScope,
            shouldCache = { it.tracks.isNotEmpty() || it.summary.trackCount <= 0 },
        ) {
            when (val result = source.getPlaylistDetail(canonicalId)) {
                is BackendResult.Success -> {
                    val detail = result.data
                    if (detail.tracks.isEmpty()) {
                        when (val tracksResult = getPlaylistTracksCached(
                            source, canonicalId, context,
                            limit = FIRST_PLAYLIST_PAGE_SIZE, offset = 0, sortKey = sortKey,
                        )) {
                            is BackendResult.Success -> BackendResult.Success(detail.copy(tracks = tracksResult.data.tracks))
                            else -> result
                        }
                    } else result
                    }
                is BackendResult.Error -> result
                is BackendResult.Unsupported -> result
            }
        }
    }

    suspend fun getPlaylistTracks(
        id: String,
        limit: Int = FIRST_PLAYLIST_PAGE_SIZE,
        offset: Int = 0,
    ): MusicResult<PlaylistTracksPage> = getPlaylistTracks(id, limit, offset, "DEFAULT")

    internal suspend fun getPlaylistTracks(
        id: String,
        limit: Int,
        offset: Int,
        sortKey: String,
    ): MusicResult<PlaylistTracksPage> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        val providerId = AppModel.activeProviderId()
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, providerId, "playlist"
        )
        return getPlaylistTracksCached(
            source, canonicalId, playlistCacheContext(providerId), limit, offset, sortKey,
        )
    }

    private suspend fun getPlaylistTracksCached(
        source: cp.player.core.music.UnifiedMusicSource,
        canonicalId: String,
        context: PlaylistCacheContext,
        limit: Int,
        offset: Int,
        sortKey: String,
    ): MusicResult<PlaylistTracksPage> {
        val key = PlaylistCacheKey(
            kind = "tracks",
            providerId = context.providerId,
            accountId = context.accountId,
            sessionId = context.sessionId,
            playlistId = canonicalId,
            limit = limit,
            offset = offset,
            sortKey = sortKey,
        ).encode()
        return playlistTracksCache.get(key, playlistCacheScope) {
            when (val result = source.getPlaylistTracks(canonicalId, limit, offset)) {
                is BackendResult.Success -> BackendResult.Success(
                    PlaylistTracksPage(result.data.items, result.data.hasMore ?: false)
                )
                is BackendResult.Error -> result
                is BackendResult.Unsupported -> result
            }
        }
    }

    suspend fun getAlbumDetail(id: String): MusicResult<AlbumDetail> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, AppModel.activeProviderId(), "album"
        )
        return source.getAlbumDetail(canonicalId)
    }

    suspend fun subscribeAlbum(id: String, subscribe: Boolean): Boolean {
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, AppModel.activeProviderId(), "album",
        )
        val ok = (unifiedSource?.subscribeAlbum(canonicalId, subscribe) as? BackendResult.Success)?.data == true
        if (ok) AppModel.notifyAlbumFavoriteResourceChanged(id)
        return ok
    }

    suspend fun getArtistProfile(id: String): ArtistProfile? {
        val source = unifiedSource ?: return null
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, AppModel.activeProviderId(), "artist"
        )
        return (source.getArtistProfile(canonicalId) as? BackendResult.Success)?.data
    }

    suspend fun getArtistTopSongs(id: String): MusicResult<List<TrackSummary>> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, AppModel.activeProviderId(), "artist"
        )
        return when (val result = source.getArtistTracks(canonicalId, limit = 50, offset = 0)) {
            is BackendResult.Success -> BackendResult.Success(result.data.items)
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }

    suspend fun getArtistAlbums(id: String, limit: Int = 50): MusicResult<List<AlbumSummary>> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            id, AppModel.activeProviderId(), "artist"
        )
        return when (val result = source.getArtistAlbums(canonicalId, limit = limit, offset = 0)) {
            is BackendResult.Success -> BackendResult.Success(result.data.items)
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }

    suspend fun getUserPlaylistsOf(uid: String): MusicResult<List<PlaylistSummary>> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        val canonicalId = cp.player.core.music.CPMediaId.toCanonicalMediaId(
            uid, AppModel.activeProviderId(), "user"
        )
        return when (val result = source.getUserPlaylistPage(canonicalId, limit = 100, offset = 0)) {
            is BackendResult.Success -> BackendResult.Success(result.data.items)
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }

    suspend fun deletePlaylist(id: String): Boolean {
        val canonicalId = canonicalPlaylistId(id)
        return playlistMutation(canonicalId, unifiedSource?.deletePlaylist(canonicalId))
    }
    suspend fun unsubscribePlaylist(id: String): Boolean = subscribePlaylist(id, false)
    suspend fun subscribePlaylist(id: String, subscribe: Boolean): Boolean {
        val canonicalId = canonicalPlaylistId(id)
        return playlistMutation(canonicalId, unifiedSource?.subscribePlaylist(canonicalId, subscribe))
    }
    suspend fun addTracksToPlaylist(playlistId: String, ids: List<String>): Boolean {
        val canonicalId = canonicalPlaylistId(playlistId)
        val providerId = AppModel.activeProviderId()
        val tracks = ids.map { cp.player.core.music.CPMediaId.toCanonicalMediaId(it, providerId, "song") }
        return playlistMutation(canonicalId, unifiedSource?.addTracksToPlaylist(canonicalId, tracks))
    }
    suspend fun removeTracksFromPlaylist(playlistId: String, ids: List<String>): Boolean {
        val canonicalId = canonicalPlaylistId(playlistId)
        val providerId = AppModel.activeProviderId()
        val tracks = ids.map { cp.player.core.music.CPMediaId.toCanonicalMediaId(it, providerId, "song") }
        return playlistMutation(canonicalId, unifiedSource?.removeTracksFromPlaylist(canonicalId, tracks))
    }
    suspend fun renamePlaylist(playlistId: String, name: String): Boolean {
        val canonicalId = canonicalPlaylistId(playlistId)
        return playlistMutation(canonicalId, unifiedSource?.renamePlaylist(canonicalId, name))
    }

    private fun canonicalPlaylistId(id: String): String =
        cp.player.core.music.CPMediaId.toCanonicalMediaId(id, AppModel.activeProviderId(), "playlist")

    private suspend fun invalidatePlaylistReadLists() {
        MusicReadCaches.playlists.invalidateMatching {
            it.contains("|account:playlists:") || it.contains("|home:")
        }
        val providerId = AppModel.activeProviderId()
        val uid = AppModel.userProfileFlow.value?.resourceId
            ?: runCatching { cp.player.app.auth.AccountStore.activeUid(providerId) }.getOrNull()
        if (!uid.isNullOrBlank()) {
            val canonicalUid = cp.player.core.music.CPMediaId.toCanonicalMediaId(uid, providerId, "user")
            MusicReadCaches.profiles.invalidate(readCacheKey("profile:$canonicalUid"))
        }
    }

    private suspend fun playlistMutation(
        canonicalId: String,
        result: MusicResult<Boolean>?,
    ): Boolean {
        val ok = (result as? BackendResult.Success)?.data == true
        if (ok) {
            val prefix = PlaylistCacheKey.playlistPrefix(canonicalId)
            playlistDetailCache.invalidateMatching { it.startsWith(prefix) }
            playlistTracksCache.invalidateMatching { it.startsWith(prefix) }
            invalidatePlaylistReadLists()
            AppModel.notifyPlaylistChanged(cp.player.core.music.CPMediaId.toRawResourceId(canonicalId))
        }
        return ok
    }

    suspend fun createPlaylistResource(name: String): MusicResult<String> {
        val result = unifiedSource?.createPlaylist(name)
            ?: BackendResult.Unsupported("Unified music source is not configured")
        if (result is BackendResult.Success) {
            invalidatePlaylistReadLists()
            AppModel.notifyPlaylistChanged(result.data)
        }
        return result
    }

    /**
     * 心动模式/智能播放列表。
     *
     * ⚠️ `playlistId` 是**必填**：上游 `playmode/intelligence/list` 要求 `pid` 指向一个
     * 真实歌单（约定用「我喜欢的音乐」收藏夹），传 `0` 会直接报错或返回空 ——
     * 与旧项目 `PlaybackRepository.getHeartbeatSongs(songId, playlistId)` 的行为一致。
     */
    suspend fun getIntelligenceSongs(songId: String, playlistId: Long): MusicResult<List<TrackSummary>> {
        val providerId = AppModel.activeProviderId()
        val song = cp.player.core.music.CPMediaId.toCanonicalMediaId(songId, providerId, "song")
        val playlist = cp.player.core.music.CPMediaId.toCanonicalMediaId(playlistId.toString(), providerId, "playlist")
        return unifiedSource?.getIntelligenceSongs(song, playlist)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getSimilarSongs(
        seedId: String,
        onRefresh: ((MusicResult<List<TrackSummary>>) -> Unit)? = null,
    ): MusicResult<List<TrackSummary>> {
        val providerId = AppModel.activeProviderId()
        val song = cp.player.core.music.CPMediaId.toCanonicalMediaId(seedId, providerId, "song")
        return cacheRead(
            MusicReadCaches.similarSongs,
            "similar:$song",
            shouldCache = { it.isNotEmpty() },
            onRefresh = onRefresh,
        ) {
            unifiedSource?.getSimilarSongs(song)
                ?: BackendResult.Unsupported("Unified music source is not configured")
        }
    }

    suspend fun getPersonalFm(): MusicResult<List<TrackSummary>> {
        return unifiedSource?.getPersonalFm()
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getPersonalFmBatch(targetSize: Int = 18, maxRequests: Int = 8): MusicResult<List<TrackSummary>> {
        val merged = mutableListOf<TrackSummary>()
        repeat(maxRequests.coerceAtLeast(1)) {
            when (val page = getPersonalFm()) {
                is BackendResult.Success -> {
                    val newItems = page.data.filter { candidate -> merged.none { it.id == candidate.id } }
                    merged += newItems
                    if (merged.size >= targetSize) return BackendResult.Success(merged.take(targetSize))
                    if (page.data.isEmpty()) return BackendResult.Success(merged)
                }
                is BackendResult.Error -> return if (merged.isNotEmpty()) BackendResult.Success(merged) else page
                is BackendResult.Unsupported -> return if (merged.isNotEmpty()) BackendResult.Success(merged) else page
            }
        }
        return BackendResult.Success(merged)
    }

    suspend fun getRecommendedSongs(
        onRefresh: ((MusicResult<List<TrackSummary>>) -> Unit)? = null,
    ): MusicResult<List<TrackSummary>> = cacheRead(
        MusicReadCaches.tracks, "home:daily", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getRecommendedSongs() ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getRecommendedPlaylists(
        onRefresh: ((MusicResult<List<PlaylistSummary>>) -> Unit)? = null,
    ): MusicResult<List<PlaylistSummary>> = cacheRead(
        MusicReadCaches.playlists, "home:recommended-playlists", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getRecommendedPlaylists() ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getPersonalizedPlaylists(
        limit: Int,
        onRefresh: ((MusicResult<List<PlaylistSummary>>) -> Unit)? = null,
    ): MusicResult<List<PlaylistSummary>> = cacheRead(
        MusicReadCaches.playlists, "home:personalized-playlists:$limit", shouldCache = { it.isNotEmpty() },
        onRefresh = onRefresh,
    ) {
        unifiedSource?.getPersonalizedPlaylists(limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getTopPlaylists(
        limit: Int,
        onRefresh: ((MusicResult<List<PlaylistSummary>>) -> Unit)? = null,
    ): MusicResult<List<PlaylistSummary>> = cacheRead(
        MusicReadCaches.playlists, "home:top-playlists:$limit", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getTopPlaylists(limit = limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getHighqualityPlaylists(
        category: String = "全部",
        limit: Int = 30,
        onRefresh: ((MusicResult<List<PlaylistSummary>>) -> Unit)? = null,
    ): MusicResult<List<PlaylistSummary>> = cacheRead(
        MusicReadCaches.playlists, "home:high-quality-playlists:$category:$limit", shouldCache = { it.isNotEmpty() },
        onRefresh = onRefresh,
    ) {
        unifiedSource?.getHighqualityPlaylists(category, limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getPersonalizedNewSongs(limit: Int): MusicResult<List<TrackSummary>> {
        return unifiedSource?.getPersonalizedNewSongs(limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    // ======================== 首页发现区 ========================
    //
    // 首页需要的粒度：焦点图 / 榜单 / 新碟 / 区域新歌 / 热门歌手。
    // 全部经 UnifiedMusicSource，音源没声明能力时返回 Unsupported。

    /** 首页焦点图（已过滤掉应用内无法跳转的类型）。 */
    suspend fun getBanners(
        onRefresh: ((MusicResult<List<BannerItem>>) -> Unit)? = null,
    ): MusicResult<List<BannerItem>> = cacheRead(
        MusicReadCaches.banners, "home:banners", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getBanners() ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /** 榜单列表（飙升榜 / 新歌榜 / 原创榜 …）。 */
    suspend fun getRankings(
        onRefresh: ((MusicResult<List<RankingSummary>>) -> Unit)? = null,
    ): MusicResult<List<RankingSummary>> = cacheRead(
        MusicReadCaches.rankings, "home:rankings", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getRankings() ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /** 新碟上架。 */
    suspend fun getNewAlbums(
        limit: Int = 30,
        onRefresh: ((MusicResult<List<AlbumSummary>>) -> Unit)? = null,
    ): MusicResult<List<AlbumSummary>> = cacheRead(
        MusicReadCaches.albums, "home:new-albums:$limit", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getNewAlbums(limit = limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /**
     * 用户收藏的专辑 —— 专辑墙的主数据源。
     *
     * 统一走 [cp.player.core.music.UnifiedMusicSource]；音源声明了
     * `accounts.albums` 能力才有数据，否则返回 Unsupported。
     */
    suspend fun getUserAlbums(limit: Int = 100, offset: Int = 0): MusicResult<List<AlbumSummary>> {
        val uid = AppModel.userProfileFlow.value?.resourceId
            ?: AppModel.activeProviderId().let { cp.player.app.auth.AccountStore.activeUid(it) }
            ?: return BackendResult.Error("未登录或登录已过期")
        return unifiedSource?.getUserAlbums(uid, limit, offset)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /**
     * 新歌速递。
     * @param type 地区：0=全部, 7=华语, 96=欧美, 8=日本, 16=韩国
     */
    suspend fun getNewSongsByRegion(
        type: Int,
        limit: Int = 20,
        onRefresh: ((MusicResult<List<TrackSummary>>) -> Unit)? = null,
    ): MusicResult<List<TrackSummary>> = cacheRead(
        MusicReadCaches.tracks, "home:new-songs:$type:$limit", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getTopSongs(type, limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /** 热门歌手。 */
    suspend fun getHotArtists(
        limit: Int = 30,
        onRefresh: ((MusicResult<List<ArtistSummary>>) -> Unit)? = null,
    ): MusicResult<List<ArtistSummary>> = cacheRead(
        MusicReadCaches.artists, "home:hot-artists:$limit", shouldCache = { it.isNotEmpty() }, onRefresh = onRefresh,
    ) {
        unifiedSource?.getHotArtists(limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /** 精品歌单。 */
    suspend fun getHighQualityPlaylists(
        limit: Int = 30,
        onRefresh: ((MusicResult<List<PlaylistSummary>>) -> Unit)? = null,
    ): MusicResult<List<PlaylistSummary>> = getHighqualityPlaylists(
        category = "全部", limit = limit, onRefresh = onRefresh,
    )

    suspend fun getPlaylistDetail(id: Long): MusicResult<PlaylistDetail> = getPlaylistDetail(id.toString())

    // ======================== 专辑 / 歌手 / 用户主页 ========================
    //
    // 专辑 / 歌手 / 他人主页：按页面真正需要的粒度暴露模型，
    // 避免 UI 再退化成「按名字重新搜一次」。

    /** 专辑详情（含曲目）。 */
    suspend fun getAlbumDetail(id: Long): MusicResult<AlbumDetail> = getAlbumDetail(id.toString())

    /**
     * 收藏 / 取消收藏专辑（上游 `album/sub`，`t` = 1 收藏 / 2 取消收藏）。
     *
     * 没有返回值以外的「当前是否已收藏」查询接口 —— 判定只能靠
     * [getUserAlbums] 拉全集本地比对，见 `AlbumDetailModel`。
     *
     * ⚠️ 成功后**必须**让读取方知道收藏列表变了：专辑墙（`AlbumWallScreenModel`）
     * 与详情页都拿 `album/sublist` 当收藏真值，不发信号的话用户收藏完回到墙上
     * 找不到刚收的专辑。
     */
    suspend fun subscribeAlbum(id: Long, subscribe: Boolean): Boolean = subscribeAlbum(id.toString(), subscribe)

    /** 歌手介绍/资料；返回 null 表示这个 id 不是歌手。取消（如快速离开页面）会正常上抛。 */
    suspend fun getArtistProfile(id: Long): ArtistProfile? = getArtistProfile(id.toString())

    /** 歌手热门歌曲（最多 50 首）。 */
    suspend fun getArtistTopSongs(id: Long): MusicResult<List<TrackSummary>> = getArtistTopSongs(id.toString())

    /** 歌手专辑。 */
    suspend fun getArtistAlbums(id: Long, limit: Int = 50): MusicResult<List<AlbumSummary>> =
        getArtistAlbums(id.toString(), limit)

    /**
     * 拉取「他人主页」的完整数据包（歌手优先，否则按普通用户）。
     *
     * 逻辑逐条对照旧项目 `UserViewModel.fetchOtherUserProfile`：
     * `artist/detail` 与 `user/detail` 用**同一段 id 空间**，同一个 id 在两边都可能返回数据，
     * 所以只能「先试歌手、拿到 `data.artist` 才认」，否则按普通用户处理。
     *
     * 歌手分支额外拉「热门歌曲」与「专辑」；用户分支拉「歌单」。
     * 这些子请求**失败不致命** —— 资料本身能显示就先显示，空的区块由页面自己收敛。
     */
    suspend fun getProfileBundle(
        uid: Long,
        onRefresh: ((MusicResult<ProfileBundle>) -> Unit)? = null,
        onPartial: ((MusicResult<ProfileBundle>) -> Unit)? = null,
    ): MusicResult<ProfileBundle> = getProfileBundle(uid.toString(), onRefresh, onPartial)

    suspend fun getProfileBundle(
        uid: String,
        onRefresh: ((MusicResult<ProfileBundle>) -> Unit)? = null,
        onPartial: ((MusicResult<ProfileBundle>) -> Unit)? = null,
    ): MusicResult<ProfileBundle> {
        val providerId = AppModel.activeProviderId()
        val canonicalUid = cp.player.core.music.CPMediaId.toCanonicalMediaId(uid, providerId, "user")
        val resourceKey = "profile:$canonicalUid"
        val requestKey = readCacheKey(resourceKey)

        fun publishPartial(bundle: ProfileBundle) {
            if (onPartial != null && readCacheKey(resourceKey) == requestKey) {
                runCatching { onPartial(BackendResult.Success(bundle)) }
            }
        }

        return cacheRead(
            MusicReadCaches.profiles,
            resourceKey,
            onRefresh = onRefresh,
        ) {
            val artist = getArtistProfile(uid)
            if (artist != null) {
                val baseBundle = ProfileBundle(
                    uid = uid.toLongOrNull() ?: 0L,
                    resourceId = uid,
                    isArtist = true,
                    nickname = artist.name,
                    avatarUrl = artist.avatarUrl,
                    signature = artist.briefDesc ?: artist.alias.joinToString(" / ").ifBlank { null },
                    primaryCount = artist.albumSize,
                    follows = 0,
                    followeds = artist.followeds,
                )
                publishPartial(baseBundle)
                val (songsResult, albumsResult) = coroutineScope {
                    val songs = async { getArtistTopSongs(uid) }
                    val albums = async { getArtistAlbums(uid) }
                    songs.await() to albums.await()
                }
                val songs = (songsResult as? BackendResult.Success)?.data.orEmpty()
                val albums = (albumsResult as? BackendResult.Success)?.data.orEmpty()
                BackendResult.Success(baseBundle.copy(
                    primaryCount = if (artist.albumSize > 0) artist.albumSize else albums.size,
                    albums = albums,
                    songs = songs,
                ))
            } else {
                val profile = when (val profileResult = unifiedSource?.getUserProfile(uid)
                    ?: BackendResult.Unsupported("No SDK source")) {
                    is BackendResult.Success -> profileResult.data
                    is BackendResult.Error -> return@cacheRead profileResult
                    is BackendResult.Unsupported -> return@cacheRead profileResult
                }
                val baseBundle = ProfileBundle(
                    uid = uid.toLongOrNull() ?: 0L,
                    resourceId = uid,
                    isArtist = false,
                    nickname = profile.nickname,
                    avatarUrl = profile.avatarUrl,
                    signature = profile.signature,
                    primaryCount = profile.playlistCount,
                    follows = profile.follows,
                    followeds = profile.followeds,
                )
                publishPartial(baseBundle)
                val playlists = (getUserPlaylistsOf(uid) as? BackendResult.Success)?.data.orEmpty()
                BackendResult.Success(baseBundle.copy(playlists = playlists))
            }
        }
    }

    /** 他人歌单列表（不含「我喜欢的音乐」这类只属于本人的条目）。 */
    suspend fun getUserPlaylistsOf(uid: Long): MusicResult<List<PlaylistSummary>> = getUserPlaylistsOf(uid.toString())

    /** 用户听歌排行；`type` 0=所有时间, 1=最近一周。 */
    suspend fun getUserRecords(uid: Long, type: Int = 0): MusicResult<List<TrackSummary>> {
        return unifiedSource?.getUserRecords(uid.toString(), type)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /** 关注 / 粉丝列表。 */
    suspend fun getFollows(uid: Long, limit: Int = 50): MusicResult<List<ArtistSummary>> {
        return unifiedSource?.getUserFollows(uid.toString(), limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getFolloweds(uid: Long, limit: Int = 50): MusicResult<List<ArtistSummary>> {
        return unifiedSource?.getUserFolloweds(uid.toString(), limit)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getPlaylistTracks(
        id: Long,
        limit: Int = FIRST_PLAYLIST_PAGE_SIZE,
        offset: Int = 0,
    ): MusicResult<PlaylistTracksPage> = getPlaylistTracks(id.toString(), limit, offset)

    suspend fun getCurrentUserPlaylists(
        onRefresh: ((MusicResult<List<PlaylistSummary>>) -> Unit)? = null,
    ): MusicResult<List<PlaylistSummary>> {
        val cachedUid = AppModel.userProfileFlow.value?.resourceId
            ?: AppModel.activeProviderId().let { cp.player.app.auth.AccountStore.activeUid(it) }
        val uid = cachedUid
            ?: return BackendResult.Error("未登录或登录已过期")
        return cacheRead(
            MusicReadCaches.playlists,
            "account:playlists:$uid",
            shouldCache = { it.isNotEmpty() },
            onRefresh = onRefresh,
        ) { getUserPlaylistsOf(uid) }
    }

    suspend fun getUserCloud(limit: Int = 200, offset: Int = 0): MusicResult<List<TrackSummary>> {
        return unifiedSource?.getUserCloud(limit, offset)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun getLikeList(): MusicResult<Set<String>> {
        val cachedUid = AppModel.userProfileFlow.value?.resourceId
            ?: AppModel.activeProviderId().let { cp.player.app.auth.AccountStore.activeUid(it) }
        val uid = cachedUid
            ?: return BackendResult.Error("未登录或登录已过期")
        return unifiedSource?.getLikeList(uid)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    suspend fun deletePlaylist(id: Long): Boolean {
        return deletePlaylist(id.toString())
    }

    suspend fun unsubscribePlaylist(id: Long): Boolean {
        return unsubscribePlaylist(id.toString())
    }

    /** 收藏 / 取消收藏歌单。 */
    suspend fun subscribePlaylist(id: Long, subscribe: Boolean): Boolean {
        return subscribePlaylist(id.toString(), subscribe)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, ids: List<String>): Boolean {
        return addTracksToPlaylist(playlistId.toString(), ids)
    }

    /**
     * 重命名自己创建的歌单。
     *
     * ⚠️ 走 `playlist/name/update`（只改名），不要用 `playlist/update` ——
     * 后者要求同时带上 `desc` / `tags`，漏传会把歌单简介与标签清空。
     */
    suspend fun renamePlaylist(playlistId: Long, name: String): Boolean {
        return renamePlaylist(playlistId.toString(), name)
    }

    suspend fun removeTracksFromPlaylist(playlistId: Long, ids: List<String>): Boolean {
        return removeTracksFromPlaylist(playlistId.toString(), ids)
    }

    suspend fun likeSong(songId: String, like: Boolean): Boolean {
        val providerId = AppModel.activeProviderId()
        val mediaId = cp.player.core.music.CPMediaId.toCanonicalMediaId(songId, providerId, "song")
        return (unifiedSource?.likeSong(mediaId, like) as? BackendResult.Success)?.data == true
    }

    /** 标记「不感兴趣」（推荐流去重）。 */
    suspend fun dislikeSong(songId: String): Boolean {
        val providerId = AppModel.activeProviderId()
        val mediaId = cp.player.core.music.CPMediaId.toCanonicalMediaId(songId, providerId, "song")
        return (unifiedSource?.dislikeSong(mediaId) as? BackendResult.Success)?.data == true
    }

    /**
     * 新建歌单，返回新歌单 id。
     *
     * ⚠️ 这是**写操作**，不在缓存名单内——每次都直连网络，不存在「建完马上查不到」的缓存窗口。
     */
    suspend fun createPlaylist(name: String): MusicResult<Long> {
        return when (val result = createPlaylistResource(name)) {
            is BackendResult.Success -> result.data.toLongOrNull()?.let { BackendResult.Success(it) }
                ?: BackendResult.Error("Provider returned an invalid playlist ID")
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }

    /** 歌曲详情信息（信息弹窗用）；[fallback] 提供列表里已知的回退字段。 */
    suspend fun getSongDetailInfo(songId: String, fallback: SongDetailInfo? = null): MusicResult<SongDetailInfo> {
        val source = unifiedSource ?: return BackendResult.Unsupported("Unified music source is not configured")
        return when (val result = source.getTrackDetail(songId)) {
            is BackendResult.Success -> {
                val track = result.data
                val known = fallback?.takeIf { it.songId == songId || it.songId == track.id }
                BackendResult.Success(SongDetailInfo(
                    songId = track.id, name = track.name, artist = track.artist,
                    album = track.album ?: known?.album.orEmpty(), durationMs = track.durationMs,
                    publishTimeMs = known?.publishTimeMs, commentCount = known?.commentCount,
                    mvId = known?.mvId, maxBitrate = known?.maxBitrate, fee = known?.fee,
                ))
            }
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }

    /**
     * 评论列表（顶层评论，分页）。
     *
     * ⚠️ 端点固定是 `comment/new` —— 老端点（`comment/music` 等）的请求体里没有 `sortType`，
     * 用它就永远切不了排序。列表走**读透缓存**（键里含 `type` / `offset` / `sortType`），
     * 所以同一排序下短时间内重复进入不会重新打网络；点赞 / 发表评论会失效这份缓存。
     *
     * ⚠️ **分页按页大小前进**：`offset` 每次 +`limit`（不要按「本页实际回来几条」加），
     * 端点那边是 `pageNo = offset / limit + 1`，算错就会把同一页反复拉回来。
     *
     * @param sortType 1=推荐 / 2=热度 / 3=最新。返回体的 [CommentPage.sortType] 是服务端
     *   **实际**使用的排序，翻页时必须回传它（未登录时「推荐」会被服务端按「热度」服务，
     *   再用「推荐」请求下一页会 400）。
     */
    suspend fun getComments(
        rawId: String,
        type: String,
        limit: Int = 20,
        offset: Int = 0,
        sortType: Int = 1,
        cursor: String? = null,
    ): MusicResult<CommentPage> {
        return unifiedSource?.getCommentsPage(canonicalCommentId(rawId, type), type, limit, offset, sortType, cursor)
            ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /**
     * 楼层（某条顶层评论下的回复），分页。
     *
     * @param time 翻页游标；首页传 0。返回体的 [CommentFloorPage.nextTime] 原样带回请求下一页。
     */
    suspend fun getFloorComments(
        rawId: String,
        parentCommentId: Long,
        type: String,
        limit: Int = 20,
        time: Long = 0L,
    ): MusicResult<CommentFloorPage> {
        return unifiedSource?.getFloorComments(
            canonicalCommentId(rawId, type), parentCommentId, type, limit, time,
        ) ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /**
     * 发表评论 / 回复。
     *
     * @param replyToCommentId 被回复的评论 id；null = 发表新的顶层评论
     * @param parentCommentId 本次回复所属的楼层（顶层评论传 0）；用于把返回体归位到正确楼层
     */
    suspend fun postComment(
        rawId: String,
        type: String,
        content: String,
        replyToCommentId: Long? = null,
        parentCommentId: Long = 0L,
    ): MusicResult<Comment?> {
        return unifiedSource?.postComment(
            canonicalCommentId(rawId, type), type, content, replyToCommentId, parentCommentId,
        ) ?: BackendResult.Unsupported("Unified music source is not configured")
    }

    /** 点赞 / 取消点赞评论。 */
    suspend fun likeComment(rawId: String, commentId: Long, type: String, like: Boolean): Boolean {
        return (unifiedSource?.likeComment(
            canonicalCommentId(rawId, type), commentId, type, like,
        ) as? BackendResult.Success)?.data == true
    }

    private fun canonicalCommentId(rawId: String, type: String): String {
        val resourceType = when (type) {
            "music" -> "song"
            else -> type
        }
        return cp.player.core.music.CPMediaId.toCanonicalMediaId(
            rawId, AppModel.activeProviderId(), resourceType,
        )
    }

    /** 从云盘删除歌曲（上游 `user/cloud/del`）。 */
    suspend fun deleteUserCloud(songId: String): Boolean {
        val providerId = AppModel.activeProviderId()
        val mediaId = cp.player.core.music.CPMediaId.toCanonicalMediaId(songId, providerId, "song")
        return (unifiedSource?.deleteUserCloud(mediaId) as? BackendResult.Success)?.data == true
    }
}

/** Reuses the cache-owning repository while the application music source remains the same instance. */
internal class MusicRepositoryProvider(
    private val source: () -> cp.player.core.music.UnifiedMusicSource,
) {
    private var currentSource: cp.player.core.music.UnifiedMusicSource? = null
    private var currentRepository: MusicRepository? = null

    @Synchronized
    fun get(): MusicRepository {
        val activeSource = source()
        if (currentRepository == null || currentSource !== activeSource) {
            currentSource = activeSource
            currentRepository = MusicRepository(activeSource)
        }
        return currentRepository!!
    }
}

/**
 * 搜索类型（UI 侧语义化常量）。
 *
 * 与上游 `cloudsearch` 的 `type` 取值一致，但 UI 只认这里（边界规则见 docs/dev/ARCHITECTURE.md §3.2）。
 */
object SearchType {
    const val SONG = 1
    const val ALBUM = 10
    const val ARTIST = 100
    const val PLAYLIST = 1000
}
