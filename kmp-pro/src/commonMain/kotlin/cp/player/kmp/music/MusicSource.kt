package cp.player.kmp.music

import cp.player.kmp.local.LocalSongMetadata

/**
 * 统一音乐源接口（KMP 版）。
 *
 * **这是前端访问音乐数据的唯一抽象**——无论数据来自云音源 Provider 还是本地文件。
 * [MusicBackend] 既实现此接口（云侧），也聚合 [cp.player.kmp.local.LocalMusicSource]（本地侧），
 * 前端只需调用统一方法，后端自动路由到正确数据源。
 *
 * ### 与旧项目关系
 * 对标旧项目 `MusicApiService`，但在其之上：
 * 1. 返回 [MusicContent] 而非裸 JsonElement，由后端完成 JSON→领域模型解析
 * 2. 统一本地/云端数据源
 * 3. 内置缓存（通过 [MusicBackend] 代理 [CachedMusicApiService]）
 *
 * ### 当前实现状态
 * 云端方法最初沿用 [MusicApiService] 的 JsonElement 返回，
 * 后续将逐步替换为强类型的 [MusicContent] 封装。
 */
interface MusicSource {

    /**
     * 获取歌单详情。
     * @param id 歌单 ID
     * @return 歌单内容
     */
    suspend fun getPlaylistDetail(id: Long): cp.player.kmp.music.MusicResult<PlaylistDetail>

    /**
     * 获取推荐歌单列表。
     * @param limit 数量上限
     */
    suspend fun getRecommendedPlaylists(limit: Int = 30): MusicResult<List<PlaylistSummary>>

    /**
     * 获取每日推荐歌曲。
     */
    suspend fun getRecommendedSongs(): MusicResult<List<TrackSummary>>

    /**
     * 搜索。
     * @param keywords 关键词
     * @param type 1=单曲, 1000=歌单, 100=歌手, 10=专辑
     */
    suspend fun search(keywords: String, type: Int = 1): MusicResult<SearchResult>

    /**
     * 获取当前用户的歌单列表。
     * @param uid 用户 ID
     */
    suspend fun getUserPlaylists(uid: Long): MusicResult<List<PlaylistSummary>>

    /**
     * 获取歌曲播放 URL。
     * @param songId 歌曲 ID
     * @param level 音质等级
     */
    suspend fun getSongUrl(songId: String, level: String = "standard"): MusicResult<SongUrl>

    //- ...更多方法将在后续增量补充（歌手/专辑/评论/社交等）
}

/**
 * 统一音乐结果（成功 / 错误 / 不支持）。
 * 复用 [cp.player.kmp.BackendResult] 但绑定音乐域语义。
 */
typealias MusicResult<T> = cp.player.kmp.BackendResult<T>

// ============ 音乐领域模型（最小子集，后续扩展） ============

/**
 * 歌单摘要（列表/网格用）。
 */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val creatorName: String?,
)

/**
 * 歌单详情（含完整曲目）。
 */
data class PlaylistDetail(
    val summary: PlaylistSummary,
    val tracks: List<TrackSummary>,
    val description: String?,
)

/**
 * 歌曲摘要（列表/队列用）。
 */
data class TrackSummary(
    val id: String,
    val name: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
)

/**
 * 歌曲播放地址。
 */
data class SongUrl(
    val url: String,
    val level: String,
    val sizeBytes: Long?,
    val expireAt: Long?,
    val cookie: String? = null,
)

/**
 * 搜索结果。
 */
data class SearchResult(
    val songs: List<TrackSummary>,
    val playlists: List<PlaylistSummary>,
    val artists: List<ArtistSummary>,
)

/**
 * 歌手摘要。
 */
data class ArtistSummary(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
)