package cp.player.core.music

import cp.player.core.local.LocalSongMetadata

/**
 * 统一音乐源接口（KMP 版）。
 *
 * **这是前端访问音乐数据的唯一抽象**——无论数据来自云音源 Provider 还是本地文件。
 * [MusicBackend] 既实现此接口（云侧），也聚合 [cp.player.core.local.LocalMusicSource]（本地侧），
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
    suspend fun getPlaylistDetail(id: Long): cp.player.core.music.MusicResult<PlaylistDetail>

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
 * 复用 [cp.player.core.BackendResult] 但绑定音乐域语义。
 */
typealias MusicResult<T> = cp.player.core.BackendResult<T>

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
) : cp.player.core.util.JavaSerializable

/**
 * 歌单详情（含完整曲目）。
 */
data class PlaylistDetail(
    val summary: PlaylistSummary,
    val tracks: List<TrackSummary>,
    val description: String?,
) : cp.player.core.util.JavaSerializable

/**
 * 歌单曲目分页结果。
 *
 * [hasMore] 取服务端布尔（`more` / `hasMore`），服务端未给出时默认 false；
 * 调用方可结合 `tracks.size >= limit` 兜底判断。
 */
data class PlaylistTracksPage(
    val tracks: List<TrackSummary>,
    val hasMore: Boolean,
)

/**
 * 歌曲摘要（列表/队列用）。
 *
 * [artists] 是**逐个拆开的**歌手，与 [artist]（拼好的一整串）并存：
 * 合唱曲目点歌手要能进到「对的那个人」的主页，只有一串 "A / B" 是做不到的 ——
 * 整串只能「点哪都进第一个人」，或者干脆整行不可点。
 *
 * 上游只在 `ar` / `artists` **数组**里给 id，所以：
 * - 数组存在 ⇒ [artists] 与 [artist] 同源同序，可逐个跳转；
 * - 数组不存在（上游只给了 `artist` 字符串）⇒ [artists] 为空，调用方退回 [artist] 纯文本；
 * - 数组里某一条**缺 id** ⇒ 该条 `id` 为 `0L`（名字照常参与 [artist] 拼接），
 *   调用方应当把它渲染成**不可点**，而不是塞一个错误的 id 进去。
 */
data class TrackSummary(
    val id: String,
    val name: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val artists: List<ArtistSummary> = emptyList(),
) : cp.player.core.util.JavaSerializable

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
 *
 * ⚠️ **专辑与歌手是两个不同的字段**，不能共用 [playlists]。
 *
 * 收敛前这里只有 `songs` / `playlists` / `artists` 三个字段，而搜索界面在「专辑」页签下
 * 直接把 `playlists` 当专辑渲染 —— 上游 `cloudsearch?type=10` 返回的其实是 `result.albums`，
 * 而 `result.playlists` 在专辑搜索里**根本不存在**，于是「专辑」页签永远空白。
 * 「歌手」页签勉强能出人名，但头像、点击进详情都没有。
 */
data class SearchResult(
    val songs: List<TrackSummary>,
    val playlists: List<PlaylistSummary>,
    val artists: List<ArtistSummary>,
    val albums: List<AlbumSummary> = emptyList(),
)

/**
 * 歌手摘要。
 */
data class ArtistSummary(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
) : cp.player.core.util.JavaSerializable

/**
 * 首页焦点图（`banner`）。
 *
 * [targetType] 是上游给出的跳转语义：`1` = 单曲、`10` = 专辑、`1000` = 歌单、`3000` = 外链。
 * 调用方**必须按它分支**，不能一律当歌单打开 —— 否则点「新歌首发」会拿到一个空歌单。
 * 目前可落地跳转的是 `1`（直接播放）与 `1000`（歌单详情），其余由调用方过滤掉，
 * 宁可少几张图也不要留下点了没反应的死区。
 */
data class BannerItem(
    val id: String,
    val imageUrl: String,
    val title: String,
    val targetType: Int,
    val targetId: String,
)

/**
 * 榜单摘要（`toplist`）。
 *
 * 上游榜单本身就是一种特殊歌单，[id] 可直接交给歌单详情接口读取曲目。
 */
data class RankingSummary(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val updateFrequency: String?,
    val trackCount: Int,
)

/**
 * 专辑摘要（`album/new` 新碟上架 / 搜索专辑 / 歌手专辑）。
 */
data class AlbumSummary(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val artistName: String?,
    val trackCount: Int,
    /** 发行时间（毫秒）。上游字段名 `publishTime`，缺失时为 null。 */
    val publishTimeMs: Long? = null,
    /** 专辑艺术家 id，用于从专辑反跳歌手。上游可能在 `artist.id` / `artists[0].id`。 */
    val artistId: Long? = null,
) : cp.player.core.util.JavaSerializable

/**
 * 专辑详情（`album`）。
 *
 * [tracks] 是专辑的完整曲目；`songs` 既可能挂在 `album.songs` 下，
 * 也可能与 `album` 平级，解析层两种都取。
 */
data class AlbumDetail(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val artistName: String?,
    val artistId: Long?,
    val publishTimeMs: Long?,
    val company: String?,
    val description: String?,
    val tracks: List<TrackSummary>,
) : cp.player.core.util.JavaSerializable

/**
 * 歌手资料（`artist/detail` 的 `data.artist`）。
 *
 * [followeds] 取自 `data.user.followeds`（歌手的粉丝数），歌手对象本身没有这个字段。
 */
data class ArtistProfile(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
    val briefDesc: String?,
    val alias: List<String>,
    val albumSize: Int,
    val musicSize: Int,
    val followeds: Int,
) : cp.player.core.util.JavaSerializable

/**
 * 用户主页 / 歌手主页的**统一**数据包。
 *
 * 上游 `user/detail` 与 `artist/detail` 用的是同一段数字 id 空间，但指向不同实体，
 * 且**同一个 id 在两边都可能返回数据**。因此调用方只能「先试歌手、拿到 `data.artist`
 * 才认」，否则按普通用户处理 —— 这也是旧项目 `fetchOtherUserProfile` 的做法。
 */
data class ProfileBundle(
    val uid: Long,
    val isArtist: Boolean,
    val nickname: String,
    val avatarUrl: String?,
    val signature: String?,
    /** 歌手：专辑数；用户：歌单数。 */
    val primaryCount: Int,
    /** 关注数（歌手恒为 0）。 */
    val follows: Int,
    /** 粉丝数。 */
    val followeds: Int,
    val playlists: List<PlaylistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val songs: List<TrackSummary> = emptyList(),
) : cp.player.core.util.JavaSerializable