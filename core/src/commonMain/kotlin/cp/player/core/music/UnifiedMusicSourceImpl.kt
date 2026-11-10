package cp.player.core.music

import cp.player.core.BackendResult
import cp.player.core.local.LocalMediaSource
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.typed.ProviderHost
import cp.player.core.provider.typed.resolveMv
import cp.player.core.provider.typed.resolveVideo
import cp.player.core.provider.typed.getMediaQualities
import cp.player.core.provider.typed.searchMvs
import cp.player.core.provider.typed.searchVideos
import cp.player.core.provider.typed.searchUsers
import cp.player.core.provider.typed.searchDjRadios
import cp.player.core.provider.typed.getCommentPage
import cp.player.core.monitor.HealthMonitor
import cp.player.core.util.PlatformSupport
import cp.player.core.util.currentTimeMillis
import cp.player.sdk.*
import kotlinx.coroutines.CancellationException

private const val UNKNOWN_PROVIDER = "unknown"

/** Local media or an account-scoped SDK session. No legacy transport or JSON normalization. */
class UnifiedMusicSourceImpl(
    private val localMusicSource: LocalMediaSource,
    private val sdkHost: suspend () -> ProviderHost? = { null },
    private val activeProvider: () -> BackendProvider? = { null },
) : ExtendedMusicSourceBase(), UnifiedMusicSource {
    override suspend fun <T> extendedOperation(block: suspend (ProviderHost) -> T): BackendResult<T> = boundary("extendedOperation") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(block(host))
    }

    override suspend fun deleteUserCloudBatch(mediaIds: List<String>): MusicResult<Boolean> = boundary("deleteUserCloudBatch") {
        require(mediaIds.size in 1..MAX_ITEMS)
        val ids = mediaIds.map(::parse)
        require(ids.map { it.providerId }.distinct().size == 1)
        val host = catalogHost(ids.first())
        BackendResult.Success(host.deleteUserCloud(BatchRequest(ids.map { it.resourceId })).success)
    }

    override suspend fun resolveVideoResource(mediaId: String, resolution: Int): MusicResult<MediaResource> = boundary("resolveVideoResource") {
        val id = CPMediaId.parse(mediaId)
        require(id.providerId != "local" && id.resourceType in setOf("mv", "video"))
        val host = catalogHost(id)
        val request = VideoResolveRequest(id.resourceId, resolution)
        BackendResult.Success(if (id.resourceType == "mv") host.resolveMv(request) else host.resolveVideo(request))
    }

    override suspend fun getMediaQualityOptions(mediaId: String): MusicResult<List<cp.player.core.media.MediaQualityOption>> = boundary("getMediaQualityOptions") {
        val id = CPMediaId.parse(mediaId)
        require(id.providerId != "local" && id.resourceType in setOf("song", "audio", "mv", "video"))
        val page = catalogHost(id).getMediaQualities(MediaQualityRequest(id.resourceId, id.resourceType))
        BackendResult.Success(page.items.filter { it.available }.map {
            cp.player.core.media.MediaQualityOption(
                it.id, it.label, resolution = if (id.resourceType in setOf("mv", "video")) it.id.toIntOrNull() ?: 0 else 0,
                bitrate = it.bitrate?.toLong() ?: 0, sizeBytes = it.sizeBytes ?: 0,
            )
        })
    }

    private fun parse(mediaId: String) = CPMediaId.parse(mediaId).also {
        require(it.providerId.isNotBlank() && it.resourceId.isNotBlank()) { "Invalid media ID" }
        require(it.resourceType == "song" || it.resourceType == "audio") { "Expected an audio media ID" }
        if (it.providerId != "local") cp.player.core.provider.typed.validateId(it.resourceId)
    }

    override suspend fun getRecommendedSongs(limit: Int): MusicResult<List<TrackSummary>> = boundary("getRecommendedSongs") {
        require(limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getRecommendedSongs(RecommendationsRequest(limit)).items.map {
            it.toDomain(host.context.session.providerId)
        })
    }

    override suspend fun getRecommendedPlaylists(limit: Int): MusicResult<List<PlaylistSummary>> = boundary("getRecommendedPlaylists") {
        require(limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getRecommendedPlaylists(RecommendationsRequest(limit)).items.map { it.toDomain() })
    }

    override suspend fun getPersonalizedPlaylists(limit: Int): MusicResult<List<PlaylistSummary>> = boundary("getPersonalizedPlaylists") {
        require(limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getPersonalizedPlaylists(RecommendationsRequest(limit)).items.map { it.toDomain() })
    }

    override suspend fun getTopPlaylists(category: String, order: String, limit: Int): MusicResult<List<PlaylistSummary>> = boundary("getTopPlaylists") {
        require(category.length <= 256 && order.length <= 64 && limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getTopPlaylists(PlaylistBrowseRequest(category, limit, order)).items.map { it.toDomain() })
    }

    override suspend fun getHighqualityPlaylists(category: String, limit: Int): MusicResult<List<PlaylistSummary>> = boundary("getHighqualityPlaylists") {
        require(category.length <= 256 && limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getHighqualityPlaylists(PlaylistBrowseRequest(category, limit, "")).items.map { it.toDomain() })
    }

    override suspend fun getPersonalizedNewSongs(limit: Int): MusicResult<List<TrackSummary>> = boundary("getPersonalizedNewSongs") {
        require(limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getPersonalizedNewSongs(RecommendationsRequest(limit)).items.map {
            it.toDomain(host.context.session.providerId)
        })
    }

    override suspend fun getBanners(): MusicResult<List<BannerItem>> = boundary("getBanners") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getBanners().filter { it.targetType == 1 || it.targetType == 1000 }.map {
            BannerItem(it.id, it.imageUrl, it.title, it.targetType, it.targetId)
        })
    }

    override suspend fun getRankings(): MusicResult<List<RankingSummary>> = boundary("getRankings") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getRankings().map {
            RankingSummary(it.id.toLongOrNull() ?: 0L, it.title, it.coverUrl, it.updateFrequency, it.trackCount, it.id)
        })
    }

    override suspend fun getNewAlbums(area: String, limit: Int): MusicResult<List<AlbumSummary>> = boundary("getNewAlbums") {
        require(area.length in 1..32 && limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getNewAlbums(AlbumBrowseRequest(area, limit)).items.map { it.toDomain() })
    }

    override suspend fun getTopSongs(region: Int, limit: Int): MusicResult<List<TrackSummary>> = boundary("getTopSongs") {
        require(region in setOf(0, 7, 8, 16, 96) && limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getTopSongs(TopSongsRequest(limit, region)).items.map {
            it.toDomain(host.context.session.providerId)
        })
    }

    override suspend fun getHotArtists(limit: Int): MusicResult<List<ArtistSummary>> = boundary("getHotArtists") {
        require(limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getHotArtists(RecommendationsRequest(limit)).items.map {
            ArtistSummary(it.id.toLongOrNull() ?: 0L, it.name, it.avatarUrl, it.id)
        })
    }

    override suspend fun getHotSearches(): MusicResult<List<SearchTerm>> = boundary("getHotSearches") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getHotSearches().map { SearchTerm(it.keyword, it.description) })
    }

    override suspend fun getSearchSuggestions(keyword: String, limit: Int): MusicResult<List<String>> = boundary("getSearchSuggestions") {
        require(keyword.isNotBlank() && keyword.length <= 4096 && limit in 1..50)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getSearchSuggestions(SearchSuggestionRequest(keyword, limit)).items)
    }

    override suspend fun getCurrentAccount(): MusicResult<CurrentAccountSession> = boundary("getCurrentAccount") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        val session = host.getCurrentAccount()
        BackendResult.Success(CurrentAccountSession(
            loggedIn = session.loggedIn,
            userId = session.userId,
            nickname = session.nickname,
            avatarUrl = session.avatarUrl,
        ))
    }

    override suspend fun authenticate(
        request: AuthActionRequest,
    ): MusicResult<AuthActionResult> = boundary("authenticate") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.authenticate(request))
    }

    override suspend fun scrobble(mediaId: String, sourceId: String, playedSeconds: Int): MusicResult<Boolean> = boundary("scrobble") {
        val id = parse(mediaId)
        require(id.providerId != "local" && playedSeconds > 0 && sourceId.length <= 1024)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        if (id.providerId != host.context.session.providerId)
            return@boundary BackendResult.Unsupported("Cross-provider requests are not supported")
        BackendResult.Success(host.scrobble(ScrobbleRequest(id.resourceId, sourceId, playedSeconds)).success)
    }

    override suspend fun getUserProfile(uid: String): MusicResult<cp.player.core.model.UserProfile> = boundary("getUserProfile") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        val p = host.getUserProfile(UserRequest(uid))
        BackendResult.Success(cp.player.core.model.UserProfile(
        userId = p.id.toLongOrNull() ?: 0L,
            nickname = p.nickname,
            avatarUrl = p.avatarUrl.orEmpty(),
            signature = p.signature.orEmpty(),
            playlistCount = p.playlistCount,
            follows = p.follows,
            followeds = p.followeds,
        resourceId = p.id,
        ))
    }

    override suspend fun getUserRecords(uid: String, type: Int, limit: Int): MusicResult<List<TrackSummary>> = boundary("getUserRecords") {
        require(type in 0..1 && limit in 1..MAX_ITEMS)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getUserRecords(UserRecordsRequest(uid, type, limit)).items.map {
            it.toDomain(host.context.session.providerId)
        })
    }

    override suspend fun getUserFollows(uid: String, limit: Int, offset: Int): MusicResult<List<ArtistSummary>> =
        userList(uid, limit, offset, followers = false)

    override suspend fun getUserFolloweds(uid: String, limit: Int, offset: Int): MusicResult<List<ArtistSummary>> =
        userList(uid, limit, offset, followers = true)

    private suspend fun userList(uid: String, limit: Int, offset: Int, followers: Boolean): MusicResult<List<ArtistSummary>> = boundary("userList") {
        require(limit in 1..MAX_ITEMS && offset >= 0)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getUserFollows(UserListRequest(uid, limit, offset), followers).items.map {
            ArtistSummary(it.id.toLongOrNull() ?: 0L, it.name, it.avatarUrl, it.id)
        })
    }

    override suspend fun getUserCloud(limit: Int, offset: Int): MusicResult<List<TrackSummary>> = boundary("getUserCloud") {
        require(limit in 1..MAX_ITEMS && offset >= 0)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getUserCloud(UserListRequest("self", limit, offset)).items.map {
            it.toDomain(host.context.session.providerId)
        })
    }

    override suspend fun getLikeList(uid: String): MusicResult<Set<String>> = boundary("getLikeList") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getLikeList(UserRequest(uid)).items.toSet())
    }

    override suspend fun getUserAlbums(uid: String, limit: Int, offset: Int): MusicResult<List<AlbumSummary>> = boundary("getUserAlbums") {
        require(limit in 1..MAX_ITEMS && offset >= 0)
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getUserAlbums(UserListRequest(uid, limit, offset)).items.map { it.toDomain() })
    }

    override suspend fun deletePlaylist(mediaId: String): MusicResult<Boolean> = boundary("deletePlaylist") {
        val id = catalogId(mediaId, "playlist")
        BackendResult.Success(catalogHost(id).deletePlaylist(IdRequest(id.resourceId)).success)
    }

    override suspend fun subscribePlaylist(mediaId: String, subscribed: Boolean): MusicResult<Boolean> = boundary("subscribePlaylist") {
        val id = catalogId(mediaId, "playlist")
        BackendResult.Success(catalogHost(id).subscribePlaylist(
            PlaylistSubscribeRequest(id.resourceId, subscribed),
        ).success)
    }

    override suspend fun subscribeAlbum(mediaId: String, subscribed: Boolean): MusicResult<Boolean> = boundary("subscribeAlbum") {
        val id = catalogId(mediaId, "album")
        BackendResult.Success(catalogHost(id).subscribeAlbum(AlbumSubscribeRequest(id.resourceId, subscribed)).success)
    }

    override suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): MusicResult<Boolean> = boundary("addTracksToPlaylist") {
        val playlist = catalogId(playlistId, "playlist")
        val ids = trackIds.map { catalogId(it, "song").also { song -> require(song.providerId == playlist.providerId) }.resourceId }
        BackendResult.Success(catalogHost(playlist).addTracksToPlaylist(PlaylistTracksRequest(playlist.resourceId, ids)).success)
    }

    override suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>): MusicResult<Boolean> = boundary("removeTracksFromPlaylist") {
        val playlist = catalogId(playlistId, "playlist")
        val ids = trackIds.map { catalogId(it, "song").also { song -> require(song.providerId == playlist.providerId) }.resourceId }
        BackendResult.Success(catalogHost(playlist).removeTracksFromPlaylist(PlaylistTracksRequest(playlist.resourceId, ids)).success)
    }

    override suspend fun renamePlaylist(playlistId: String, name: String): MusicResult<Boolean> = boundary("renamePlaylist") {
        val playlist = catalogId(playlistId, "playlist")
        BackendResult.Success(catalogHost(playlist).renamePlaylist(PlaylistNameRequest(playlist.resourceId, name)).success)
    }

    override suspend fun createPlaylist(name: String, privacy: Int): MusicResult<String> = boundary("createPlaylist") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        val result = host.createPlaylist(CreatePlaylistRequest(name, privacy))
        BackendResult.Success(result.id ?: throw ProviderException(Failure(FailureCategory.INVALID_RESPONSE, "Created playlist ID missing")))
    }

    override suspend fun likeSong(mediaId: String, liked: Boolean): MusicResult<Boolean> = boundary("likeSong") {
        val id = parse(mediaId)
        require(id.providerId != "local")
        val host = catalogHost(id)
        BackendResult.Success(host.likeSong(SongLikeRequest(id.resourceId, liked)).success)
    }

    override suspend fun dislikeSong(mediaId: String): MusicResult<Boolean> = boundary("dislikeSong") {
        val id = parse(mediaId)
        require(id.providerId != "local")
        BackendResult.Success(catalogHost(id).dislikeSong(IdRequest(id.resourceId)).success)
    }

    override suspend fun deleteUserCloud(mediaId: String): MusicResult<Boolean> = boundary("deleteUserCloud") {
        val id = parse(mediaId)
        require(id.providerId != "local")
        BackendResult.Success(catalogHost(id).deleteUserCloud(BatchRequest(listOf(id.resourceId))).success)
    }

    override suspend fun getComments(
        mediaId: String, type: String, limit: Int, offset: Int, sortType: Int,
    ): MusicResult<CommentPage> = boundary("getComments") {
        val id = parseCommentResourceId(mediaId)
        require(limit in 1..MAX_ITEMS && offset >= 0 && sortType in 1..3)
        val page = catalogHost(id).getComments(CommentPageRequest(id.resourceId, type, limit, offset, sortType))
        BackendResult.Success(CommentPage(
            page.comments.map { it.toDomain() }, page.totalCount, page.hasMore, page.cursor, page.sortType,
        ))
    }

    override suspend fun getCommentsPage(
        mediaId: String, type: String, limit: Int, offset: Int, sortType: Int, cursor: String?,
    ): MusicResult<CommentPage> = boundary("getCommentsPage") {
        val id = parseCommentResourceId(mediaId)
        require(id.resourceType == (if (type == "music") "song" else type))
        val host = catalogHost(id)
        val cursorPaging = ExtendedCapability.GETCOMMENTPAGE in host.capabilities().supported
        val page = if (cursorPaging) {
            host.getCommentPage(CommentQuery(id.resourceId, type, limit, offset, sortType, cursor))
        } else {
            if (cursor != null) return@boundary BackendResult.Unsupported("Cursor comment pagination is not supported")
            require(limit in 1..MAX_ITEMS && offset >= 0 && sortType in 1..3)
            host.getComments(CommentPageRequest(id.resourceId, type, limit, offset, sortType))
        }
        BackendResult.Success(CommentPage(
            page.comments.map { it.toDomain() }, page.totalCount, page.hasMore, page.cursor, page.sortType,
            nextCursor = if (cursorPaging) page.cursor else null,
        ))
    }

    override suspend fun getFloorComments(
        mediaId: String, parentCommentId: Long, type: String, limit: Int, time: Long,
    ): MusicResult<CommentFloorPage> = boundary("getFloorComments") {
        val id = parseCommentResourceId(mediaId)
        require(limit in 1..MAX_ITEMS && time >= 0 && parentCommentId > 0)
        val page = catalogHost(id).getFloorComments(CommentFloorRequest(id.resourceId, parentCommentId, type, limit, time))
        BackendResult.Success(CommentFloorPage(
            page.replies.map { it.toDomain().copy(parentCommentId = parentCommentId) },
            page.totalCount, page.hasMore, page.nextTime,
        ))
    }

    override suspend fun postComment(
        mediaId: String, type: String, content: String, replyToCommentId: Long?, parentCommentId: Long,
    ): MusicResult<Comment?> = boundary("postComment") {
        val id = parseCommentResourceId(mediaId)
        val comment = catalogHost(id).postComment(PostCommentRequest(id.resourceId, type, content, replyToCommentId)).comment
        BackendResult.Success(comment?.toDomain()?.copy(parentCommentId = parentCommentId))
    }

    override suspend fun likeComment(
        mediaId: String, commentId: Long, type: String, liked: Boolean,
    ): MusicResult<Boolean> = boundary("likeComment") {
        val id = parseCommentResourceId(mediaId)
        require(commentId > 0)
        BackendResult.Success(catalogHost(id).likeComment(
            CommentLikeRequest(id.resourceId, commentId, type, liked),
        ).success)
    }

    override suspend fun getPersonalFm(): MusicResult<List<TrackSummary>> = boundary("getPersonalFm") {
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        BackendResult.Success(host.getPersonalFm().items.map { it.toDomain(host.context.session.providerId) })
    }

    override suspend fun getSimilarSongs(mediaId: String, limit: Int): MusicResult<List<TrackSummary>> = boundary("getSimilarSongs") {
        require(limit in 1..MAX_ITEMS)
        val id = parse(mediaId)
        val host = catalogHost(id)
        BackendResult.Success(host.getSimilarSongs(SimilarSongsRequest(id.resourceId, limit)).items.map {
            it.toDomain(id.providerId)
        })
    }

    override suspend fun getIntelligenceSongs(
        mediaId: String, playlistId: String, limit: Int,
    ): MusicResult<List<TrackSummary>> = boundary("getIntelligenceSongs") {
        require(limit in 1..MAX_ITEMS)
        val song = parse(mediaId)
        val playlist = catalogId(playlistId, "playlist")
        require(song.providerId == playlist.providerId)
        val host = catalogHost(song)
        BackendResult.Success(host.getIntelligenceSongs(
            IntelligenceSongsRequest(song.resourceId, playlist.resourceId, limit),
        ).items.map { it.toDomain(song.providerId) })
    }

    private fun parseCommentResourceId(mediaId: String): CPMediaId {
        val id = CPMediaId.parse(mediaId)
        require(id.providerId != "local" && id.resourceId.isNotBlank())
        cp.player.core.provider.typed.validateId(id.resourceId)
        return id
    }

    /**
     * 本地曲查找索引。
     *
     * 一次播放会问三次（取 URL / 取词 / 取详情），每次都 `items().firstOrNull` 全库线性扫
     * 在几万条媒体库上是实打实的开销。索引只在 [LocalMediaSource.items] 的快照**换对象**时重建
     * （扫描完成会换新 List），所以稳态下是纯查表。
     */
    private class LocalIndex(
        val source: List<LocalMediaItem>,
        val byPath: Map<String, LocalMediaItem>,
        val byOriginalId: Map<String, List<LocalMediaItem>>,
    )

    private var localIndex: LocalIndex? = null

    private fun localIndexFor(items: List<LocalMediaItem>): LocalIndex {
        localIndex?.takeIf { it.source === items }?.let { return it }
        val audio = items.filter { it.mediaType == MediaType.AUDIO }
        return LocalIndex(
            source = items,
            byPath = audio.associateBy { it.path },
            byOriginalId = audio.mapNotNull { item -> item.originalMediaId?.let { it to item } }
                .groupBy({ it.first }, { it.second }),
        ).also { localIndex = it }
    }

    private fun localItem(id: CPMediaId, mediaId: String): LocalMediaItem? {
        val index = localIndexFor(localMusicSource.items().value)
        return if (id.providerId == "local") index.byPath[id.resourceId]
        // 下载副本可能已被用户删掉：仍按「文件真的在」判定（与既有语义一致）。
        else index.byOriginalId[mediaId]?.firstOrNull { PlatformSupport.exists(it.path) }
    }

    private fun LocalMediaItem.toTrack(mediaId: String) = TrackSummary(
        id = mediaId, name = title, artist = artist.orEmpty(), album = album,
        coverUrl = coverUri, durationMs = durationMs, originalMediaId = originalMediaId,
    )

    /**
     * 所有取数的唯一出口：统一异常 → 结果映射，并逐次上报 [HealthMonitor]。
     *
     * 诊断页要的「谁 / 做了什么 / 多久 / 结果如何」只有这里同时知道，
     * 因此监控挂在这一层，而不是散落在各 Provider 或 UI 侧。
     */
    private suspend fun <T> boundary(op: String, block: suspend () -> MusicResult<T>): MusicResult<T> {
        val startedAt = currentTimeMillis()
        val providerId = activeProvider()?.id ?: UNKNOWN_PROVIDER
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            if (e.failure.category == FailureCategory.UNSUPPORTED) BackendResult.Unsupported(e.failure.message)
            else BackendResult.Error("Provider ${e.failure.category}", cause = e)
        } catch (e: Exception) {
            BackendResult.Error("Provider request failed", cause = e)
        }
        HealthMonitor.recordCall(
            HealthMonitor.ApiCallRecord(
                timestamp = startedAt,
                providerId = providerId,
                operation = op,
                durationMs = currentTimeMillis() - startedAt,
                level = when (result) {
                    is BackendResult.Success -> HealthMonitor.HealthLevel.OK
                    is BackendResult.Unsupported -> HealthMonitor.HealthLevel.WARNING
                    is BackendResult.Error -> HealthMonitor.HealthLevel.ERROR
                },
                errorMessage = when (result) {
                    is BackendResult.Unsupported -> result.message
                    is BackendResult.Error -> result.message
                    is BackendResult.Success -> null
                },
            ),
        )
        return result
    }

    override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> = boundary("getTrackDetail") {
        val id = parse(mediaId)
        localItem(id, mediaId)?.let { return@boundary BackendResult.Success(it.toTrack(mediaId)) }
        if (id.providerId == "local") return@boundary BackendResult.Error("Local audio not found")
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        if (id.providerId != host.context.session.providerId)
            return@boundary BackendResult.Unsupported("Cross-provider requests are not supported")
        BackendResult.Success(host.get(id.resourceId).toDomain(id.providerId))
    }

    override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> =
        when (val results = getTrackOutcomes(mediaIds)) {
            is BackendResult.Success -> {
                val tracks = results.data.mapNotNull { it.track }
                if (results.data.isNotEmpty() && results.data.all { it.error == FailureCategory.UNSUPPORTED })
                    BackendResult.Unsupported("Track details are not supported")
                else if (tracks.isEmpty() && mediaIds.isNotEmpty()) BackendResult.Error("No requested tracks available")
                else BackendResult.Success(tracks)
            }
            is BackendResult.Error -> results
            is BackendResult.Unsupported -> results
        }

    override suspend fun getTrackOutcomes(mediaIds: List<String>): MusicResult<List<TrackDetailOutcome>> = boundary("getTrackOutcomes") {
        if (mediaIds.isEmpty()) return@boundary BackendResult.Success(emptyList())
        val results = arrayOfNulls<TrackDetailOutcome>(mediaIds.size)
        val remote = mutableListOf<Pair<Int, CPMediaId>>()
        val localItems = localMusicSource.items().value.filter { it.mediaType == MediaType.AUDIO }
        val byPath = localItems.associateBy { it.path }
        val downloads = localItems.filter { it.originalMediaId != null }.groupBy { it.originalMediaId }
        val offline = mutableMapOf<String, LocalMediaItem?>()
        // Preserve positions and duplicates; one malformed or foreign ID cannot lose the whole queue.
        mediaIds.forEachIndexed { index, mediaId ->
            val id = try { parse(mediaId) } catch (_: IllegalArgumentException) { null }
            val item = when {
                id == null -> null
                id.providerId == "local" -> byPath[id.resourceId]
                else -> {
                    if (mediaId !in offline) offline[mediaId] =
                        downloads[mediaId]?.firstOrNull { PlatformSupport.exists(it.path) }
                    offline[mediaId]
                }
            }
            when {
                id == null -> results[index] = TrackDetailOutcome(mediaId, error = "InvalidId")
                item != null -> results[index] = TrackDetailOutcome(mediaId, item.toTrack(mediaId))
                id.providerId == "local" -> results[index] = TrackDetailOutcome(mediaId, error = FailureCategory.NOT_FOUND)
                else -> remote += index to id
            }
        }
        if (remote.isNotEmpty()) {
            val host = try {
                sdkHost()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val category = (e as? ProviderException)?.failure?.category ?: FailureCategory.INVALID_RESPONSE
                remote.forEach { (index, _) -> results[index] = TrackDetailOutcome(mediaIds[index], error = category) }
                return@boundary BackendResult.Success(results.map { checkNotNull(it) })
            }
            val supported = remote.filter { (index, id) ->
                val error = when {
                    host == null -> "NoProvider"
                    id.providerId != host.context.session.providerId -> FailureCategory.UNSUPPORTED
                    else -> null
                }
                if (error != null) results[index] = TrackDetailOutcome(mediaIds[index], error = error)
                error == null
            }
            supported.chunked(MAX_ITEMS).forEach { batch ->
                try {
                    val outcomes = checkNotNull(host).getMany(batch.map { it.second.resourceId }).items
                    batch.zip(outcomes).forEach { (entry, outcome) ->
                        val (index, id) = entry
                        results[index] = TrackDetailOutcome(
                            mediaIds[index], outcome.track?.toDomain(id.providerId), outcome.failure?.category,
                        )
                    }
                } catch (e: ProviderException) {
                    batch.forEach { (index, _) ->
                        results[index] = TrackDetailOutcome(mediaIds[index], error = e.failure.category)
                    }
                }
            }
        }
        BackendResult.Success(results.map { checkNotNull(it) })
    }

    override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> = boundary("getSongUrl") {
        val id = parse(mediaId)
        localItem(id, mediaId)?.let { item ->
            val codec = item.path.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() }
            return@boundary BackendResult.Success(SongUrl(
                item.path, if (codec in setOf("FLAC", "WAV")) "lossless" else level,
                item.sizeBytes, null, codec = codec,
            ))
        }
        if (id.providerId == "local") return@boundary BackendResult.Error("Local audio not found")
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        if (id.providerId != host.context.session.providerId)
            return@boundary BackendResult.Unsupported("Cross-provider requests are not supported")
        val provider = activeProvider()?.takeIf { it.id == id.providerId }
        val resource = host.resolve(ResolveRequest(id.resourceId, provider?.resolveQualityKey(level) ?: level))
        val quality = provider?.resolveQualityOption(resource.actualQuality)
        BackendResult.Success(SongUrl(
            url = resource.url, level = quality?.targetQuality ?: resource.actualQuality, sizeBytes = resource.sizeBytes,
            expireAt = resource.expiresAtEpochMs, headers = resource.headers.associate { it.name to it.value },
            codec = resource.format, bitrate = resource.bitrate, qualityKey = resource.actualQuality,
            qualityLabel = quality?.label,
        ))
    }

    override suspend fun getLyrics(mediaId: String): MusicResult<TrackLyrics> = boundary("getLyrics") {
        val id = parse(mediaId)
        val item = localItem(id, mediaId)
        if (item != null) {
            val lyrics = cp.player.core.playback.SidecarLyricLoader.load(item.path)
            if (lyrics != null) return@boundary BackendResult.Success(TrackLyrics(
                lrc = cp.player.core.lyrics.TtmlSerializer.toLrc(lyrics.first),
            ))
        }
        if (id.providerId == "local") return@boundary BackendResult.Error("No local lyrics")
        val host = catalogHost(id)
        val lyrics = host.getLyrics(id.resourceId)
        BackendResult.Success(TrackLyrics(lyrics.lrc, lyrics.ttml, lyrics.yrc, lyrics.translation, lyrics.romanization))
    }

    override suspend fun getLyric(mediaId: String): MusicResult<String> =
        when (val result = getLyrics(mediaId)) {
            is BackendResult.Success -> BackendResult.Success(
                result.data.ttml ?: result.data.yrc ?: result.data.lrc.orEmpty(),
            )
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }

    override suspend fun search(keywords: String, type: Int, providers: List<String>?): MusicResult<SearchResult> =
        searchPage(keywords, type, 30, 0, providers)

    override suspend fun searchPage(
        keywords: String, type: Int, limit: Int, offset: Int, providers: List<String>?,
    ): MusicResult<SearchResult> = boundary("searchPage") {
        require(providers == null || providers.isNotEmpty()) { "providers must not be empty" }
        require(keywords.isNotBlank() && limit in 1..MAX_ITEMS && offset >= 0) { "Invalid search request" }
        if (type !in setOf(1, 10, 100, 1000, 1002, 1004, 1009, 1014))
            return@boundary BackendResult.Unsupported("Search type is not supported")
        val host = sdkHost() ?: return@boundary BackendResult.Error("No active SDK provider")
        val providerId = host.context.session.providerId
        if (providers != null && providers.distinct() != listOf(providerId))
            return@boundary BackendResult.Unsupported("Cross-provider search is not supported")
        val request = SearchRequest(keywords, limit, offset)
        val result = when (type) {
            1004, 1014 -> (if (type == 1004) host.searchMvs(request) else host.searchVideos(request)).let { page ->
                SearchResult(emptyList(), emptyList(), emptyList(), total = page.total, hasMore = page.hasMore,
                    videos = page.items.map { item -> VideoSummary(
                        item.id, item.title, item.durationMs, item.coverUrl,
                        item.creators.map { ArtistSummary(it.id?.toLongOrNull() ?: 0, it.name, null, it.id.orEmpty()) },
                        if (type == 1004) "mv" else "video",
                    ) })
            }
            1002 -> host.searchUsers(request).let { page ->
                SearchResult(emptyList(), emptyList(), emptyList(), total = page.total, hasMore = page.hasMore,
                    users = page.items.map { SocialUserSummary(it.id, it.nickname, it.avatarUrl) })
            }
            1009 -> host.searchDjRadios(request).let { page ->
                SearchResult(emptyList(), emptyList(), emptyList(), total = page.total, hasMore = page.hasMore,
                    radios = page.items.map { item -> DjRadioSummary(
                        item.id, item.title, item.coverUrl,
                        item.creator?.let { SocialUserSummary(it.id, it.nickname, it.avatarUrl) }, item.programCount,
                    ) })
            }
            1000 -> host.searchPlaylists(request).let { page -> SearchResult(
                emptyList(), page.items.map { it.toDomain() }, emptyList(), total = page.total, hasMore = page.hasMore,
            ) }
            10 -> host.searchAlbums(request).let { page -> SearchResult(
                emptyList(), emptyList(), emptyList(), page.items.map { it.toDomain() }, page.total, page.hasMore,
            ) }
            100 -> host.searchArtists(request).let { page -> SearchResult(
                emptyList(), emptyList(), page.items.map { it.toSummary() }, total = page.total, hasMore = page.hasMore,
            ) }
            else -> host.search(request).let { page -> SearchResult(
                page.items.map { it.toDomain(providerId) }, emptyList(), emptyList(), total = page.total, hasMore = page.hasMore,
            ) }
        }
        BackendResult.Success(result)
    }

    private fun catalogId(mediaId: String, type: String): CPMediaId = CPMediaId.parse(mediaId).also {
        require(it.providerId.isNotBlank() && it.providerId != "local" && it.resourceType == type) { "Invalid catalog ID" }
        cp.player.core.provider.typed.validateId(it.resourceId)
    }

    private suspend fun catalogHost(id: CPMediaId): ProviderHost {
        val host = sdkHost() ?: throw ProviderException(Failure(FailureCategory.RUNTIME, "No active SDK provider"))
        if (host.context.session.providerId != id.providerId) unsupported()
        return host
    }

    override suspend fun getPlaylistDetail(mediaId: String): MusicResult<PlaylistDetail> = boundary("getPlaylistDetail") {
        val id = catalogId(mediaId, "playlist")
        val playlist = catalogHost(id).getPlaylist(id.resourceId)
        BackendResult.Success(PlaylistDetail(playlist.toDomain(), emptyList(), playlist.description))
    }

    override suspend fun getPlaylistTracks(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<TrackSummary>> = boundary("getPlaylistTracks") {
        val id = catalogId(mediaId, "playlist")
        val page = catalogHost(id).getPlaylistTracks(PagedIdRequest(id.resourceId, limit, offset))
        BackendResult.Success(CatalogPage(page.items.map { it.toDomain(id.providerId) }, page.total, page.hasMore))
    }

    override suspend fun getUserPlaylistPage(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<PlaylistSummary>> = boundary("getUserPlaylistPage") {
        val id = catalogId(mediaId, "user")
        val page = catalogHost(id).getUserPlaylists(PagedIdRequest(id.resourceId, limit, offset))
        BackendResult.Success(CatalogPage(page.items.map { it.toDomain() }, page.total, page.hasMore))
    }

    override suspend fun getAlbumDetail(mediaId: String): MusicResult<AlbumDetail> = boundary("getAlbumDetail") {
        val id = catalogId(mediaId, "album")
        val detail = catalogHost(id).getAlbum(id.resourceId)
        val album = detail.album
        val summary = album.toDomain()
        BackendResult.Success(AlbumDetail(
            summary.id, summary.name, summary.coverUrl, summary.artistName, summary.artistId,
            summary.publishTimeMs, album.company, album.description, detail.tracks.map { it.toDomain(id.providerId) },
            summary.resourceId, summary.artistResourceId,
        ))
    }

    override suspend fun getArtistProfile(mediaId: String): MusicResult<ArtistProfile> = boundary("getArtistProfile") {
        val id = catalogId(mediaId, "artist")
        val artist = catalogHost(id).getArtist(id.resourceId)
        BackendResult.Success(ArtistProfile(
            artist.id.toLongOrNull() ?: 0L, artist.name, artist.avatarUrl, artist.description, artist.aliases,
            artist.albumCount ?: 0, artist.trackCount ?: 0, 0, artist.id,
        ))
    }

    override suspend fun getArtistTracks(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<TrackSummary>> = boundary("getArtistTracks") {
        val id = catalogId(mediaId, "artist")
        val page = catalogHost(id).getArtistTracks(PagedIdRequest(id.resourceId, limit, offset))
        BackendResult.Success(CatalogPage(page.items.map { it.toDomain(id.providerId) }, page.total, page.hasMore))
    }

    override suspend fun getArtistAlbums(mediaId: String, limit: Int, offset: Int): MusicResult<CatalogPage<AlbumSummary>> = boundary("getArtistAlbums") {
        val id = catalogId(mediaId, "artist")
        val page = catalogHost(id).getArtistAlbums(PagedIdRequest(id.resourceId, limit, offset))
        BackendResult.Success(CatalogPage(page.items.map { it.toDomain() }, page.total, page.hasMore))
    }

    override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> =
        when (val result = getUserPlaylistPage(CPMediaId(providerId, "user", uid.toString()).toString())) {
            is BackendResult.Success -> BackendResult.Success(result.data.items)
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
}

internal fun Playlist.toDomain() = PlaylistSummary(
    id.toLongOrNull() ?: 0L, title, coverUrl, trackCount ?: 0, creatorName, id,
)
internal fun Album.toDomain() = AlbumSummary(
    id = id.toLongOrNull() ?: 0L, name = title, coverUrl = coverUrl,
    artistName = artists.joinToString(" / ") { it.name }.ifBlank { null },
    trackCount = trackCount ?: 0, publishTimeMs = releasedAtEpochMs,
    artistId = artists.firstOrNull()?.id?.toLongOrNull(), resourceId = id,
    artistResourceId = artists.firstOrNull()?.id,
)
internal fun CatalogArtist.toSummary() = ArtistSummary(id.toLongOrNull() ?: 0L, name, avatarUrl, id)

internal fun Track.toDomain(providerId: String): TrackSummary = TrackSummary(
    id = CPMediaId(providerId, "song", id).toString(),
    name = title, artist = artists.joinToString(" / ") { it.name },
    album = album, coverUrl = coverUrl, durationMs = durationMs,
    artists = artists.map {
        ArtistSummary(id = it.id?.toLongOrNull() ?: 0, name = it.name, avatarUrl = null, resourceId = it.id.orEmpty())
    },
)

internal fun cp.player.sdk.CommentItem.toDomain(): Comment = Comment(
    id = id,
    content = content,
    user = user,
    avatar = avatar,
    time = time,
    likedCount = likedCount,
    liked = liked,
    replyCount = replyCount,
    beReplied = beReplied.map { Comment.Reply(it.userId, it.nickname, it.content) }.ifEmpty { null },
    userId = userId,
    timeMs = timeMs,
    parentCommentId = parentCommentId,
    ipLocation = ipLocation,
    isHot = isHot,
    topReplies = topReplies.map { it.toDomain() },
)
