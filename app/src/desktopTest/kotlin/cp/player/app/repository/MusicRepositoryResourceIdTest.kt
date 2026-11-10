package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.music.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 仓库层只经强类型 [UnifiedMusicSource] 取数。
 *
 * 旧的 JSON 桥（`MusicApiService` + `callApi` 断言）随兼容层一起删除；
 * 这里保留的是「opaque id 端到端不被改写」与「不猜可选字段」两件事。
 */
class MusicRepositoryResourceIdTest {

    @Test
    fun `comment repository preserves opaque cursors and effective server sorting`() {
        runBlocking {
            val calls = mutableListOf<List<Any?>>()
            val page = CommentPage(emptyList(), 0, true, "", 2, nextCursor = "")
            @Suppress("UNCHECKED_CAST")
            val source = java.lang.reflect.Proxy.newProxyInstance(
                UnifiedMusicSource::class.java.classLoader,
                arrayOf(UnifiedMusicSource::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getCommentsPage" -> {
                        calls += args.take(6)
                        BackendResult.Success(page)
                    }
                    else -> error("Unexpected source call: ${method.name}")
                }
            } as UnifiedMusicSource
            val repository = MusicRepository(source)
            for (cursor in listOf(null, "", "opaque+/==:%20")) {
                assertEquals(page, repository.getComments(
                    "test://playlist/Playlist-A", "playlist", 7, 21, 1, cursor,
                ).getOrNull())
                assertEquals(listOf("test://playlist/Playlist-A", "playlist", 7, 21, 1, cursor), calls.last())
            }
            assertEquals(3, calls.size)
        }
    }

    @Test
    fun `typed search and detail never round trip through JSON or guess optional metadata`() = runBlocking {
        val track = TrackSummary("test://song/001", "Short", "Artist", null, null, 9_500L)
        val requests = mutableListOf<Pair<Int, Int>>()
        val source = object : UnifiedMusicSource {
            override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(track)
            override suspend fun getTrackDetails(mediaIds: List<String>) = BackendResult.Success(listOf(track))
            override suspend fun getSongUrl(mediaId: String, level: String) = BackendResult.Unsupported("unused")
            override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
                searchPage(keywords, type, 30, 0, providers)
            override suspend fun searchPage(keywords: String, type: Int, limit: Int, offset: Int, providers: List<String>?): MusicResult<SearchResult> {
                requests += limit to offset
                return BackendResult.Success(SearchResult(listOf(track), emptyList(), emptyList()))
            }
            override suspend fun getUserPlaylists(providerId: String, uid: Long) = BackendResult.Unsupported("unused")
        }
        val repository = MusicRepository(source)
        assertEquals(track, repository.search("short", 1, 10, 20).getOrNull()?.songs?.single())
        assertEquals(listOf(10 to 20), requests)
        val detail = repository.getSongDetailInfo(track.id).getOrNull()!!
        assertEquals(track.id, detail.songId)
        assertEquals(9_500L, detail.durationMs)
        assertEquals(null, detail.fee)
        assertEquals(null, detail.maxBitrate)
        assertTrue(MusicRepository().search("short", 1) is BackendResult.Unsupported)
    }

    @Test
    fun `strongly typed unifiedSource is preferred without JSON serialization round trip`() = runBlocking {
        val track = TrackSummary("netease://song/001", "Track", "Artist", null, null, 180_000L)
        val album = AlbumDetail(
            id = 101L, name = "Album", coverUrl = null, artistName = "Artist", artistId = 201L,
            publishTimeMs = null, company = null, description = null, tracks = listOf(track),
            resourceId = "album-001", artistResourceId = "artist-001",
        )
        val playlist = PlaylistDetail(
            summary = PlaylistSummary(1L, "List", null, 1, "Creator", "list-001"),
            tracks = listOf(track),
            description = "Desc",
        )
        val profile = ArtistProfile(
            id = 201L, name = "Artist", avatarUrl = null, briefDesc = "Bio", alias = emptyList(),
            albumSize = 5, musicSize = 10, followeds = 100, resourceId = "artist-001",
        )

        val source = object : UnifiedMusicSource {
            override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(track)
            override suspend fun getTrackDetails(mediaIds: List<String>) = BackendResult.Success(listOf(track))
            override suspend fun getSongUrl(mediaId: String, level: String) = BackendResult.Unsupported("unused")
            override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
                BackendResult.Unsupported("unused")
            override suspend fun getUserPlaylists(providerId: String, uid: Long) =
                BackendResult.Unsupported("unused")
            override suspend fun getPlaylistDetail(mediaId: String): MusicResult<PlaylistDetail> =
                BackendResult.Success(playlist)
            override suspend fun getPlaylistTracks(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<TrackSummary>> =
                BackendResult.Success(CatalogPage(listOf(track), 1, false))
            override suspend fun getAlbumDetail(mediaId: String): MusicResult<AlbumDetail> =
                BackendResult.Success(album)
            override suspend fun getArtistProfile(mediaId: String): MusicResult<ArtistProfile> =
                BackendResult.Success(profile)
            override suspend fun getArtistTracks(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<TrackSummary>> =
                BackendResult.Success(CatalogPage(listOf(track), 1, false))
            override suspend fun getArtistAlbums(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<AlbumSummary>> =
                BackendResult.Success(CatalogPage(listOf(AlbumSummary(101L, "Album", null, "Artist", 1, null, 201L, "album-001", "artist-001")), 1, false))
            override suspend fun getUserPlaylistPage(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<PlaylistSummary>> =
                BackendResult.Success(CatalogPage(listOf(playlist.summary), 1, false))
        }

        val repository = MusicRepository(source)
        assertEquals(playlist.summary.resourceId, repository.getPlaylistDetail("list-001").getOrNull()?.summary?.resourceId)
        assertEquals(1, repository.getPlaylistTracks("list-001", 300, 0).getOrNull()?.tracks?.size)
        assertEquals(album.resourceId, repository.getAlbumDetail("album-001").getOrNull()?.resourceId)
        assertEquals(profile.resourceId, repository.getArtistProfile("artist-001")?.resourceId)
        assertEquals(track.id, repository.getArtistTopSongs("artist-001").getOrNull()?.single()?.id)
        assertEquals("album-001", repository.getArtistAlbums("artist-001", 10).getOrNull()?.single()?.resourceId)
        assertEquals(listOf(playlist.summary), repository.getUserPlaylistsOf("user-001").getOrNull())
    }

    @Test
    fun `repository operates without legacy api bridge when unifiedSource is supplied`() = runBlocking {
        val playlist = PlaylistDetail(
            summary = PlaylistSummary(1L, "List", null, 1, null, "list-001"),
            tracks = emptyList(),
            description = null,
        )
        @Suppress("UNCHECKED_CAST")
        val source = java.lang.reflect.Proxy.newProxyInstance(
            UnifiedMusicSource::class.java.classLoader,
            arrayOf(UnifiedMusicSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlaylistDetail" -> BackendResult.Success(playlist)
                else -> BackendResult.Unsupported("unused")
            }
        } as UnifiedMusicSource
        val repository = MusicRepository(source)
        assertEquals("list-001", repository.getPlaylistDetail("list-001").getOrNull()?.summary?.resourceId)
        assertTrue(repository.getIntelligenceSongs("song-1", 1L) is BackendResult.Unsupported)
    }
}
