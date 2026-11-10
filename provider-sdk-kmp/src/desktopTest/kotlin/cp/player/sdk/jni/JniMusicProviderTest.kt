package cp.player.sdk.jni

import cp.player.sdk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JniMusicProviderTest {
    @Test
    fun `all current SDK operations use typed JNI dispatch and close once`() {
        runBlocking {
            val session = RecordingSession()
            val provider = JniMusicProvider(session)

            provider.capabilities()
            provider.search(SearchRequest("query"))
            provider.get(IdRequest("track"))
            provider.getMany(BatchRequest(listOf("track")))
            provider.resolve(ResolveRequest("track"))
            provider.getLyrics(IdRequest("track"))
            provider.searchPlaylists(SearchRequest("query"))
            provider.getPlaylist(IdRequest("playlist"))
            provider.getPlaylistTracks(PagedIdRequest("playlist"))
            provider.getUserPlaylists(PagedIdRequest("user"))
            provider.searchAlbums(SearchRequest("query"))
            provider.getAlbum(IdRequest("album"))
            provider.searchArtists(SearchRequest("query"))
            provider.getArtist(IdRequest("artist"))
            provider.getArtistTracks(PagedIdRequest("artist"))
            provider.getArtistAlbums(PagedIdRequest("artist"))
            provider.getRecommendedSongs(RecommendationsRequest(12))
            provider.getRecommendedPlaylists(RecommendationsRequest(12))
            provider.getPersonalizedPlaylists(RecommendationsRequest(12))
            provider.getTopPlaylists(PlaylistBrowseRequest("全部", 12, "hot"))
            provider.getHighqualityPlaylists(PlaylistBrowseRequest("全部", 12, "hot"))
            provider.getPersonalizedNewSongs(RecommendationsRequest(12))
            provider.getBanners(Bytes(byteArrayOf()))
            provider.getRankings(Bytes(byteArrayOf()))
            provider.getNewAlbums(AlbumBrowseRequest("ALL", 12))
            provider.getTopSongs(TopSongsRequest(12, 0))
            provider.getHotArtists(RecommendationsRequest(12))

            assertEquals(
                listOf(
                    ProviderProtocol.CAPABILITIES,
                    ProviderProtocol.SEARCH,
                    ProviderProtocol.GET,
                    ProviderProtocol.CAPABILITIES,
                    ProviderProtocol.GETMANY,
                    ProviderProtocol.RESOLVE,
                    ProviderProtocol.GETLYRICS,
                    ProviderProtocol.SEARCHPLAYLISTS,
                    ProviderProtocol.GETPLAYLIST,
                    ProviderProtocol.GETPLAYLISTTRACKS,
                    ProviderProtocol.GETUSERPLAYLISTS,
                    ProviderProtocol.SEARCHALBUMS,
                    ProviderProtocol.GETALBUM,
                    ProviderProtocol.SEARCHARTISTS,
                    ProviderProtocol.GETARTIST,
                    ProviderProtocol.GETARTISTTRACKS,
                    ProviderProtocol.GETARTISTALBUMS,
                    ProviderProtocol.GETRECOMMENDEDSONGS,
                    ProviderProtocol.GETRECOMMENDEDPLAYLISTS,
                    ProviderProtocol.GETPERSONALIZEDPLAYLISTS,
                    ProviderProtocol.GETTOPPLAYLISTS,
                    ProviderProtocol.GETHIGHQUALITYPLAYLISTS,
                    ProviderProtocol.GETPERSONALIZEDNEWSONGS,
                    ProviderProtocol.GETBANNERS,
                    ProviderProtocol.GETRANKINGS,
                    ProviderProtocol.GETNEWALBUMS,
                    ProviderProtocol.GETTOPSONGS,
                    ProviderProtocol.GETHOTARTISTS,
                ),
                session.operations,
            )

            provider.close()
            provider.close()
            assertEquals(1, session.closeCount)
            assertFailsWith<IllegalStateException> { runBlocking { provider.capabilities() } }
        }
    }

    private class RecordingSession : JniProviderSession {
        val operations = mutableListOf<Int>()
        var closeCount = 0

        override fun invoke(operation: Int, request: Any): Any {
            operations += operation
            return when (operation) {
                ProviderProtocol.CAPABILITIES -> Capabilities(listOf(Capability.BATCH))
                ProviderProtocol.SEARCH, ProviderProtocol.GETPLAYLISTTRACKS,
                ProviderProtocol.GETARTISTTRACKS, ProviderProtocol.GETRECOMMENDEDSONGS,
                ProviderProtocol.GETPERSONALIZEDNEWSONGS, ProviderProtocol.GETTOPSONGS -> TrackPage()
                ProviderProtocol.GET -> Track("track", "Track", durationMs = 1000)
                ProviderProtocol.GETMANY -> TrackBatch(listOf(TrackOutcome(
                    "track", track = Track("track", "Track", durationMs = 1000),
                )))
                ProviderProtocol.RESOLVE -> MediaResource("https://example.test/audio", "standard")
                ProviderProtocol.GETLYRICS -> Lyrics("track", lrc = "[00:00.00]line")
                ProviderProtocol.SEARCHPLAYLISTS, ProviderProtocol.GETUSERPLAYLISTS,
                ProviderProtocol.GETRECOMMENDEDPLAYLISTS, ProviderProtocol.GETPERSONALIZEDPLAYLISTS,
                ProviderProtocol.GETTOPPLAYLISTS, ProviderProtocol.GETHIGHQUALITYPLAYLISTS -> PlaylistPage()
                ProviderProtocol.GETBANNERS -> BannerPage()
                ProviderProtocol.GETRANKINGS -> RankingPage()
                ProviderProtocol.GETNEWALBUMS -> AlbumPage()
                ProviderProtocol.GETPLAYLIST -> Playlist("playlist", "Playlist")
                ProviderProtocol.SEARCHALBUMS, ProviderProtocol.GETARTISTALBUMS -> AlbumPage()
                ProviderProtocol.GETALBUM -> AlbumDetails(Album("album", "Album"))
                ProviderProtocol.SEARCHARTISTS, ProviderProtocol.GETHOTARTISTS -> ArtistPage()
                ProviderProtocol.GETARTIST -> CatalogArtist("artist", "Artist")
                else -> error("Unexpected JNI operation $operation")
            }
        }

        override fun cancel() = Unit
        override fun close() { closeCount++ }
    }
}
