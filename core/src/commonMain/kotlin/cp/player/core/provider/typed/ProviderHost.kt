package cp.player.core.provider.typed

import cp.player.sdk.*
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** A session identity, never a credential. A new revision requires a new host. */
data class ProviderSessionKey(val providerId: String, val accountId: String, val revision: Long) {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{0,127}").matches(providerId) && ".." !in providerId && providerId != "local")
        require(accountId.isNotBlank() && accountId.length <= 256)
        require(revision >= 0)
    }
}

/** Deliberately not a data class: toString must not expose credentials/settings. */
class ProviderCallContext(
    val session: ProviderSessionKey,
    settings: Map<String, String> = emptyMap(),
    val credential: String? = null,
    val credentialVersion: Long = 0,
) {
    val settings: Map<String, String> = settings.toMap()
    override fun toString(): String = "ProviderCallContext(${session.providerId}, revision=${session.revision})"
}

/**
 * Owns one adapter and one immutable provider/account session. Calls are tasks
 * under the session job; cancellation of an awaiting caller cancels its task.
 * invalidate() fences publication synchronously; close() drains before disposal.
 */
class ProviderHost(
    val context: ProviderCallContext,
    private val adapter: MusicProvider,
    private val isCurrent: () -> Boolean = { true },
    private val timeoutMs: Long = 15_000,
    maxConcurrency: Int = 4,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + sessionJob)
    private val permits: Semaphore
    private val closeMutex = Mutex()
    private var disposed = false
    val isActive: Boolean get() = sessionJob.isActive
    init { require(timeoutMs > 0); require(maxConcurrency in 1..16); permits = Semaphore(maxConcurrency) }

    fun invalidate() { sessionJob.cancel(CancellationException("Provider session retired")) }
    private fun fence() {
        sessionJob.ensureActive()
        if (!isCurrent()) throw CancellationException("Provider session changed")
    }
    private suspend fun <T> call(block: suspend () -> T): T {
        fence()
        val task = scope.async {
            try {
                withTimeout(timeoutMs) {
                    permits.withPermit { fence(); block().also { fence() } }
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw ProviderException(Failure(FailureCategory.TIMEOUT, "Provider deadline exceeded"))
            } catch (e: CancellationException) { throw e }
            catch (e: ProviderException) {
                if (e.failure.category.isBlank() || e.failure.category.length > 128 || e.failure.message.length > 4096)
                    throw ProviderException(Failure(FailureCategory.INVALID_RESPONSE, "Invalid provider failure"))
                throw e
            } catch (e: Exception) {
                throw ProviderException(Failure(FailureCategory.INVALID_RESPONSE, "Provider returned an invalid result: ${e.message.orEmpty()}"))
            }
        }
        return try { task.await().also { fence() } }
        finally { withContext(NonCancellable) { task.cancelAndJoin() } }
    }
    suspend fun capabilities(): Capabilities = call {
        adapter.capabilities().also {
            require(it.supported.size <= MAX_CAPABILITIES && it.supported.all { c -> c.isNotBlank() && c.length <= 128 })
        }
    }
    private suspend fun requireCapability(capability: String) {
        val supported = adapter.capabilities().supported
        require(supported.size <= MAX_CAPABILITIES && supported.all { it.isNotBlank() && it.length <= 128 })
        if (capability !in supported && !(capability == Capability.BATCH && Capability.GET in supported)) unsupported()
    }
    internal suspend fun <T> callExtended(capability: String, action: suspend (MusicProvider) -> T): T = call {
        requireCapability(capability)
        action(adapter)
    }
    suspend fun search(request: SearchRequest): TrackPage {
        require(request.keyword.isNotBlank() && request.keyword.length <= 4096)
        require(request.limit in 1..MAX_ITEMS && request.offset >= 0)
        return call {
            requireCapability(Capability.SEARCH)
            adapter.search(request).also { page ->
                require(page.items.size <= request.limit)
                require(page.total == null || page.total in 0..MAX_SAFE_INTEGER)
                page.items.forEach(::validateTrack)
                // Bound the complete result, not just each string.
                page.write(WireWriter())
            }
        }
    }
    suspend fun get(id: String): Track {
        validateId(id)
        return call {
            requireCapability(Capability.GET)
            adapter.get(IdRequest(id)).also { validateTrack(it); require(it.id == id) }
        }
    }
    suspend fun getMany(ids: List<String>): TrackBatch {
        require(ids.size in 1..MAX_ITEMS)
        val snapshot = ids.toList()
        snapshot.forEach(::validateId)
        return call {
            requireCapability(Capability.BATCH)
            adapter.getMany(BatchRequest(snapshot)).also { batch ->
                require(batch.items.size == snapshot.size)
                batch.items.zip(snapshot).forEach { (item, id) ->
                    require(item.id == id && (item.track != null) != (item.failure != null))
                    item.track?.let { validateTrack(it); require(it.id == id) }
                    item.failure?.let { require(it.category.isNotBlank() && it.category.length <= 128 && it.message.length <= 4096) }
                }
                batch.write(WireWriter())
            }
        }
    }
    suspend fun resolve(request: ResolveRequest): MediaResource {
        validateId(request.id)
        require(request.quality.isNotBlank() && request.quality.length <= 128)
        return call {
            requireCapability(Capability.RESOLVE)
            adapter.resolve(request).also {
                validateHttpUrl(it.url)
                require(it.actualQuality.isNotBlank() && it.actualQuality.length <= 128)
                require(it.headers.size <= 64)
                it.headers.forEach { h ->
                    require(h.name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))
                    require(h.value.length <= 8192 && h.value.none { c -> c == '\r' || c == '\n' || c == '\u0000' })
                }
                require(it.headers.map { h -> h.name.lowercase() }.distinct().size == it.headers.size)
                require(it.expiresAtEpochMs == null || it.expiresAtEpochMs in 0..MAX_SAFE_INTEGER)
                require(it.sizeBytes == null || it.sizeBytes in 0..MAX_SAFE_INTEGER)
                require(it.bitrate?.let { b -> b > 0 } != false)
                it.write(WireWriter())
            }
        }
    }
    suspend fun getLyrics(id: String): Lyrics {
        validateId(id)
        return call {
            requireCapability(Capability.LYRICS)
            adapter.getLyrics(IdRequest(id)).also {
                require(it.id == id)
                require(listOf(it.lrc, it.ttml, it.yrc).any { text -> !text.isNullOrBlank() })
                it.write(WireWriter())
            }
        }
    }

    suspend fun searchPlaylists(request: SearchRequest): PlaylistPage {
        validateSearch(request)
        return call {
            requireCapability(Capability.SEARCH_PLAYLISTS)
            adapter.searchPlaylists(request).also {
                validatePage(it.items, request.limit, it.total, ::validatePlaylist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getPlaylist(id: String): Playlist {
        validateId(id)
        return call {
            requireCapability(Capability.PLAYLIST)
            adapter.getPlaylist(IdRequest(id)).also {
                validatePlaylist(it); require(it.id == id); it.write(WireWriter())
            }
        }
    }

    suspend fun getPlaylistTracks(request: PagedIdRequest): TrackPage {
        validatePagedId(request)
        return call {
            requireCapability(Capability.PLAYLIST_TRACKS)
            adapter.getPlaylistTracks(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getUserPlaylists(request: PagedIdRequest): PlaylistPage {
        validatePagedId(request)
        return call {
            requireCapability(Capability.USER_PLAYLISTS)
            adapter.getUserPlaylists(request).also {
                validatePage(it.items, request.limit, it.total, ::validatePlaylist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun searchAlbums(request: SearchRequest): AlbumPage {
        validateSearch(request)
        return call {
            requireCapability(Capability.SEARCH_ALBUMS)
            adapter.searchAlbums(request).also {
                validatePage(it.items, request.limit, it.total, ::validateAlbum)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getAlbum(id: String): AlbumDetails {
        validateId(id)
        return call {
            requireCapability(Capability.ALBUM)
            adapter.getAlbum(IdRequest(id)).also {
                validateAlbum(it.album); require(it.album.id == id)
                require(it.tracks.size <= MAX_ITEMS)
                it.tracks.forEach(::validateTrack)
                require(it.album.trackCount == null || it.album.trackCount == it.tracks.size)
                it.write(WireWriter())
            }
        }
    }

    suspend fun searchArtists(request: SearchRequest): ArtistPage {
        validateSearch(request)
        return call {
            requireCapability(Capability.SEARCH_ARTISTS)
            adapter.searchArtists(request).also {
                validatePage(it.items, request.limit, it.total, ::validateCatalogArtist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getArtist(id: String): CatalogArtist {
        validateId(id)
        return call {
            requireCapability(Capability.ARTIST)
            adapter.getArtist(IdRequest(id)).also {
                validateCatalogArtist(it); require(it.id == id); it.write(WireWriter())
            }
        }
    }

    suspend fun getArtistTracks(request: PagedIdRequest): TrackPage {
        validatePagedId(request)
        return call {
            requireCapability(Capability.ARTIST_TRACKS)
            adapter.getArtistTracks(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getArtistAlbums(request: PagedIdRequest): AlbumPage {
        validatePagedId(request)
        return call {
            requireCapability(Capability.ARTIST_ALBUMS)
            adapter.getArtistAlbums(request).also {
                validatePage(it.items, request.limit, it.total, ::validateAlbum)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getRecommendedSongs(request: RecommendationsRequest): TrackPage {
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.RECOMMENDED_SONGS)
            adapter.getRecommendedSongs(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getRecommendedPlaylists(request: RecommendationsRequest): PlaylistPage {
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.RECOMMENDED_PLAYLISTS)
            adapter.getRecommendedPlaylists(request).also {
                validatePage(it.items, request.limit, it.total, ::validatePlaylist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getPersonalizedPlaylists(request: RecommendationsRequest): PlaylistPage {
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.PERSONALIZED_PLAYLISTS)
            adapter.getPersonalizedPlaylists(request).also {
                validatePage(it.items, request.limit, it.total, ::validatePlaylist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getTopPlaylists(request: PlaylistBrowseRequest): PlaylistPage {
        validatePlaylistBrowse(request)
        return call {
            requireCapability(Capability.TOP_PLAYLISTS)
            adapter.getTopPlaylists(request).also {
                validatePage(it.items, request.limit, it.total, ::validatePlaylist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getHighqualityPlaylists(request: PlaylistBrowseRequest): PlaylistPage {
        validatePlaylistBrowse(request)
        return call {
            requireCapability(Capability.HIGHQUALITY_PLAYLISTS)
            adapter.getHighqualityPlaylists(request).also {
                validatePage(it.items, request.limit, it.total, ::validatePlaylist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getPersonalizedNewSongs(request: RecommendationsRequest): TrackPage {
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.RECOMMENDATION_NEW_SONGS)
            adapter.getPersonalizedNewSongs(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getBanners(): List<Banner> = call {
        requireCapability(Capability.DISCOVERY_BANNERS)
        adapter.getBanners(Bytes(byteArrayOf())).also { page ->
            require(page.items.size <= MAX_ITEMS)
            page.items.forEach {
                require(it.id.isNotBlank() && it.id.length <= 1024)
                require(it.title.length <= 4096)
                validateHttpUrl(it.imageUrl)
                validateId(it.targetId)
            }
            page.write(WireWriter())
        }.items
    }

    suspend fun getRankings(): List<Ranking> = call {
        requireCapability(Capability.DISCOVERY_RANKINGS)
        adapter.getRankings(Bytes(byteArrayOf())).also { page ->
            require(page.items.size <= MAX_ITEMS)
            page.items.forEach {
                validateId(it.id); validateTitle(it.title)
                it.coverUrl?.let(::validateHttpUrl)
                require(it.trackCount >= 0 && (it.updateFrequency?.length ?: 0) <= 256)
            }
            page.write(WireWriter())
        }.items
    }

    suspend fun getHotSearches(): List<Keyword> = call {
        requireCapability(Capability.HOT_SEARCHES)
        adapter.getHotSearches(Bytes(byteArrayOf())).also { page ->
            require(page.items.size <= MAX_ITEMS)
            page.items.forEach {
                require(it.keyword.isNotBlank() && it.keyword.length <= 512 && it.description.length <= 4096)
            }
            page.write(WireWriter())
        }.items
    }

    suspend fun getSearchSuggestions(request: SearchSuggestionRequest): SuggestionPage {
        require(request.keyword.isNotBlank() && request.keyword.length <= 4096)
        require(request.limit in 1..50)
        return call {
            requireCapability(Capability.SEARCH_SUGGESTIONS)
            adapter.getSearchSuggestions(request).also {
                require(it.items.size <= request.limit)
                it.items.forEach { item -> require(item.isNotBlank() && item.length <= 512) }
                it.write(WireWriter())
            }
        }
    }

    suspend fun getNewAlbums(request: AlbumBrowseRequest): AlbumPage {
        require(request.area.length in 1..32 && request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.DISCOVERY_NEW_ALBUMS)
            adapter.getNewAlbums(request).also {
                validatePage(it.items, request.limit, it.total, ::validateAlbum)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getTopSongs(request: TopSongsRequest): TrackPage {
        require(request.region in setOf(0, 7, 8, 16, 96) && request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.DISCOVERY_TOP_SONGS)
            adapter.getTopSongs(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getHotArtists(request: RecommendationsRequest): ArtistPage {
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.DISCOVERY_HOT_ARTISTS)
            adapter.getHotArtists(request).also {
                validatePage(it.items, request.limit, it.total, ::validateCatalogArtist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getPersonalFm(): TrackPage = call {
        requireCapability(Capability.PERSONAL_FM)
        adapter.getPersonalFm(Bytes(byteArrayOf())).also {
            validatePage(it.items, MAX_ITEMS, it.total, ::validateTrack)
            it.write(WireWriter())
        }
    }

    suspend fun getSimilarSongs(request: SimilarSongsRequest): TrackPage {
        validateId(request.id)
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.SIMILAR_SONGS)
            adapter.getSimilarSongs(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getIntelligenceSongs(request: IntelligenceSongsRequest): TrackPage {
        validateId(request.id)
        validateId(request.playlistId)
        require(request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.INTELLIGENCE_SONGS)
            adapter.getIntelligenceSongs(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getUserProfile(request: UserRequest): UserProfile {
        validateId(request.id)
        return call {
            requireCapability(Capability.ACCOUNT_PROFILE)
            adapter.getUserProfile(request).also {
                require(it.id == request.id && it.nickname.isNotBlank() && it.nickname.length <= 4096)
                it.avatarUrl?.let(::validateHttpUrl)
                require((it.signature?.length ?: 0) <= 65536)
                require(it.playlistCount >= 0 && it.follows >= 0 && it.followeds >= 0)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getCurrentAccount(): AccountSession = call {
        requireCapability(Capability.ACCOUNT_SESSION)
        adapter.getCurrentAccount(Bytes(byteArrayOf())).also {
            if (it.loggedIn) {
                val userId = it.userId
                require(!userId.isNullOrBlank())
                validateId(userId)
            }
            require((it.nickname?.length ?: 0) <= 4096)
            it.avatarUrl?.let(::validateHttpUrl)
            it.write(WireWriter())
        }
    }

    suspend fun authenticate(request: AuthActionRequest): AuthActionResult {
        require(request.action in setOf(
            AuthAction.QR_KEY, AuthAction.QR_CREATE, AuthAction.QR_CHECK,
            AuthAction.LOGIN_EMAIL, AuthAction.LOGIN_PHONE, AuthAction.CAPTCHA_SEND,
            AuthAction.CAPTCHA_IMAGE, AuthAction.WEBVIEW_URL, AuthAction.WEBVIEW_CHECK,
            AuthAction.VERIFY_URL, AuthAction.VERIFY_CHECK, AuthAction.LOGIN_ANONYMOUS,
            AuthAction.LOGOUT,
        ))
        require(listOf(
            request.email, request.phone, request.password, request.captcha,
            request.imageCaptcha, request.challengeCookie, request.key, request.sessionId,
            request.verifyId, request.extraJson, request.countryCode,
        ).all { (it?.length ?: 0) <= 16_384 })
        request.countryCode?.let { require(Regex("[0-9]{1,4}").matches(it)) }
        return call {
            requireCapability(Capability.AUTH_ACTIONS)
            adapter.authenticate(request).also {
                require((it.key?.length ?: 0) <= 4096)
                require((it.url?.length ?: 0) <= 16_384)
                require((it.imageBase64?.length ?: 0) <= 4_194_304)
                require((it.cookie?.length ?: 0) <= 16_384)
                require((it.verifyUrl?.length ?: 0) <= 16_384)
                require((it.message?.length ?: 0) <= 4096)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getUserRecords(request: UserRecordsRequest): TrackPage {
        validateId(request.id)
        require(request.type in 0..1 && request.limit in 1..MAX_ITEMS)
        return call {
            requireCapability(Capability.ACCOUNT_RECORDS)
            adapter.getUserRecords(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getUserFollows(request: UserListRequest, followers: Boolean): ArtistPage {
        validateUserList(request)
        return call {
            requireCapability(if (followers) Capability.ACCOUNT_FOLLOWEDS else Capability.ACCOUNT_FOLLOWS)
            (if (followers) adapter.getUserFolloweds(request) else adapter.getUserFollows(request)).also {
                validatePage(it.items, request.limit, it.total, ::validateCatalogArtist)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getUserCloud(request: UserListRequest): TrackPage {
        validateUserList(request)
        return call {
            requireCapability(Capability.ACCOUNT_CLOUD)
            adapter.getUserCloud(request).also {
                validatePage(it.items, request.limit, it.total, ::validateTrack)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getLikeList(request: UserRequest): IdList {
        validateId(request.id)
        return call {
            requireCapability(Capability.ACCOUNT_LIKES)
            adapter.getLikeList(request).also {
                require(it.items.size <= MAX_ITEMS)
                it.items.forEach(::validateId)
                it.write(WireWriter())
            }
        }
    }

    suspend fun getUserAlbums(request: UserListRequest): AlbumPage {
        validateUserList(request)
        return call {
            requireCapability(Capability.ACCOUNT_ALBUMS)
            adapter.getUserAlbums(request).also {
                validatePage(it.items, request.limit, it.total, ::validateAlbum)
                it.write(WireWriter())
            }
        }
    }

    suspend fun deletePlaylist(request: IdRequest): MutationResult {
        validateId(request.id)
        return mutation(Capability.PLAYLIST_DELETE) { adapter.deletePlaylist(request) }
    }

    suspend fun subscribePlaylist(request: PlaylistSubscribeRequest): MutationResult {
        validateId(request.playlistId)
        return mutation(Capability.PLAYLIST_SUBSCRIBE) { adapter.subscribePlaylist(request) }
    }

    suspend fun addTracksToPlaylist(request: PlaylistTracksRequest): MutationResult {
        validateTracksMutation(request)
        return mutation(Capability.PLAYLIST_ADD_TRACKS) { adapter.addTracksToPlaylist(request) }
    }

    suspend fun removeTracksFromPlaylist(request: PlaylistTracksRequest): MutationResult {
        validateTracksMutation(request)
        return mutation(Capability.PLAYLIST_REMOVE_TRACKS) { adapter.removeTracksFromPlaylist(request) }
    }

    suspend fun renamePlaylist(request: PlaylistNameRequest): MutationResult {
        validateId(request.playlistId)
        require(request.name.isNotBlank() && request.name.length <= 256)
        return mutation(Capability.PLAYLIST_RENAME) { adapter.renamePlaylist(request) }
    }

    suspend fun createPlaylist(request: CreatePlaylistRequest): MutationResult {
        require(request.name.isNotBlank() && request.name.length <= 256 && request.privacy in 0..1)
        return mutation(Capability.PLAYLIST_CREATE) { adapter.createPlaylist(request) }
    }

    suspend fun subscribeAlbum(request: AlbumSubscribeRequest): MutationResult {
        validateId(request.id)
        return mutation(Capability.ALBUM_SUBSCRIBE) { adapter.subscribeAlbum(request) }
    }

    suspend fun likeSong(request: SongLikeRequest): MutationResult {
        validateId(request.id)
        return mutation(Capability.LIBRARY_LIKE) { adapter.likeSong(request) }
    }

    suspend fun dislikeSong(request: IdRequest): MutationResult {
        validateId(request.id)
        return mutation(Capability.LIBRARY_DISLIKE) { adapter.dislikeSong(request) }
    }

    suspend fun deleteUserCloud(request: BatchRequest): MutationResult {
        require(request.ids.size in 1..MAX_ITEMS)
        request.ids.forEach(::validateId)
        return mutation(Capability.LIBRARY_CLOUD_DELETE) { adapter.deleteUserCloud(request) }
    }

    private suspend fun mutation(capability: String, action: suspend () -> MutationResult): MutationResult = call {
        requireCapability(capability)
        action().also {
            it.id?.let(::validateId)
            it.write(WireWriter())
        }
    }

    suspend fun getComments(request: CommentPageRequest): CommentPage {
        validateCommentRequest(request.id, request.type, request.limit)
        require(request.offset >= 0 && request.sortType in 1..3)
        return call {
            requireCapability(Capability.COMMENTS_LIST)
            adapter.getComments(request).also {
                require(it.comments.size <= request.limit && it.totalCount in 0..MAX_SAFE_INTEGER)
                require(it.cursor.length <= 4096 && it.sortType in 1..3)
                it.comments.forEach { comment -> validateComment(comment, 0) }
                it.write(WireWriter())
            }
        }
    }

    suspend fun getFloorComments(request: CommentFloorRequest): CommentFloorPage {
        validateCommentRequest(request.id, request.type, request.limit)
        require(request.parentCommentId in 1..MAX_SAFE_INTEGER && request.time in 0..MAX_SAFE_INTEGER)
        return call {
            requireCapability(Capability.COMMENTS_FLOOR)
            adapter.getFloorComments(request).also {
                require(it.replies.size <= request.limit && it.totalCount >= 0 && it.nextTime in 0..MAX_SAFE_INTEGER)
                it.replies.forEach { comment -> validateComment(comment, 0) }
                it.write(WireWriter())
            }
        }
    }

    suspend fun postComment(request: PostCommentRequest): CreatedComment {
        validateCommentRequest(request.id, request.type, 1)
        require(request.content.isNotBlank() && request.content.length <= 4096)
        request.replyToCommentId?.let { require(it in 1..MAX_SAFE_INTEGER) }
        return call {
            requireCapability(Capability.COMMENTS_POST)
            adapter.postComment(request).also {
                it.comment?.let { comment -> validateComment(comment, 0) }
                it.write(WireWriter())
            }
        }
    }

    suspend fun likeComment(request: CommentLikeRequest): MutationResult {
        validateCommentRequest(request.id, request.type, 1)
        require(request.commentId in 1..MAX_SAFE_INTEGER)
        return mutation(Capability.COMMENTS_LIKE) { adapter.likeComment(request) }
    }

    suspend fun scrobble(request: ScrobbleRequest): MutationResult {
        validateId(request.id)
        require(request.sourceId.length <= 1024 && request.playedSeconds > 0)
        return mutation(Capability.PLAYBACK_SCROBBLE) { adapter.scrobble(request) }
    }

    private fun validateComment(comment: CommentItem, depth: Int) {
        require(depth <= 2 && comment.id in 1..MAX_SAFE_INTEGER && comment.content.length <= 16384)
        require(comment.user.length <= 4096 && comment.avatar.length <= 16384 && comment.time.length <= 256)
        require(comment.likedCount >= 0 && comment.replyCount >= 0 && comment.userId in 0..MAX_SAFE_INTEGER)
        require(comment.timeMs in 0..MAX_SAFE_INTEGER && comment.parentCommentId in 0..MAX_SAFE_INTEGER && comment.ipLocation.length <= 256)
        if (comment.avatar.isNotBlank()) validateHttpUrl(comment.avatar)
        require(comment.beReplied.size <= 64 && comment.topReplies.size <= 20)
        comment.beReplied.forEach {
            require(it.userId in 0..MAX_SAFE_INTEGER && it.nickname.length <= 4096 && it.content.length <= 16384)
        }
        comment.topReplies.forEach { validateComment(it, depth + 1) }
    }

    suspend fun close() = withContext(NonCancellable) {
        invalidate()
        sessionJob.join()
        closeMutex.withLock {
            if (!disposed) {
                // Set before disposal: even a throwing adapter is never released twice.
                disposed = true
                adapter.close()
            }
        }
    }
}

private fun validateSearch(request: SearchRequest) {
    require(request.keyword.isNotBlank() && request.keyword.length <= 4096)
    require(request.limit in 1..MAX_ITEMS && request.offset >= 0)
}
private fun validatePlaylistBrowse(request: PlaylistBrowseRequest) {
    require(request.category.length <= 256 && request.order.length <= 64)
    require(request.limit in 1..MAX_ITEMS)
}
private fun validatePagedId(request: PagedIdRequest) {
    validateId(request.id)
    require(request.limit in 1..MAX_ITEMS && request.offset >= 0)
}
private fun validateUserList(request: UserListRequest) {
    validateId(request.id)
    require(request.limit in 1..MAX_ITEMS && request.offset >= 0)
}
private fun validateTracksMutation(request: PlaylistTracksRequest) {
    validateId(request.playlistId)
    require(request.trackIds.size in 1..MAX_ITEMS)
    request.trackIds.forEach(::validateId)
}
private fun validateCommentRequest(id: String, type: String, limit: Int) {
    validateId(id)
    require(type.isNotBlank() && type.length <= 32 && limit in 1..MAX_ITEMS)
}
private fun <T> validatePage(items: List<T>, limit: Int, total: Long?, validate: (T) -> Unit) {
    require(items.size <= limit)
    require(total == null || total in items.size.toLong()..MAX_SAFE_INTEGER)
    items.forEach(validate)
}
private fun validateTitle(value: String) { require(value.isNotBlank() && value.length <= 4096) }
private fun validateCount(value: Int?) { require(value == null || value >= 0) }
private fun validateArtists(artists: List<Artist>) {
    require(artists.size <= 64)
    artists.forEach { validateTitle(it.name); it.id?.let(::validateId) }
}
private fun validatePlaylist(value: Playlist) {
    validateId(value.id); validateTitle(value.title)
    value.coverUrl?.let(::validateHttpUrl)
    validateCount(value.trackCount)
    value.creatorId?.let(::validateId)
    value.creatorName?.let(::validateTitle)
    require((value.description?.length ?: 0) <= 65536)
}
private fun validateAlbum(value: Album) {
    validateId(value.id); validateTitle(value.title); validateArtists(value.artists)
    value.coverUrl?.let(::validateHttpUrl)
    validateCount(value.trackCount)
    require(value.releasedAtEpochMs == null || value.releasedAtEpochMs in 0..MAX_SAFE_INTEGER)
    require((value.company?.length ?: 0) <= 4096)
    require((value.description?.length ?: 0) <= 65536)
}
private fun validateCatalogArtist(value: CatalogArtist) {
    validateId(value.id); validateTitle(value.name)
    value.avatarUrl?.let(::validateHttpUrl)
    require((value.description?.length ?: 0) <= 65536)
    require(value.aliases.size <= 64)
    value.aliases.forEach(::validateTitle)
    validateCount(value.albumCount); validateCount(value.trackCount)
}

fun validateId(id: String) {
    require(id.isNotBlank() && id.length <= 1024 && id.none { it.code < 32 || it.code == 127 }) { "Invalid opaque ID" }
}
fun validateHttpUrl(value: String) {
    require(value.length <= 16384 && value.none { it.code < 32 }) { "Invalid URL" }
    val url = Url(value)
    require(url.protocol.name in setOf("http", "https") && url.host.isNotBlank() && url.user == null && url.password == null) { "Invalid HTTP URL" }
}
fun validateTrack(track: Track) {
    validateId(track.id)
    require(track.title.isNotBlank() && track.title.length <= 4096)
    require(track.durationMs in 0..MAX_SAFE_INTEGER)
    require(track.artists.size <= 64)
    track.artists.forEach { require(it.name.isNotBlank() && it.name.length <= 4096); it.id?.let(::validateId) }
    track.coverUrl?.let(::validateHttpUrl)
    require(track.album?.let { it.length <= 4096 } != false)
    track.write(WireWriter())
}
