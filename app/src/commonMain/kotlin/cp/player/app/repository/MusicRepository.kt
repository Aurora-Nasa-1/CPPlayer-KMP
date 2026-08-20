package cp.player.app.repository

import cp.player.app.extractUidFromLoginStatus
import cp.player.kmp.BackendResult
import cp.player.kmp.api.MusicApiService
import cp.player.kmp.music.MusicResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistDetail
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.PlaylistTracksPage
import cp.player.kmp.music.SearchResult
import cp.player.kmp.music.TrackSummary
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
        val seenIds = mutableSetOf<String>()
        repeat(maxRequests.coerceAtLeast(1)) {
            when (val page = getPersonalFm()) {
                is BackendResult.Success -> {
                    // Bolt: O(1) deduplication instead of O(N^2) using merged.none
                    val newItems = page.data.filter { seenIds.add(it.id) }
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
        val obj = json as? JsonObject
        val code = (obj?.get("code") as? JsonPrimitive)?.intOrNull
            ?: (obj?.get("status") as? JsonPrimitive)?.intOrNull
        return code == null || code == 200 || code == 0 || code == 201 || code == 301
    }
}
