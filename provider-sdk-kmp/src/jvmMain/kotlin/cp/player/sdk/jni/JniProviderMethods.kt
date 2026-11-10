package cp.player.sdk.jni

import cp.player.sdk.*

/** Typed SDK requests/results over a provider-owned JNI binding. */
abstract class JniProviderMethods : ExtendedJniProviderMethods() {
    override suspend fun capabilities(): Capabilities =
        dispatch(ProviderProtocol.CAPABILITIES, Bytes(byteArrayOf())) as Capabilities
    override suspend fun search(request: SearchRequest): TrackPage =
        dispatch(ProviderProtocol.SEARCH, request) as TrackPage
    override suspend fun get(request: IdRequest): Track =
        dispatch(ProviderProtocol.GET, request) as Track
    override suspend fun getMany(request: BatchRequest): TrackBatch =
        if (Capability.BATCH !in capabilities().supported) super.getMany(request)
        else dispatch(ProviderProtocol.GETMANY, request) as TrackBatch
    override suspend fun resolve(request: ResolveRequest): MediaResource =
        dispatch(ProviderProtocol.RESOLVE, request) as MediaResource
    override suspend fun getLyrics(request: IdRequest): Lyrics =
        dispatch(ProviderProtocol.GETLYRICS, request) as Lyrics
    override suspend fun searchPlaylists(request: SearchRequest): PlaylistPage =
        dispatch(ProviderProtocol.SEARCHPLAYLISTS, request) as PlaylistPage
    override suspend fun getPlaylist(request: IdRequest): Playlist =
        dispatch(ProviderProtocol.GETPLAYLIST, request) as Playlist
    override suspend fun getPlaylistTracks(request: PagedIdRequest): TrackPage =
        dispatch(ProviderProtocol.GETPLAYLISTTRACKS, request) as TrackPage
    override suspend fun getUserPlaylists(request: PagedIdRequest): PlaylistPage =
        dispatch(ProviderProtocol.GETUSERPLAYLISTS, request) as PlaylistPage
    override suspend fun searchAlbums(request: SearchRequest): AlbumPage =
        dispatch(ProviderProtocol.SEARCHALBUMS, request) as AlbumPage
    override suspend fun getAlbum(request: IdRequest): AlbumDetails =
        dispatch(ProviderProtocol.GETALBUM, request) as AlbumDetails
    override suspend fun searchArtists(request: SearchRequest): ArtistPage =
        dispatch(ProviderProtocol.SEARCHARTISTS, request) as ArtistPage
    override suspend fun getArtist(request: IdRequest): CatalogArtist =
        dispatch(ProviderProtocol.GETARTIST, request) as CatalogArtist
    override suspend fun getArtistTracks(request: PagedIdRequest): TrackPage =
        dispatch(ProviderProtocol.GETARTISTTRACKS, request) as TrackPage
    override suspend fun getArtistAlbums(request: PagedIdRequest): AlbumPage =
        dispatch(ProviderProtocol.GETARTISTALBUMS, request) as AlbumPage
    override suspend fun getRecommendedSongs(request: RecommendationsRequest): TrackPage =
        dispatch(ProviderProtocol.GETRECOMMENDEDSONGS, request) as TrackPage
    override suspend fun getRecommendedPlaylists(request: RecommendationsRequest): PlaylistPage =
        dispatch(ProviderProtocol.GETRECOMMENDEDPLAYLISTS, request) as PlaylistPage
    override suspend fun getPersonalizedPlaylists(request: RecommendationsRequest): PlaylistPage =
        dispatch(ProviderProtocol.GETPERSONALIZEDPLAYLISTS, request) as PlaylistPage
    override suspend fun getTopPlaylists(request: PlaylistBrowseRequest): PlaylistPage =
        dispatch(ProviderProtocol.GETTOPPLAYLISTS, request) as PlaylistPage
    override suspend fun getHighqualityPlaylists(request: PlaylistBrowseRequest): PlaylistPage =
        dispatch(ProviderProtocol.GETHIGHQUALITYPLAYLISTS, request) as PlaylistPage
    override suspend fun getPersonalizedNewSongs(request: RecommendationsRequest): TrackPage =
        dispatch(ProviderProtocol.GETPERSONALIZEDNEWSONGS, request) as TrackPage
    override suspend fun getBanners(request: Bytes): BannerPage =
        dispatch(ProviderProtocol.GETBANNERS, request) as BannerPage
    override suspend fun getRankings(request: Bytes): RankingPage =
        dispatch(ProviderProtocol.GETRANKINGS, request) as RankingPage
    override suspend fun getNewAlbums(request: AlbumBrowseRequest): AlbumPage =
        dispatch(ProviderProtocol.GETNEWALBUMS, request) as AlbumPage
    override suspend fun getTopSongs(request: TopSongsRequest): TrackPage =
        dispatch(ProviderProtocol.GETTOPSONGS, request) as TrackPage
    override suspend fun getHotArtists(request: RecommendationsRequest): ArtistPage =
        dispatch(ProviderProtocol.GETHOTARTISTS, request) as ArtistPage
    override suspend fun getUserProfile(request: UserRequest): UserProfile =
        dispatch(ProviderProtocol.GETUSERPROFILE, request) as UserProfile
    override suspend fun getUserRecords(request: UserRecordsRequest): TrackPage =
        dispatch(ProviderProtocol.GETUSERRECORDS, request) as TrackPage
    override suspend fun getUserFollows(request: UserListRequest): ArtistPage =
        dispatch(ProviderProtocol.GETUSERFOLLOWS, request) as ArtistPage
    override suspend fun getUserFolloweds(request: UserListRequest): ArtistPage =
        dispatch(ProviderProtocol.GETUSERFOLLOWEDS, request) as ArtistPage
    override suspend fun getUserCloud(request: UserListRequest): TrackPage =
        dispatch(ProviderProtocol.GETUSERCLOUD, request) as TrackPage
    override suspend fun getLikeList(request: UserRequest): IdList =
        dispatch(ProviderProtocol.GETLIKELIST, request) as IdList
    override suspend fun getUserAlbums(request: UserListRequest): AlbumPage =
        dispatch(ProviderProtocol.GETUSERALBUMS, request) as AlbumPage
    override suspend fun deletePlaylist(request: IdRequest): MutationResult =
        dispatch(ProviderProtocol.DELETEPLAYLIST, request) as MutationResult
    override suspend fun subscribePlaylist(request: PlaylistSubscribeRequest): MutationResult =
        dispatch(ProviderProtocol.SUBSCRIBEPLAYLIST, request) as MutationResult
    override suspend fun addTracksToPlaylist(request: PlaylistTracksRequest): MutationResult =
        dispatch(ProviderProtocol.ADDTRACKSTOPLAYLIST, request) as MutationResult
    override suspend fun removeTracksFromPlaylist(request: PlaylistTracksRequest): MutationResult =
        dispatch(ProviderProtocol.REMOVETRACKSFROMPLAYLIST, request) as MutationResult
    override suspend fun renamePlaylist(request: PlaylistNameRequest): MutationResult =
        dispatch(ProviderProtocol.RENAMEPLAYLIST, request) as MutationResult
    override suspend fun createPlaylist(request: CreatePlaylistRequest): MutationResult =
        dispatch(ProviderProtocol.CREATEPLAYLIST, request) as MutationResult
    override suspend fun likeSong(request: SongLikeRequest): MutationResult =
        dispatch(ProviderProtocol.LIKESONG, request) as MutationResult
    override suspend fun dislikeSong(request: IdRequest): MutationResult =
        dispatch(ProviderProtocol.DISLIKESONG, request) as MutationResult
    override suspend fun deleteUserCloud(request: BatchRequest): MutationResult =
        dispatch(ProviderProtocol.DELETEUSERCLOUD, request) as MutationResult
    override suspend fun getComments(request: CommentPageRequest): CommentPage =
        dispatch(ProviderProtocol.GETCOMMENTS, request) as CommentPage
    override suspend fun getFloorComments(request: CommentFloorRequest): CommentFloorPage =
        dispatch(ProviderProtocol.GETFLOORCOMMENTS, request) as CommentFloorPage
    override suspend fun postComment(request: PostCommentRequest): CreatedComment =
        dispatch(ProviderProtocol.POSTCOMMENT, request) as CreatedComment
    override suspend fun likeComment(request: CommentLikeRequest): MutationResult =
        dispatch(ProviderProtocol.LIKECOMMENT, request) as MutationResult
    override suspend fun getPersonalFm(request: Bytes): TrackPage =
        dispatch(ProviderProtocol.GETPERSONALFM, request) as TrackPage
    override suspend fun getSimilarSongs(request: SimilarSongsRequest): TrackPage =
        dispatch(ProviderProtocol.GETSIMILARSONGS, request) as TrackPage
    override suspend fun getIntelligenceSongs(request: IntelligenceSongsRequest): TrackPage =
        dispatch(ProviderProtocol.GETINTELLIGENCESONGS, request) as TrackPage
    override suspend fun subscribeAlbum(request: AlbumSubscribeRequest): MutationResult =
        dispatch(ProviderProtocol.SUBSCRIBEALBUM, request) as MutationResult
    override suspend fun getHotSearches(request: Bytes): KeywordPage =
        dispatch(ProviderProtocol.GETHOTSEARCHES, request) as KeywordPage
    override suspend fun getSearchSuggestions(request: SearchSuggestionRequest): SuggestionPage =
        dispatch(ProviderProtocol.GETSEARCHSUGGESTIONS, request) as SuggestionPage
    override suspend fun scrobble(request: ScrobbleRequest): MutationResult =
        dispatch(ProviderProtocol.SCROBBLE, request) as MutationResult
    override suspend fun getCurrentAccount(request: Bytes): AccountSession =
        dispatch(ProviderProtocol.GETCURRENTACCOUNT, request) as AccountSession
    override suspend fun authenticate(request: AuthActionRequest): AuthActionResult =
        dispatch(ProviderProtocol.AUTHENTICATE, request) as AuthActionResult
}
