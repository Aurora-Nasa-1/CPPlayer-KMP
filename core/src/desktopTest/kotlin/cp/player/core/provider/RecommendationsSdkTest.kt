package cp.player.core.provider

import cp.player.core.provider.typed.ProviderCallContext
import cp.player.core.provider.typed.ProviderHost
import cp.player.core.provider.typed.ProviderSessionKey
import cp.player.sdk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecommendationsSdkTest {
    @Test
    fun `recommendations use typed host operations and enforce declared capabilities`() {
        runBlocking<Unit> {
            val provider = object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    Capability.RECOMMENDED_SONGS, Capability.RECOMMENDED_PLAYLISTS,
                    Capability.PERSONALIZED_PLAYLISTS, Capability.TOP_PLAYLISTS, Capability.HIGHQUALITY_PLAYLISTS,
                    Capability.RECOMMENDATION_NEW_SONGS, Capability.DISCOVERY_BANNERS, Capability.DISCOVERY_RANKINGS,
                    Capability.DISCOVERY_NEW_ALBUMS, Capability.DISCOVERY_TOP_SONGS, Capability.DISCOVERY_HOT_ARTISTS,
                ))
                override suspend fun getRecommendedSongs(request: RecommendationsRequest) = TrackPage(
                    items = listOf(Track("song-1", "Song", durationMs = 1200)),
                )
                override suspend fun getRecommendedPlaylists(request: RecommendationsRequest) = PlaylistPage(
                    items = listOf(Playlist("playlist-1", "Playlist")),
                )
                override suspend fun getPersonalizedPlaylists(request: RecommendationsRequest) =
                    PlaylistPage(items = listOf(Playlist("personalized-1", "Personalized")))
                override suspend fun getTopPlaylists(request: PlaylistBrowseRequest) =
                    PlaylistPage(items = listOf(Playlist("top-1", request.category)))
                override suspend fun getHighqualityPlaylists(request: PlaylistBrowseRequest) =
                    PlaylistPage(items = listOf(Playlist("quality-1", request.category)))
                override suspend fun getPersonalizedNewSongs(request: RecommendationsRequest) =
                    TrackPage(items = listOf(Track("new-song", "New Song", durationMs = 900)))
                override suspend fun getBanners(request: Bytes) = BannerPage()
                override suspend fun getRankings(request: Bytes) = RankingPage()
                override suspend fun getNewAlbums(request: AlbumBrowseRequest) = AlbumPage()
                override suspend fun getTopSongs(request: TopSongsRequest) = TrackPage()
                override suspend fun getHotArtists(request: RecommendationsRequest) = ArtistPage()
            }
            val host = ProviderHost(
                ProviderCallContext(ProviderSessionKey("recommendations", "account", 0)),
                provider,
            )
            try {
                assertEquals("song-1", host.getRecommendedSongs(RecommendationsRequest(10)).items.single().id)
                assertEquals("playlist-1", host.getRecommendedPlaylists(RecommendationsRequest(10)).items.single().id)
                assertEquals("personalized-1", host.getPersonalizedPlaylists(RecommendationsRequest(10)).items.single().id)
                assertEquals("top-1", host.getTopPlaylists(PlaylistBrowseRequest("Rock", 10, "hot")).items.single().id)
                assertEquals("quality-1", host.getHighqualityPlaylists(PlaylistBrowseRequest("Jazz", 10, "")).items.single().id)
                assertEquals("new-song", host.getPersonalizedNewSongs(RecommendationsRequest(10)).items.single().id)
                assertEquals(emptyList(), host.getBanners())
                assertEquals(emptyList(), host.getRankings())
                assertEquals(emptyList(), host.getNewAlbums(AlbumBrowseRequest("ALL", 10)).items)
                assertEquals(emptyList(), host.getTopSongs(TopSongsRequest(10, 0)).items)
                assertEquals(emptyList(), host.getHotArtists(RecommendationsRequest(10)).items)
                assertFailsWith<IllegalArgumentException> {
                    host.getRecommendedSongs(RecommendationsRequest(501))
                }
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `recommendation calls reject providers that do not declare the capability`() {
        runBlocking<Unit> {
            val host = ProviderHost(
                ProviderCallContext(ProviderSessionKey("recommendations", "account", 0)),
                object : MusicProvider {},
            )
            try {
                val failure = assertFailsWith<ProviderException> {
                    host.getRecommendedSongs(RecommendationsRequest(10))
                }
                assertEquals(FailureCategory.UNSUPPORTED, failure.failure.category)
            } finally {
                host.close()
            }
        }
    }
}
