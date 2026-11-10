package cp.player.sdk

/** Optional business surfaces. Declaring a capability opts a session into its method. */
interface LyricsProvider {
    /** Get lyrics with at least one primary format (LRC, TTML or YRC); report NotFound when no lyrics match. Capability: lyrics.get. Tag: 10. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getLyrics(request: IdRequest): Lyrics = unsupported()
}

interface Playlists {
    /** Search playlists using item-offset pagination. Capability: playlists.search. Tag: 11. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun searchPlaylists(request: SearchRequest): PlaylistPage = unsupported()
    /** Get playlist metadata; fetch its tracks separately with getPlaylistTracks. Capability: playlists.get. Tag: 12. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getPlaylist(request: IdRequest): Playlist = unsupported()
    /** Page tracks belonging to the playlist identified by request.id. Capability: playlists.tracks. Tag: 13. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getPlaylistTracks(request: PagedIdRequest): TrackPage = unsupported()
    /** Page playlists belonging to the provider-local user ID, not the host account key. Capability: playlists.user. Tag: 14. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserPlaylists(request: PagedIdRequest): PlaylistPage = unsupported()
}

interface Albums {
    /** Search albums using item-offset pagination. Capability: albums.search. Tag: 15. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun searchAlbums(request: SearchRequest): AlbumPage = unsupported()
    /** Get album metadata and complete contents, bounded to 500 tracks and the shared result byte limit. Capability: albums.get. Tag: 16. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getAlbum(request: IdRequest): AlbumDetails = unsupported()
}

interface Artists {
    /** Search artists using item-offset pagination. Capability: artists.search. Tag: 17. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun searchArtists(request: SearchRequest): ArtistPage = unsupported()
    /** Get the artist identified by request.id. Capability: artists.get. Tag: 18. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getArtist(request: IdRequest): CatalogArtist = unsupported()
    /** Page tracks belonging to the artist identified by request.id. Capability: artists.tracks. Tag: 19. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getArtistTracks(request: PagedIdRequest): TrackPage = unsupported()
    /** Page albums belonging to the artist identified by request.id. Capability: artists.albums. Tag: 20. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getArtistAlbums(request: PagedIdRequest): AlbumPage = unsupported()
}
