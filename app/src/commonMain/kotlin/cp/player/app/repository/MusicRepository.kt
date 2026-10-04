package cp.player.app.repository

import cp.player.core.api.extractUidFromLoginStatus
import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.AlbumDetail
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistProfile
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.Comment
import cp.player.core.music.MusicResult
import cp.player.core.music.MusicSourceFromApi
import cp.player.core.music.PlaylistDetail
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.PlaylistTracksPage
import cp.player.core.music.ProfileBundle
import cp.player.core.music.RankingSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.SongDetailInfo
import cp.player.core.music.TrackSummary
import cp.player.core.util.runCatchingExceptCancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Application-facing music facade. UI models do not depend on raw Provider/API wiring. */
class MusicRepository(private val api: MusicApiService) {
    suspend fun getHotSearches(): JsonElement = api.getHotSearches()

    suspend fun getSearchSuggestions(keyword: String): JsonElement =
        api.getSearchSuggestions(keyword)

    suspend fun search(keyword: String, type: Int): MusicResult<SearchResult> =
        MusicSourceFromApi.search(api, keyword, type)

    /**
     * 心动模式/智能播放列表。
     *
     * ⚠️ `playlistId` 是**必填**：上游 `playmode/intelligence/list` 要求 `pid` 指向一个
     * 真实歌单（约定用「我喜欢的音乐」收藏夹），传 `0` 会直接报错或返回空 ——
     * 与旧项目 `PlaybackRepository.getHeartbeatSongs(songId, playlistId)` 的行为一致。
     */
    suspend fun getIntelligenceSongs(songId: String, playlistId: Long): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseFmSongs(api.getIntelligenceList(songId, playlistId))

    suspend fun getSimilarSongs(seedId: String): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseFmSongs(api.getSimilarSongs(seedId))

    suspend fun getPersonalFm(): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getPersonalFm(api)

    suspend fun getPersonalFmBatch(targetSize: Int = 18, maxRequests: Int = 8): MusicResult<List<TrackSummary>> {
        val merged = mutableListOf<TrackSummary>()
        repeat(maxRequests.coerceAtLeast(1)) {
            when (val page = getPersonalFm()) {
                is BackendResult.Success -> {
                    val newItems = page.data.filter { candidate -> merged.none { it.id == candidate.id } }
                    merged += newItems
                    if (merged.size >= targetSize) return BackendResult.Success(merged.take(targetSize))
                    if (page.data.isEmpty()) return BackendResult.Success(merged)
                }
                is BackendResult.Error -> return if (merged.isNotEmpty()) BackendResult.Success(merged) else page
                is BackendResult.Unsupported -> return if (merged.isNotEmpty()) BackendResult.Success(merged) else page
            }
        }
        return BackendResult.Success(merged)
    }

    suspend fun getRecommendedSongs(): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseRecommendedSongs(api.getRecommendedSongs())

    suspend fun getRecommendedPlaylists(): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.parseRecommendedPlaylists(api.getRecommendedPlaylists())

    suspend fun getPersonalizedPlaylists(limit: Int): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.parseRecommendedPlaylists(api.getPersonalizedPlaylists(limit))

    suspend fun getTopPlaylists(limit: Int): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.parseRecommendedPlaylists(api.getTopPlaylists(limit = limit))

    suspend fun getPersonalizedNewSongs(limit: Int): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseRecommendedSongs(api.getPersonalizedNewSongs(limit))

    // ======================== 首页发现区 ========================
    //
    // 这几项此前只存在于 [MusicApiService]，首页够不到，于是首页只能靠
    // 「日推 + 两个歌单栅格」撑版面，其余位置用纯入口卡片填空。
    // 这里把它们按首页真正需要的粒度暴露出来。

    /** 首页焦点图（已过滤掉应用内无法跳转的类型）。 */
    suspend fun getBanners(): MusicResult<List<BannerItem>> =
        MusicSourceFromApi.getBanners(api)

    /** 榜单列表（飙升榜 / 新歌榜 / 原创榜 …）。 */
    suspend fun getRankings(): MusicResult<List<RankingSummary>> =
        MusicSourceFromApi.getRankings(api)

    /** 新碟上架。 */
    suspend fun getNewAlbums(limit: Int = 30): MusicResult<List<AlbumSummary>> =
        MusicSourceFromApi.getNewAlbums(api, limit = limit)

    /**
     * 新歌速递。
     * @param type 地区：0=全部, 7=华语, 96=欧美, 8=日本, 16=韩国
     */
    suspend fun getNewSongsByRegion(type: Int, limit: Int = 20): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getTopSongs(api, type).let { result ->
            when (result) {
                is BackendResult.Success -> BackendResult.Success(result.data.take(limit))
                else -> result
            }
        }

    /** 热门歌手。 */
    suspend fun getHotArtists(limit: Int = 30): MusicResult<List<ArtistSummary>> =
        MusicSourceFromApi.getHotArtists(api, limit = limit)

    /** 精品歌单。 */
    suspend fun getHighQualityPlaylists(limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.getHighQualityPlaylists(api, limit = limit)

    suspend fun getPlaylistDetail(id: Long): MusicResult<PlaylistDetail> =
        MusicSourceFromApi.getPlaylistDetail(api, id)

    // ======================== 专辑 / 歌手 / 用户主页 ========================
    //
    // 这三组此前只存在于 [MusicApiService]（原始 JsonElement），UI 够不到 ——
    // 于是首页把「点专辑 / 点歌手」一律退化成「按名字再搜一次」，
    // 搜索结果里的专辑页签也因为拿不到 `result.albums` 而恒空。
    // 这里按页面真正需要的粒度暴露解析后的模型。

    /** 专辑详情（含曲目）。 */
    suspend fun getAlbumDetail(id: Long): MusicResult<AlbumDetail> =
        MusicSourceFromApi.getAlbumDetail(api, id)

    /** 歌手介绍/资料；返回 null 表示这个 id 不是歌手。取消（如快速离开页面）会正常上抛。 */
    suspend fun getArtistProfile(id: Long): ArtistProfile? =
        runCatchingExceptCancellation { MusicSourceFromApi.parseArtistProfile(api.getArtistDetail(id)) }.getOrNull()

    /** 歌手热门歌曲（最多 50 首）。 */
    suspend fun getArtistTopSongs(id: Long): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getArtistTopSongs(api, id)

    /** 歌手专辑。 */
    suspend fun getArtistAlbums(id: Long, limit: Int = 50): MusicResult<List<AlbumSummary>> =
        MusicSourceFromApi.getArtistAlbums(api, id, limit)

    /**
     * 拉取「他人主页」的完整数据包（歌手优先，否则按普通用户）。
     *
     * 逻辑逐条对照旧项目 `UserViewModel.fetchOtherUserProfile`：
     * `artist/detail` 与 `user/detail` 用**同一段 id 空间**，同一个 id 在两边都可能返回数据，
     * 所以只能「先试歌手、拿到 `data.artist` 才认」，否则按普通用户处理。
     *
     * 歌手分支额外拉「热门歌曲」与「专辑」；用户分支拉「歌单」。
     * 这些子请求**失败不致命** —— 资料本身能显示就先显示，空的区块由页面自己收敛。
     */
    suspend fun getProfileBundle(uid: Long): MusicResult<ProfileBundle> {
        val artist = getArtistProfile(uid)
        if (artist != null) {
            val songs = (getArtistTopSongs(uid) as? BackendResult.Success)?.data.orEmpty()
            val albums = (getArtistAlbums(uid) as? BackendResult.Success)?.data.orEmpty()
            return BackendResult.Success(
                ProfileBundle(
                    uid = uid,
                    isArtist = true,
                    nickname = artist.name,
                    avatarUrl = artist.avatarUrl,
                    signature = artist.briefDesc ?: artist.alias.joinToString(" / ").ifBlank { null },
                    primaryCount = if (artist.albumSize > 0) artist.albumSize else albums.size,
                    follows = 0,
                    followeds = artist.followeds,
                    albums = albums,
                    songs = songs,
                ),
            )
        }

        val profile = runCatching { MusicSourceFromApi.parseUserDetail(api.getUserDetail(uid)) }.getOrNull()
            ?: return BackendResult.Error("没有找到该用户")
        val playlists = (getUserPlaylistsOf(uid) as? BackendResult.Success)?.data.orEmpty()
        return BackendResult.Success(
            ProfileBundle(
                uid = uid,
                isArtist = false,
                nickname = profile.nickname,
                avatarUrl = profile.avatarUrl,
                signature = profile.signature,
                primaryCount = profile.playlistCount,
                follows = profile.follows,
                followeds = profile.followeds,
                playlists = playlists,
            ),
        )
    }

    /** 他人歌单列表（不含「我喜欢的音乐」这类只属于本人的条目）。 */
    suspend fun getUserPlaylistsOf(uid: Long): MusicResult<List<PlaylistSummary>> =
        MusicSourceFromApi.getUserPlaylists(api, uid)

    /** 用户听歌排行；`type` 0=所有时间, 1=最近一周。 */
    suspend fun getUserRecords(uid: Long, type: Int = 0): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.parseUserRecords(api.getUserRecord(uid, type))

    /** 关注 / 粉丝列表。 */
    suspend fun getFollows(uid: Long, limit: Int = 50): MusicResult<List<ArtistSummary>> =
        MusicSourceFromApi.parseUserList(api.getUserFollows(uid, limit = limit))

    suspend fun getFolloweds(uid: Long, limit: Int = 50): MusicResult<List<ArtistSummary>> =
        MusicSourceFromApi.parseUserList(api.getUserFolloweds(uid, limit = limit))

    suspend fun getPlaylistTracks(id: Long, limit: Int = 300, offset: Int = 0): MusicResult<PlaylistTracksPage> =
        MusicSourceFromApi.getPlaylistTracks(api, id, limit, offset)

    suspend fun getCurrentUserPlaylists(): MusicResult<List<PlaylistSummary>> {
        val uid = extractUidFromLoginStatus(api.getLoginStatus())
            ?: return BackendResult.Error("未登录或登录已过期")
        return MusicSourceFromApi.getUserPlaylists(api, uid)
    }

    suspend fun getUserCloud(limit: Int = 200, offset: Int = 0): MusicResult<List<TrackSummary>> =
        MusicSourceFromApi.getUserCloud(api, limit, offset)

    suspend fun getLikeList(): MusicResult<Set<String>> {
        val uid = extractUidFromLoginStatus(api.getLoginStatus())
            ?: return BackendResult.Error("未登录或登录已过期")
        val json = api.getLikeList(uid)
        val array = (json as? JsonObject)?.get("ids") as? JsonArray ?: JsonArray(emptyList())
        return BackendResult.Success(
            array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.toSet()
        )
    }

    suspend fun deletePlaylist(id: Long): Boolean = isApiSuccess(api.deletePlaylist(id))

    suspend fun unsubscribePlaylist(id: Long): Boolean = isApiSuccess(api.subscribePlaylist(id, t = 2))

    /** 收藏 / 取消收藏歌单。 */
    suspend fun subscribePlaylist(id: Long, subscribe: Boolean): Boolean =
        isApiSuccess(api.subscribePlaylist(id, t = if (subscribe) 1 else 2))

    suspend fun addTracksToPlaylist(playlistId: Long, ids: List<String>): Boolean =
        isApiSuccess(api.addTracksToPlaylist(playlistId, ids))

    suspend fun removeTracksFromPlaylist(playlistId: Long, ids: List<String>): Boolean =
        isApiSuccess(api.removeTracksFromPlaylist(playlistId, ids))

    suspend fun likeSong(songId: String, like: Boolean): Boolean = isApiSuccess(api.likeSong(songId, like))

    /** 标记「不感兴趣」（推荐流去重）。 */
    suspend fun dislikeSong(songId: String): Boolean = isApiSuccess(api.dislikeSong(songId))

    /**
     * 新建歌单，返回新歌单 id。
     *
     * ⚠️ 这是**写操作**，不在缓存名单内——每次都直连网络，不存在「建完马上查不到」的缓存窗口。
     */
    suspend fun createPlaylist(name: String): MusicResult<Long> =
        MusicSourceFromApi.parseCreatePlaylist(api.createPlaylist(name))

    /** 歌曲详情信息（信息弹窗用）；[fallback] 提供列表里已知的回退字段。 */
    suspend fun getSongDetailInfo(songId: String, fallback: SongDetailInfo? = null): MusicResult<SongDetailInfo> =
        MusicSourceFromApi.parseSongDetailInfo(api.getSongDetail(listOf(songId)), fallback)

    /**
     * 评论列表。
     *
     * ⚠️ 评论接口不在读透缓存名单内（comment 直通网络），每次调用都打网络 ——
     * 与旧行为一致，别按「有缓存」来设计刷新节奏。
     */
    suspend fun getComments(rawId: String, type: String): MusicResult<List<Comment>> =
        MusicSourceFromApi.parseComments(api.getComments(rawId, type))

    /** 点赞 / 取消点赞评论。 */
    suspend fun likeComment(rawId: String, commentId: Long, type: String, like: Boolean): Boolean =
        isApiSuccess(api.likeComment(rawId, commentId, type, like))

    /** 从云盘删除歌曲（上游 `user/cloud/del`）。 */
    suspend fun deleteUserCloud(songId: String): Boolean =
        isApiSuccess(api.deleteUserCloud(listOf(songId)))

    suspend fun getLoginStatus(): JsonElement = api.getLoginStatus()

    /** Transitional escape hatch for operations not yet migrated to typed repositories. */
    suspend fun raw(block: suspend MusicApiService.() -> JsonElement): JsonElement = api.block()

    private fun isApiSuccess(json: JsonElement): Boolean {
        val code = ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            ?: ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull
        return code == null || code == 200 || code == 0 || code == 201 || code == 301
    }
}

/**
 * 搜索类型（UI 侧语义化常量）。
 *
 * 与上游 `cloudsearch` 的 `type` 取值一致，但 UI 只认这里，不直接 import
 * `core.api.MusicApiMethod`（边界规则见 docs/dev/ARCHITECTURE.md §3.2）。
 */
object SearchType {
    const val SONG = 1
    const val ALBUM = 10
    const val ARTIST = 100
    const val PLAYLIST = 1000
}
