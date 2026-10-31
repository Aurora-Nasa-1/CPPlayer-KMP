package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiMethod
import cp.player.core.api.MusicApiService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicRepositoryResourceIdTest {
    private class Fixture {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val responses = mutableMapOf(
            MusicApiMethod.PLAYLIST_DETAIL to """{"code":0,"data":{"id":"001","title":"List","tracks":[]}}""",
            MusicApiMethod.PLAYLIST_TRACK_ALL to """{"code":0,"data":{"tracks":[],"hasMore":false}}""",
            MusicApiMethod.ALBUM_DETAIL to """{"code":0,"data":{"id":"album-uuid","title":"Album","tracks":[]}}""",
            MusicApiMethod.USER_PLAYLIST to """{"code":0,"data":[{"id":"playlist-uuid","title":"List"}]}""",
            MusicApiMethod.USER_LIKE_LIST to """{"code":0,"data":{"ids":["track-hash","001"]}}""",
        )

        @Suppress("UNCHECKED_CAST")
        val api: MusicApiService = java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader, arrayOf(MusicApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getLoginStatus" -> Json.parseToJsonElement(
                    """{"code":0,"data":{"account":{"id":"user-uuid"},"profile":{"userId":"user-uuid","name":"User"}}}""",
                )
                "callApi" -> {
                    val endpoint = args!![0] as String
                    calls += endpoint to (args[1] as Map<String, String>)
                    Json.parseToJsonElement(responses[endpoint] ?: """{"code":401}""")
                }
                else -> error("Unexpected API overload: ${method.name}")
            }
        } as MusicApiService

        val repository = MusicRepository(api)
    }

    @Test
    fun `catalog and account reads preserve opaque ids end to end`() = runBlocking {
        val f = Fixture()
        assertEquals("001", f.repository.getPlaylistDetail("001").getOrNull()?.summary?.resourceId)
        assertEquals(false, f.repository.getPlaylistTracks("001", 50, 100).getOrNull()?.hasMore)
        assertEquals("album-uuid", f.repository.getAlbumDetail("album-uuid").getOrNull()?.resourceId)
        assertEquals("playlist-uuid", f.repository.getUserPlaylistsOf("user-uuid").getOrNull()?.single()?.resourceId)
        assertTrue(f.repository.getCurrentUserPlaylists() is BackendResult.Success)
        assertEquals(setOf("track-hash", "001"), f.repository.getLikeList().getOrNull())
        assertEquals(listOf(
            MusicApiMethod.PLAYLIST_DETAIL to mapOf("id" to "001"),
            MusicApiMethod.PLAYLIST_TRACK_ALL to mapOf("id" to "001", "limit" to "50", "offset" to "100"),
            MusicApiMethod.ALBUM_DETAIL to mapOf("id" to "album-uuid"),
            MusicApiMethod.USER_PLAYLIST to mapOf("uid" to "user-uuid"),
            MusicApiMethod.USER_PLAYLIST to mapOf("uid" to "user-uuid"),
            MusicApiMethod.USER_LIKE_LIST to mapOf("uid" to "user-uuid"),
        ), f.calls)
    }

    @Test
    fun `opaque artist profile uses the supported songs fallback`() = runBlocking {
        val f = Fixture()
        f.responses[MusicApiMethod.ARTIST_DETAIL] = """{"code":0,"data":{"id":"artist-hash","name":"Artist"}}"""
        f.responses[MusicApiMethod.ARTIST_TOP_SONG] = """{"code":501}"""
        f.responses[MusicApiMethod.ARTIST_SONGS] = """{"code":0,"data":{"tracks":[{"id":"track-hash","title":"Track"}]}}"""
        f.responses[MusicApiMethod.ARTIST_ALBUM] = """{"code":0,"data":[{"id":"album-uuid","title":"Album"}]}"""
        val bundle = f.repository.getProfileBundle("artist-hash").getOrNull()
            ?: error("Expected artist bundle")
        assertTrue(bundle.isArtist)
        assertEquals("artist-hash", bundle.resourceId)
        assertEquals(0L, bundle.uid)
        assertEquals("track-hash", bundle.songs.single().id)
        assertEquals("album-uuid", bundle.albums.single().resourceId)
        assertTrue(f.calls.all { it.second["id"] == "artist-hash" })
    }

    @Test
    fun `string playlist writes dispatch once without recursion`() = runBlocking {
        val f = Fixture()
        assertFalse(f.repository.deletePlaylist("playlist-uuid"))
        assertFalse(f.repository.subscribePlaylist("playlist-uuid", true))
        assertFalse(f.repository.renamePlaylist("playlist-uuid", "Renamed"))
        assertFalse(f.repository.addTracksToPlaylist("playlist-uuid", listOf("song-hash", "001")))
        assertFalse(f.repository.removeTracksFromPlaylist("playlist-uuid", listOf("song-hash")))
        assertEquals(listOf(
            MusicApiMethod.PLAYLIST_DELETE to mapOf("id" to "playlist-uuid"),
            MusicApiMethod.PLAYLIST_SUBSCRIBE to mapOf("id" to "playlist-uuid", "t" to "1"),
            MusicApiMethod.PLAYLIST_NAME_UPDATE to mapOf("id" to "playlist-uuid", "name" to "Renamed"),
            MusicApiMethod.PLAYLIST_TRACKS to mapOf("op" to "add", "pid" to "playlist-uuid", "tracks" to "song-hash,001"),
            MusicApiMethod.PLAYLIST_TRACKS to mapOf("op" to "del", "pid" to "playlist-uuid", "tracks" to "song-hash"),
        ), f.calls)
    }
}
