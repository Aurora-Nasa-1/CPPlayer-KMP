package cp.player.core.music

data class SearchTerm(val keyword: String, val description: String = "")

/**
 * 前端统一调用的音乐源接口，整合了多 Provider 和本地音乐。
 * 所有涉及到资源定位的入参全部采用带有 Provider 命名空间的 [CPMediaId] (String)。
 */
interface UnifiedMusicSource : ExtendedMusicSource {
    suspend fun getRecommendedSongs(limit: Int = 30): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Recommendations not supported")
    suspend fun getRecommendedPlaylists(limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        cp.player.core.BackendResult.Unsupported("Recommendations not supported")
    suspend fun getPersonalizedPlaylists(limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        cp.player.core.BackendResult.Unsupported("Personalized playlists not supported")
    suspend fun getTopPlaylists(category: String = "全部", order: String = "hot", limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        cp.player.core.BackendResult.Unsupported("Top playlists not supported")
    suspend fun getHighqualityPlaylists(category: String = "全部", limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        cp.player.core.BackendResult.Unsupported("High quality playlists not supported")
    suspend fun getPersonalizedNewSongs(limit: Int = 10): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Personalized new songs not supported")
    suspend fun getBanners(): MusicResult<List<BannerItem>> =
        cp.player.core.BackendResult.Unsupported("Banners not supported")
    suspend fun getRankings(): MusicResult<List<RankingSummary>> =
        cp.player.core.BackendResult.Unsupported("Rankings not supported")
    suspend fun getNewAlbums(area: String = "ALL", limit: Int = 30): MusicResult<List<AlbumSummary>> =
        cp.player.core.BackendResult.Unsupported("New albums not supported")
    suspend fun getTopSongs(region: Int = 0, limit: Int = 20): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Top songs not supported")
    suspend fun getHotArtists(limit: Int = 30): MusicResult<List<ArtistSummary>> =
        cp.player.core.BackendResult.Unsupported("Hot artists not supported")
    suspend fun getHotSearches(): MusicResult<List<SearchTerm>> =
        cp.player.core.BackendResult.Unsupported("Hot searches not supported")
    suspend fun getSearchSuggestions(keyword: String, limit: Int = 8): MusicResult<List<String>> =
        cp.player.core.BackendResult.Unsupported("Search suggestions not supported")
    suspend fun getCurrentAccount(): MusicResult<CurrentAccountSession> =
        cp.player.core.BackendResult.Unsupported("Current account session not supported")
    suspend fun authenticate(request: cp.player.sdk.AuthActionRequest): MusicResult<cp.player.sdk.AuthActionResult> =
        cp.player.core.BackendResult.Unsupported("Authentication action not supported")
    suspend fun scrobble(mediaId: String, sourceId: String, playedSeconds: Int): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Scrobbling not supported")
    suspend fun getUserProfile(uid: String): MusicResult<cp.player.core.model.UserProfile> =
        cp.player.core.BackendResult.Unsupported("User profiles not supported")
    suspend fun getUserRecords(uid: String, type: Int = 0, limit: Int = 100): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("User records not supported")
    suspend fun getUserFollows(uid: String, limit: Int = 50, offset: Int = 0): MusicResult<List<ArtistSummary>> =
        cp.player.core.BackendResult.Unsupported("Follows not supported")
    suspend fun getUserFolloweds(uid: String, limit: Int = 50, offset: Int = 0): MusicResult<List<ArtistSummary>> =
        cp.player.core.BackendResult.Unsupported("Followers not supported")
    suspend fun getUserCloud(limit: Int = 200, offset: Int = 0): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("User cloud not supported")
    suspend fun getLikeList(uid: String): MusicResult<Set<String>> =
        cp.player.core.BackendResult.Unsupported("Like list not supported")
    suspend fun getUserAlbums(uid: String, limit: Int = 100, offset: Int = 0): MusicResult<List<AlbumSummary>> =
        cp.player.core.BackendResult.Unsupported("User albums not supported")
    suspend fun deletePlaylist(mediaId: String): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Playlist deletion not supported")
    suspend fun subscribePlaylist(mediaId: String, subscribed: Boolean): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Playlist subscription not supported")
    suspend fun subscribeAlbum(mediaId: String, subscribed: Boolean): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Album subscription not supported")
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Adding playlist tracks not supported")
    suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Removing playlist tracks not supported")
    suspend fun renamePlaylist(playlistId: String, name: String): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Playlist rename not supported")
    suspend fun createPlaylist(name: String, privacy: Int = 0): MusicResult<String> =
        cp.player.core.BackendResult.Unsupported("Playlist creation not supported")
    suspend fun likeSong(mediaId: String, liked: Boolean): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Song likes not supported")
    suspend fun dislikeSong(mediaId: String): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Song dislikes not supported")
    suspend fun deleteUserCloud(mediaId: String): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Cloud deletion not supported")
    suspend fun deleteUserCloudBatch(mediaIds: List<String>): MusicResult<Boolean> =
        cp.player.core.BackendResult.Unsupported("Cloud deletion not supported")
    suspend fun resolveVideoResource(mediaId: String, resolution: Int = 1080): MusicResult<cp.player.sdk.MediaResource> =
        cp.player.core.BackendResult.Unsupported("Video resolution not supported")
    suspend fun getMediaQualityOptions(mediaId: String): MusicResult<List<cp.player.core.media.MediaQualityOption>> =
        cp.player.core.BackendResult.Unsupported("Media qualities not supported")
    suspend fun getComments(
        mediaId: String, type: String, limit: Int = 20, offset: Int = 0, sortType: Int = 1,
    ): MusicResult<CommentPage> = cp.player.core.BackendResult.Unsupported("Comments not supported")
    suspend fun getCommentsPage(
        mediaId: String, type: String, limit: Int = 20, offset: Int = 0, sortType: Int = 1, cursor: String? = null,
    ): MusicResult<CommentPage> =
        if (cursor == null) getComments(mediaId, type, limit, offset, sortType)
        else cp.player.core.BackendResult.Unsupported("Cursor comment pagination not supported")
    suspend fun getFloorComments(
        mediaId: String, parentCommentId: Long, type: String, limit: Int = 20, time: Long = 0,
    ): MusicResult<CommentFloorPage> = cp.player.core.BackendResult.Unsupported("Comment floors not supported")
    suspend fun postComment(
        mediaId: String, type: String, content: String, replyToCommentId: Long? = null, parentCommentId: Long = 0,
    ): MusicResult<Comment?> = cp.player.core.BackendResult.Unsupported("Posting comments not supported")
    suspend fun likeComment(
        mediaId: String, commentId: Long, type: String, liked: Boolean,
    ): MusicResult<Boolean> = cp.player.core.BackendResult.Unsupported("Comment likes not supported")
    suspend fun getPersonalFm(): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Personal FM not supported")
    suspend fun getSimilarSongs(mediaId: String, limit: Int = 30): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Similar songs not supported")
    suspend fun getIntelligenceSongs(mediaId: String, playlistId: String, limit: Int = 30): MusicResult<List<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Intelligence songs not supported")
    suspend fun getLyrics(mediaId: String): MusicResult<TrackLyrics> =
        cp.player.core.BackendResult.Unsupported("Lyrics not supported")

    /** Metadata only; use getPlaylistTracks for the independently paginated contents. */
    suspend fun getPlaylistDetail(mediaId: String): MusicResult<PlaylistDetail> =
        cp.player.core.BackendResult.Unsupported("Playlists not supported")

    suspend fun getPlaylistTracks(mediaId: String, limit: Int = 300, offset: Int = 0): MusicResult<CatalogPage<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Playlist tracks not supported")

    suspend fun getUserPlaylistPage(mediaId: String, limit: Int = 30, offset: Int = 0): MusicResult<CatalogPage<PlaylistSummary>> =
        cp.player.core.BackendResult.Unsupported("User playlists not supported")

    suspend fun getAlbumDetail(mediaId: String): MusicResult<AlbumDetail> =
        cp.player.core.BackendResult.Unsupported("Albums not supported")

    suspend fun getArtistProfile(mediaId: String): MusicResult<ArtistProfile> =
        cp.player.core.BackendResult.Unsupported("Artists not supported")

    suspend fun getArtistTracks(mediaId: String, limit: Int = 50, offset: Int = 0): MusicResult<CatalogPage<TrackSummary>> =
        cp.player.core.BackendResult.Unsupported("Artist tracks not supported")

    suspend fun getArtistAlbums(mediaId: String, limit: Int = 30, offset: Int = 0): MusicResult<CatalogPage<AlbumSummary>> =
        cp.player.core.BackendResult.Unsupported("Artist albums not supported")

    suspend fun searchPage(keywords: String, type: Int, limit: Int, offset: Int, providers: List<String>? = null): MusicResult<SearchResult> =
        if (offset == 0) search(keywords, type, providers)
        else cp.player.core.BackendResult.Unsupported("Pagination not supported")

    suspend fun getTrackOutcomes(mediaIds: List<String>): MusicResult<List<TrackDetailOutcome>> =
        cp.player.core.BackendResult.Success(mediaIds.map { id ->
            when (val result = getTrackDetail(id)) {
                is cp.player.core.BackendResult.Success -> TrackDetailOutcome(id, result.data)
                is cp.player.core.BackendResult.Error -> TrackDetailOutcome(id, error = result.message)
                is cp.player.core.BackendResult.Unsupported -> TrackDetailOutcome(id, error = "Unsupported")
            }
        })

    /**
     * 获取歌曲详情
     * @param mediaId 例如 "netease://song/12345" 或 "local://song/path/to/file"
     */
    suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary>

    /**
     * 批量获取歌曲详情
     */
    suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>>

    /**
     * 获取歌曲播放地址
     */
    suspend fun getSongUrl(mediaId: String, level: String = "standard"): MusicResult<SongUrl>

    /**
     * 预加载指定媒体（音源 URL、详情等），供下一首播放预热。
     *
     * 默认实现直接委托至 [getSongUrl]。
     */
    suspend fun preloadTrack(mediaId: String, level: String = "standard"): MusicResult<SongUrl> =
        getSongUrl(mediaId, level)

    /**
     * 获取歌曲歌词（LRC/TTML 文本）
     */
    suspend fun getLyric(mediaId: String): MusicResult<String> =
        cp.player.core.BackendResult.Unsupported("Not supported")

    /**
     * 搜索当前账号会话的歌曲；暂不跨 Provider 聚合。
     * @param keywords 关键词
     * @param providers null 或仅含当前 Provider ID；其他集合明确返回 Unsupported
     */
    suspend fun search(keywords: String, type: Int = 1, providers: List<String>? = null): MusicResult<SearchResult>

    /**
     * 获取用户歌单
     * @param providerId 指定从哪个 provider 提取
     * @param uid 用户ID
     */
    suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>>
}

data class TrackDetailOutcome(val mediaId: String, val track: TrackSummary? = null, val error: String? = null)
