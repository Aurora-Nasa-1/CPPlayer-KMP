package cp.player.app.repository

import cp.player.app.extractUidFromLoginStatus
import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.MusicResult
import cp.player.core.music.MusicSourceFromApi
import cp.player.core.music.PlaylistDetail
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.PlaylistTracksPage
import cp.player.core.music.RankingSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.TrackSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Application-facing music facade. UI models do not depend on raw Provider/API wiring. */
class MusicRepository(private val api: MusicApiService) {
    suspend fun getHotSearches(): JsonElement = api.getHotSearches()

    suspend fun getSearchSuggestions(keyword: String): JsonElement =
        api.getSearchSuggestions(keyword)

    suspend fun search(keyword: String, type: Int): MusicResult<SearchResult> =
        MusicSourceFromApi.search(api, keyword, type)

    suspend fun getIntelligenceSongs(seedId: String): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseFmSongs(api.getIntelligenceList(seedId, 0L))

    suspend fun getSimilarSongs(seedId: String): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseFmSongs(api.getSimilarSongs(seedId))

    suspend fun getPersonalFm(): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getPersonalFm(api)

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

    suspend fun getRecommendedSongs(): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseRecommendedSongs(api.getRecommendedSongs())

    suspend fun getRecommendedPlaylists(): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.parseRecommendedPlaylists(api.getRecommendedPlaylists())

    suspend fun getPersonalizedPlaylists(limit: Int): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.parseRecommendedPlaylists(api.getPersonalizedPlaylists(limit))

    suspend fun getTopPlaylists(limit: Int): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.parseRecommendedPlaylists(api.getTopPlaylists(limit = limit))

    suspend fun getPersonalizedNewSongs(limit: Int): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseRecommendedSongs(api.getPersonalizedNewSongs(limit))

    // ======================== 首页发现区 ========================
    //
    // 这几项此前只存在于 [MusicApiService]，首页够不到，于是首页只能靠
    // 「日推 + 两个歌单栅格」撑版面，其余位置用纯入口卡片填空。
    // 这里把它们按首页真正需要的粒度暴露出来。

    /** 首页焦点图（已过滤掉应用内无法跳转的类型）。 */
    suspend fun getBanners(): MusicResult<List<BannerItem>> =
        MusicSourceFromApi.getBanners(api)

    /** 榜单列表（飙升榜 / 新歌榜 / 原创榜 …）。 */
    suspend fun getRankings(): MusicResult<List<RankingSummary>> =
        MusicSourceFromApi.getRankings(api)

    /** 新碟上架。 */
    suspend fun getNewAlbums(limit: Int = 30): MusicResult<List<AlbumSummary>> =
        MusicSourceFromApi.getNewAlbums(api, limit = limit)

    /**
     * 新歌速递。
     * @param type 地区：0=全部, 7=华语, 96=欧美, 8=日本, 16=韩国
     */
    suspend fun getNewSongsByRegion(type: Int, limit: Int = 20): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getTopSongs(api, type).let { result ->
            when (result) {
                is BackendResult.Success -> BackendResult.Success(result.data.take(limit))
                else -> result
            }
        }

    /** 热门歌手。 */
    suspend fun getHotArtists(limit: Int = 30): MusicResult<List<ArtistSummary>> =
        MusicSourceFromApi.getHotArtists(api, limit = limit)

    /** 精品歌单。 */
    suspend fun getHighQualityPlaylists(limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.getHighQualityPlaylists(api, limit = limit)

    suspend fun getPlaylistDetail(id: Long): MusicResult<PlaylistDetail> =
        MusicSourceFromApi.getPlaylistDetail(api, id)

    suspend fun getPlaylistTracks(id: Long, limit: Int = 300, offset: Int = 0): MusicResult<PlaylistTracksPage> =
        MusicSourceFromApi.getPlaylistTracks(api, id, limit, offset)

    suspend fun getCurrentUserPlaylists(): MusicResult<List<PlaylistSummary>> {
        val uid = extractUidFromLoginStatus(api.getLoginStatus())
            ?: return BackendResult.Error("未登录或登录已过期")
        return MusicSourceFromApi.getUserPlaylists(api, uid)
    }

    suspend fun getUserCloud(limit: Int = 200, offset: Int = 0): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getUserCloud(api, limit, offset)

    suspend fun getLikeList(): MusicResult<Set<String>> {
        val uid = extractUidFromLoginStatus(api.getLoginStatus())
            ?: return BackendResult.Error("未登录或登录已过期")
        val json = api.getLikeList(uid)
        val array = (json as? JsonObject)?.get("ids") as? JsonArray ?: JsonArray(emptyList())
        return BackendResult.Success(
            array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.toSet()
        )
    }

    suspend fun deletePlaylist(id: Long): Boolean = isApiSuccess(api.deletePlaylist(id))

    suspend fun unsubscribePlaylist(id: Long): Boolean = isApiSuccess(api.subscribePlaylist(id, t = 2))

    suspend fun addTracksToPlaylist(playlistId: Long, ids: List<String>): Boolean =
        isApiSuccess(api.addTracksToPlaylist(playlistId, ids))

    suspend fun removeTracksFromPlaylist(playlistId: Long, ids: List<String>): Boolean =
        isApiSuccess(api.removeTracksFromPlaylist(playlistId, ids))

    suspend fun likeSong(songId: String, like: Boolean): Boolean = isApiSuccess(api.likeSong(songId, like))

    suspend fun getLoginStatus(): JsonElement = api.getLoginStatus()

    /** Transitional escape hatch for operations not yet migrated to typed repositories. */
    suspend fun raw(block: suspend MusicApiService.() -> JsonElement): JsonElement = api.block()

    private fun isApiSuccess(json: JsonElement): Boolean {
        val code = ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            ?: ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull
        return code == null || code == 200 || code == 0 || code == 201 || code == 301
    }
}
