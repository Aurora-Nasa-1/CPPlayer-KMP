package cp.player.sdk

import kotlinx.coroutines.CancellationException

const val MAX_CAPABILITIES = 256

/** One instance belongs to one immutable provider/account session. */
interface MusicProvider : LyricsProvider, Playlists, Albums, Artists, Recommendations, Discovery, Accounts, Authentication, PlaylistMutations, LibraryMutations, Comments, AlbumMutations, Scrobbling, ExtendedOperations {
    /** Runtime method capabilities, at most 256; fixed for this immutable account session. */
    suspend fun capabilities(): Capabilities = Capabilities(emptyList())
    /** Search audio tracks using item-offset pagination. Capability: tracks.search. Tag: 2. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun search(request: SearchRequest): TrackPage = unsupported()
    /** Get one track by its exact provider-local ID; return the same ID or NotFound. Capability: tracks.get. Tag: 3. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun get(request: IdRequest): Track = unsupported()
    /** Get one outcome per input ID, in order, including duplicates. The default calls get and preserves per-item failures. Capability: tracks.getMany. Tag: 4. The host also permits the default batch path when tracks.get is declared. */
    suspend fun getMany(request: BatchRequest): TrackBatch = TrackBatch(request.ids.map { id ->
        try { TrackOutcome(id, get(IdRequest(id))) }
        catch (e: CancellationException) { throw e }
        catch (e: ProviderException) { TrackOutcome(id, failure = e.failure) }
    })
    /** Resolve playable audio with the actual quality and explicit resource headers, not the account credential. Capability: tracks.resolve. Tag: 5. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun resolve(request: ResolveRequest): MediaResource = unsupported()
    /** Release owned resources after cancellation and draining; the host closes an instance once. */
    suspend fun close() {}
}

object Capability {
    const val SEARCH = "tracks.search"
    const val GET = "tracks.get"
    const val BATCH = "tracks.getMany"
    const val RESOLVE = "tracks.resolve"
    const val LYRICS = "lyrics.get"
    const val SEARCH_PLAYLISTS = "playlists.search"
    const val PLAYLIST = "playlists.get"
    const val PLAYLIST_TRACKS = "playlists.tracks"
    const val USER_PLAYLISTS = "playlists.user"
    const val SEARCH_ALBUMS = "albums.search"
    const val ALBUM = "albums.get"
    const val SEARCH_ARTISTS = "artists.search"
    const val ARTIST = "artists.get"
    const val ARTIST_TRACKS = "artists.tracks"
    const val ARTIST_ALBUMS = "artists.albums"
    const val RECOMMENDED_SONGS = "recommendations.songs"
    const val RECOMMENDED_PLAYLISTS = "recommendations.playlists"
    const val PERSONALIZED_PLAYLISTS = "recommendations.personalizedPlaylists"
    const val TOP_PLAYLISTS = "recommendations.topPlaylists"
    const val HIGHQUALITY_PLAYLISTS = "recommendations.highqualityPlaylists"
    const val RECOMMENDATION_NEW_SONGS = "recommendations.newSongs"
    const val DISCOVERY_BANNERS = "discovery.banners"
    const val DISCOVERY_RANKINGS = "discovery.rankings"
    const val DISCOVERY_NEW_ALBUMS = "discovery.newAlbums"
    const val DISCOVERY_TOP_SONGS = "discovery.topSongs"
    const val DISCOVERY_HOT_ARTISTS = "discovery.hotArtists"
    const val ACCOUNT_PROFILE = "accounts.profile"
    const val ACCOUNT_SESSION = "accounts.session"
    const val AUTH_ACTIONS = "auth.actions"
    const val ACCOUNT_RECORDS = "accounts.records"
    const val ACCOUNT_FOLLOWS = "accounts.follows"
    const val ACCOUNT_FOLLOWEDS = "accounts.followeds"
    const val ACCOUNT_CLOUD = "accounts.cloud"
    const val ACCOUNT_LIKES = "accounts.likes"
    const val ACCOUNT_ALBUMS = "accounts.albums"
    const val PLAYLIST_DELETE = "playlists.delete"
    const val PLAYLIST_SUBSCRIBE = "playlists.subscribe"
    const val PLAYLIST_ADD_TRACKS = "playlists.addTracks"
    const val PLAYLIST_REMOVE_TRACKS = "playlists.removeTracks"
    const val PLAYLIST_RENAME = "playlists.rename"
    const val PLAYLIST_CREATE = "playlists.create"
    const val LIBRARY_LIKE = "library.like"
    const val LIBRARY_DISLIKE = "library.dislike"
    const val LIBRARY_CLOUD_DELETE = "library.cloud.delete"
    const val COMMENTS_LIST = "comments.list"
    const val COMMENTS_FLOOR = "comments.floor"
    const val COMMENTS_POST = "comments.post"
    const val COMMENTS_LIKE = "comments.like"
    const val PERSONAL_FM = "recommendations.personalFm"
    const val SIMILAR_SONGS = "recommendations.similarSongs"
    const val INTELLIGENCE_SONGS = "recommendations.intelligenceSongs"
    const val ALBUM_SUBSCRIBE = "albums.subscribe"
    const val HOT_SEARCHES = "discovery.hotSearches"
    const val SEARCH_SUGGESTIONS = "discovery.searchSuggestions"
    const val PLAYBACK_SCROBBLE = "playback.scrobble"
}

interface Recommendations {
    /** Get track recommendations for the current account, bounded by request.limit. Capability: recommendations.songs. Tag: 21. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getRecommendedSongs(request: RecommendationsRequest): TrackPage = unsupported()
    /** Get playlist recommendations for the current account. Capability: recommendations.playlists. Tag: 22. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getRecommendedPlaylists(request: RecommendationsRequest): PlaylistPage = unsupported()
    /** Get personalized playlist recommendations; this capability is distinct from general recommendations. Capability: recommendations.personalizedPlaylists. Tag: 23. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getPersonalizedPlaylists(request: RecommendationsRequest): PlaylistPage = unsupported()
    /** Browse top playlists with provider-supported category and ordering selectors. Capability: recommendations.topPlaylists. Tag: 24. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getTopPlaylists(request: PlaylistBrowseRequest): PlaylistPage = unsupported()
    /** Browse high-quality playlists with the requested category. Capability: recommendations.highqualityPlaylists. Tag: 25. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getHighqualityPlaylists(request: PlaylistBrowseRequest): PlaylistPage = unsupported()
    /** Get recommended newly released tracks for the current account. Capability: recommendations.newSongs. Tag: 26. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getPersonalizedNewSongs(request: RecommendationsRequest): TrackPage = unsupported()
    /** Get a personal-radio selection for the current account. Capability: recommendations.personalFm. Tag: 52. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getPersonalFm(request: Bytes): TrackPage = unsupported()
    /** Get tracks related to request.id, bounded by request.limit. Capability: recommendations.similarSongs. Tag: 53. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getSimilarSongs(request: SimilarSongsRequest): TrackPage = unsupported()
    /** Get an intelligent continuation from a seed track and playlist. Capability: recommendations.intelligenceSongs. Tag: 54. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getIntelligenceSongs(request: IntelligenceSongsRequest): TrackPage = unsupported()
}

interface Discovery {
    /** Get discovery banners; the Bytes request is an empty no-argument marker. Capability: discovery.banners. Tag: 27. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getBanners(request: Bytes): BannerPage = unsupported()
    /** Get ranking-list metadata, not the contents of every ranking. Capability: discovery.rankings. Tag: 28. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getRankings(request: Bytes): RankingPage = unsupported()
    /** Get the bounded legacy discovery selection by area; use browseNewAlbums for paginated browsing. Capability: discovery.newAlbums. Tag: 29. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getNewAlbums(request: AlbumBrowseRequest): AlbumPage = unsupported()
    /** Get top tracks for region 0, 7, 8, 16 or 96; the provider documents its supported regional mapping. Capability: discovery.topSongs. Tag: 30. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getTopSongs(request: TopSongsRequest): TrackPage = unsupported()
    /** Get a bounded selection of popular artists. Capability: discovery.hotArtists. Tag: 31. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getHotArtists(request: RecommendationsRequest): ArtistPage = unsupported()
    /** Get hot-search keywords with optional descriptive text. Capability: discovery.hotSearches. Tag: 56. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getHotSearches(request: Bytes): KeywordPage = unsupported()
    /** Get plain search suggestions for the input keyword. Capability: discovery.searchSuggestions. Tag: 57. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getSearchSuggestions(request: SearchSuggestionRequest): SuggestionPage = unsupported()
}

interface Accounts {
    /** Inspect current-session login state without replacing the immutable session credential. Capability: accounts.session. Tag: 59. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getCurrentAccount(request: Bytes): AccountSession = unsupported()
    /** Get a public profile by provider-local user ID. Capability: accounts.profile. Tag: 32. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserProfile(request: UserRequest): UserProfile = unsupported()
    /** Get listening records for a user: type 0 selects all-time records and type 1 selects weekly records. Capability: accounts.records. Tag: 33. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserRecords(request: UserRecordsRequest): TrackPage = unsupported()
    /** Get followed profiles in the existing ArtistPage shape; this legacy shape is not a new social-user contract. Capability: accounts.follows. Tag: 34. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserFollows(request: UserListRequest): ArtistPage = unsupported()
    /** Get follower profiles in the existing ArtistPage shape; use provider-local user IDs. Capability: accounts.followeds. Tag: 35. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserFolloweds(request: UserListRequest): ArtistPage = unsupported()
    /** Page cloud-library tracks for the specified user within current-session permissions. Capability: accounts.cloud. Tag: 36. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserCloud(request: UserListRequest): TrackPage = unsupported()
    /** Get liked track IDs; the reserved request.id value self addresses the current account session. Capability: accounts.likes. Tag: 37. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getLikeList(request: UserRequest): IdList = unsupported()
    /** Page albums collected by the specified user. Capability: accounts.albums. Tag: 38. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getUserAlbums(request: UserListRequest): AlbumPage = unsupported()
}

interface Authentication {
    /** Perform a named authentication action. The host owns credential persistence and subsequent session replacement. Capability: auth.actions. Tag: 60. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun authenticate(request: AuthActionRequest): AuthActionResult = unsupported()
}

object AuthAction {
    const val QR_KEY = "qr.key"
    const val QR_CREATE = "qr.create"
    const val QR_CHECK = "qr.check"
    const val LOGIN_EMAIL = "login.email"
    const val LOGIN_PHONE = "login.phone"
    const val CAPTCHA_SEND = "captcha.send"
    const val CAPTCHA_IMAGE = "captcha.image"
    const val WEBVIEW_URL = "webview.url"
    const val WEBVIEW_CHECK = "webview.check"
    const val VERIFY_URL = "verify.url"
    const val VERIFY_CHECK = "verify.check"
    const val LOGIN_ANONYMOUS = "login.anonymous"
    const val LOGOUT = "logout"
}

interface PlaylistMutations {
    /** Delete a playlist. The application must obtain confirmation before invoking this destructive operation. Capability: playlists.delete. Tag: 39. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun deletePlaylist(request: IdRequest): MutationResult = unsupported()
    /** Set playlist subscription state explicitly; this is not a toggle. Capability: playlists.subscribe. Tag: 40. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun subscribePlaylist(request: PlaylistSubscribeRequest): MutationResult = unsupported()
    /** Add the supplied provider-local track IDs to the playlist. Capability: playlists.addTracks. Tag: 41. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun addTracksToPlaylist(request: PlaylistTracksRequest): MutationResult = unsupported()
    /** Remove the supplied provider-local track IDs from the playlist. Capability: playlists.removeTracks. Tag: 42. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun removeTracksFromPlaylist(request: PlaylistTracksRequest): MutationResult = unsupported()
    /** Rename a playlist without changing its identity. Capability: playlists.rename. Tag: 43. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun renamePlaylist(request: PlaylistNameRequest): MutationResult = unsupported()
    /** Create a playlist with the requested name and provider-supported privacy selector. Capability: playlists.create. Tag: 44. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun createPlaylist(request: CreatePlaylistRequest): MutationResult = unsupported()
}

interface LibraryMutations {
    /** Set the current account's liked state for a track. Capability: library.like. Tag: 45. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun likeSong(request: SongLikeRequest): MutationResult = unsupported()
    /** Mark a track as unwanted using the provider's dislike behavior, not as a synonym for unlike. Capability: library.dislike. Tag: 46. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun dislikeSong(request: IdRequest): MutationResult = unsupported()
    /** Delete cloud-library items identified by the input IDs. Capability: library.cloud.delete. Tag: 47. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun deleteUserCloud(request: BatchRequest): MutationResult = unsupported()
}

interface Comments {
    /** Get an offset-based comment page. The request cannot carry a cursor; use getCommentPage for cursor pagination. Capability: comments.list. Tag: 48. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getComments(request: CommentPageRequest): CommentPage = unsupported()
    /** Get replies under a top-level comment; return nextTime for the next request without unit conversion. Capability: comments.floor. Tag: 49. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun getFloorComments(request: CommentFloorRequest): CommentFloorPage = unsupported()
    /** Create a top-level comment or a reply. A missing returned comment does not negate a successful creation. Capability: comments.post. Tag: 50. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun postComment(request: PostCommentRequest): CreatedComment = unsupported()
    /** Set a comment's liked state explicitly. Capability: comments.like. Tag: 51. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun likeComment(request: CommentLikeRequest): MutationResult = unsupported()
}

interface AlbumMutations {
    /** Set album subscription state explicitly. Capability: albums.subscribe. Tag: 55. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun subscribeAlbum(request: AlbumSubscribeRequest): MutationResult = unsupported()
}

interface Scrobbling {
    /** Report playback; playedSeconds is seconds, unlike the SDK's millisecond duration fields. Capability: playback.scrobble. Tag: 58. Undeclared or unimplemented capabilities fail with Unsupported. */
    suspend fun scrobble(request: ScrobbleRequest): MutationResult = unsupported()
}

object FailureCategory {
    const val UNSUPPORTED = "Unsupported"
    const val NOT_FOUND = "NotFound"
    const val AUTHENTICATION = "Authentication"
    const val NETWORK = "Network"
    const val RATE_LIMIT = "RateLimit"
    const val TIMEOUT = "Timeout"
    const val INVALID_RESPONSE = "InvalidResponse"
    const val RUNTIME = "Runtime"
}

class ProviderException(val failure: Failure) : Exception(failure.category)
fun unsupported(): Nothing = throw ProviderException(Failure(FailureCategory.UNSUPPORTED, "Capability not supported"))

/** Scoped capabilities, not a reference to the application or its database. */
interface ProviderRuntime {
    /** Allowed-origin HTTP with explicit credentials, no redirects and at most 4 MiB per body. */
    suspend fun http(request: HttpRequest): HttpResponse
    /** SHA-256 digest; no implicit text encoding or additional cryptographic algorithms. */
    suspend fun sha256(bytes: ByteArray): ByteArray
    /** Copy of session-local bytes, or null; this store does not survive session replacement. */
    suspend fun read(key: String): ByteArray?
    /** Store a copy, or delete with null. Per-value and total storage are bounded to 1 MiB. */
    suspend fun write(key: String, value: ByteArray?)
}

/** Do not retain a context across account changes; the host creates a new instance. */
class ProviderContext(
    /** Stable provider identity, separate from catalog resource IDs. */
    val providerId: String,
    /** Host account-slot key, not the upstream user ID. */
    val accountId: String,
    settings: Map<String, String>,
    /** Credential snapshot; never log or implicitly copy into media-resource headers. */
    val credential: String?,
    /** Scoped HTTP, SHA-256 and temporary storage. No host filesystem or UI access. */
    val runtime: ProviderRuntime,
) {
    /** Immutable saved/default settings snapshot. */
    val settings = settings.toMap()
    override fun toString() = "ProviderContext($providerId)"
}
