package cp.player.core.music

import cp.player.core.BackendResult
import cp.player.core.api.ApiResponseCodes
import cp.player.core.api.MusicApiService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * JSON → 强类型模型的解析桥。
 *
 * 把 [MusicApiService] 返回的 [JsonElement] 按字段兼容性提取为
 * [MusicResult] 包裹的音乐领域模型。设计上：
 * - 优先识别兼容字段名（跨音源 Provider 习惯略有不同），找不到时回退到 null/Empty；
 * - 解析或服务端 code 异常时返回 [BackendResult.Error]；
 * - 不支持的 API 返回 [BackendResult.Unsupported]。
 *
 * 当前覆盖：推荐歌单 / 日推 / 搜索 / 歌单详情 / 用户歌单。
 */
object MusicSourceFromApi {

    /**
     * 焦点图里**应用内能落地跳转**的 `targetType`：`1` = 单曲（直接播放），
     * `1000` = 歌单（进歌单详情）。其余（专辑 / 外链）暂没有对应页面，
     * 展示出来只会变成点了没反应的死区，因此在解析阶段就过滤掉。
     */
    private val BANNER_SUPPORTED_TARGETS = setOf(1, 1000)

    // ============ code 判定（跨 Provider） ============

    private fun codeOf(json: JsonElement): Int? =
        ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            ?: ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull

    /** 码表统一在 [ApiResponseCodes]，本文件只负责「拿哪个字段当 code」。 */
    private fun isSuccess(json: JsonElement): Boolean = ApiResponseCodes.isSuccess(codeOf(json))

    /**
     * 统一封装：成功 → 解析为 [transform] 结果；
     * code 表示不支持 → [BackendResult.Unsupported]；
     * 其它 → [BackendResult.Error]。
     */
    private inline fun <T> JsonElement.toMusicResult(
        unsupportedCodes: Set<Int> = setOf(-1, 501, 404),
        transform: JsonObject.() -> T,
    ): MusicResult<T> {
        if (this !is JsonObject) return BackendResult.Error("响应格式异常（非 JsonObject）")
        if (!isSuccess(this)) {
            val code = codeOf(this)
            if (code != null && code in unsupportedCodes) {
                return BackendResult.Unsupported("该音源不支持此功能（code=$code）")
            }
            val msg = (this["msg"] as? JsonPrimitive)?.contentOrNull ?: (this["message"] as? JsonPrimitive)?.contentOrNull
            return BackendResult.Error(msg ?: "API 返回失败（code=$code）", code)
        }
        return runCatching { BackendResult.Success(transform()) }
            .getOrElse { BackendResult.Error("数据解析失败: ${it.message}", cause = it) }
    }

    // ============ 评论 ============

    private val commentJsonDecoder = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * 解析评论响应为 [Comment] 列表。
     *
     * 上游形状：`{comments, hotComments}` 或包一层 `{data: {comments, hotComments}}`；
     * 评论区优先取 `comments`，为空再取 `hotComments`。评论接口不在读透缓存名单内，
     * 调用即直连网络（与旧行为一致）。
     */
    fun parseComments(json: JsonElement): MusicResult<List<Comment>> {
        if (json !is JsonObject) return BackendResult.Error("响应格式异常（非 JsonObject）")
        return runCatching {
            val dto = commentJsonDecoder.decodeFromJsonElement<CommentResponseDto>(json)
            val dtos = dto.data?.comments
                ?: dto.comments
                ?: dto.data?.hotComments
                ?: dto.hotComments
                ?: emptyList()
            BackendResult.Success(dtos.map { d ->
                val userDto = d.user ?: d.author
                Comment(
                    id = d.commentId ?: d.id ?: 0L,
                    content = d.content ?: "",
                    user = userDto?.nickname ?: "Unknown",
                    avatar = userDto?.avatarUrl ?: "",
                    time = d.timeStr ?: d.time?.toString() ?: "",
                    likedCount = d.likedCount ?: 0,
                    liked = d.liked ?: false,
                )
            })
        }.getOrElse { BackendResult.Error("评论解析失败: ${it.message}", cause = it) }
    }

    // ============ 推荐歌单 ============

    /** 从 `recommend` / `result` 数组解析推荐歌单摘要列表。 */
    fun parseRecommendedPlaylists(json: JsonElement): MusicResult<List<PlaylistSummary>> {
        return json.toMusicResult {
            val array = (this["recommend"] ?: this["result"] ?: this["playlists"] ?: (this["data"] as? JsonObject)?.get("playlists")) as? JsonArray
                ?: JsonArray(emptyList())
            array.mapNotNull { (it as? JsonObject)?.toPlaylistSummary()?.takeIf { playlist -> playlist.id != 0L && playlist.name.isNotBlank() } }
        }
    }

    // ============ 推荐歌曲（日推） ============

    /** 从 `data.dailySongs` / `data` / `recommend` 数组解析推荐歌曲。 */
    fun parseRecommendedSongs(json: JsonElement): MusicResult<List<TrackSummary>> {
        return json.toMusicResult {
            val root = this["data"] as? JsonObject ?: this
            val array = (root["dailySongs"] ?: root["songs"] ?: this["recommend"] ?: this["result"]) as? JsonArray
                ?: JsonArray(emptyList())
            array.mapNotNull { (it as? JsonObject)?.toTrackSummary()?.takeIf { track -> track.id.isNotBlank() } }
        }
    }

    // ============ 搜索 ============

    /**
     * 从 `result` 子对象解析搜索结果。
     *
     * 四个数组**按类型各自解析**：`type=10`（专辑）只填 `result.albums`，
     * `type=100`（歌手）只填 `result.artists`，其余为空。
     * 调用方必须按 `result` 里**对应的那个数组**渲染，不能拿别的数组顶替 ——
     * 收敛前「专辑」页签就是拿 `playlists` 渲染的，而专辑搜索里没有这个数组，页面恒空。
     */
    fun parseSearchSongs(json: JsonElement, type: Int = 1): MusicResult<SearchResult> {
        return json.toMusicResult {
            val result = this["result"] as? JsonObject ?: this
            val songs = (result["songs"] as? JsonArray ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.toTrackSummary()?.takeIf { t -> t.id.isNotBlank() } }
            val playlists = (result["playlists"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject.toPlaylistSummary() }
                .filter { it.id != 0L }
            val artists = (result["artists"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject.toArtistSummary() }
                .filter { it.id != 0L }
            // 专辑搜索的关键修复点：上游字段是 `albums`。旧实现只认 `playlists`，
            // 于是「专辑」页签永远拿到 0 条。
            val albums = parseAlbumArray(result, "albums")
            SearchResult(songs = songs, playlists = playlists, artists = artists, albums = albums)
        }
    }

    // ============ 歌单详情 ============

    fun parsePlaylistDetail(json: JsonElement): MusicResult<PlaylistDetail> {
        return json.toMusicResult {
            val playlist = this["playlist"] as? JsonObject ?: this["data"] as? JsonObject ?: this
            val summary = playlist.toPlaylistSummary()
            val tracks = (playlist["tracks"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject.toTrackSummary() }
            val desc = (playlist["description"] as? JsonPrimitive)?.contentOrNull
            PlaylistDetail(summary = summary, tracks = tracks, description = desc)
        }
    }

    // ============ 歌单曲目（分页） ============

    /**
     * 解析歌单全部歌曲分页接口（playlist/track/all）。
     *
     * 曲目数组兼容 `songs` / `tracks` 字段；hasMore 优先取 `more`，
     * 其次 `hasMore`，服务端未给出时默认 false（调用方可用
     * `tracks.size >= limit` 兜底）。
     */
    fun parsePlaylistTracks(json: JsonElement): MusicResult<PlaylistTracksPage> {
        return json.toMusicResult {
            val array = (this["songs"] as? JsonArray)
                ?: (this["tracks"] as? JsonArray)
                ?: JsonArray(emptyList())
            val tracks = array.mapNotNull { el ->
                (el as? JsonObject)?.toTrackSummary()?.takeIf { it.id.isNotBlank() }
            }
            val hasMore = ((this["more"] as? JsonPrimitive)?.booleanOrNull)
                ?: (this["hasMore"] as? JsonPrimitive)?.booleanOrNull
                ?: false
            PlaylistTracksPage(tracks = tracks, hasMore = hasMore)
        }
    }

    // ============ 用户歌单 ============

    fun parseUserPlaylists(json: JsonElement): MusicResult<List<PlaylistSummary>> {
        return json.toMusicResult {
            val array = (this["playlist"] ?: this["playlists"] ?: this["data"]) as? JsonArray
                ?: JsonArray(emptyList())
            array.map { it.jsonObject.toPlaylistSummary() }
        }
    }

    // ============ 歌单写操作 ============

    /**
     * 从 `playlist/create` 响应中提取新歌单 id。
     *
     * 上游有两种形状：id 直接在根层（`{code, id}`），或包在 `playlist` 对象里
     * （`{code, playlist: {id, ...}}`），两种都取。
     */
    fun parseCreatePlaylist(json: JsonElement): MusicResult<Long> {
        return json.toMusicResult {
            ((this["id"] ?: (this["playlist"] as? JsonObject)?.get("id")) as? JsonPrimitive)?.longOrNull
                ?: error("响应中没有新歌单 id")
        }
    }

    // ============ 歌曲详情（信息弹窗） ============

    /**
     * 解析 `song/detail` 的 `songs[0]` + `privileges[0]` 为 [SongDetailInfo]。
     *
     * `name` / `artist` / `album` / `durationMs` 缺失时回退到调用方已知的
     * [fallback] 字段（列表里通常已经有这份信息）；可选字段缺失时为 null。
     */
    fun parseSongDetailInfo(json: JsonElement, fallback: SongDetailInfo? = null): MusicResult<SongDetailInfo> {
        return json.toMusicResult {
            val songs = this["songs"] as? JsonArray
            val first = songs?.firstOrNull() as? JsonObject
                ?: error("响应中没有 songs[0]")
            val privilege = (this["privileges"] as? JsonArray)?.firstOrNull() as? JsonObject
            SongDetailInfo(
                songId = fallback?.songId ?: "",
                name = (first["name"] as? JsonPrimitive)?.contentOrNull ?: fallback?.name ?: "",
                artist = (first["ar"] as? JsonArray)
                    ?.mapNotNull {
                        ((it as? JsonObject)?.get("name") as? JsonPrimitive)
                            ?.contentOrNull?.takeIf(String::isNotBlank)
                    }
                    ?.joinToString("/") ?: fallback?.artist ?: "",
                album = ((first["al"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: fallback?.album ?: "",
                durationMs = (first["dt"] as? JsonPrimitive)?.longOrNull ?: fallback?.durationMs ?: 0L,
                publishTimeMs = (first["publishTime"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                commentCount = (first["commentCount"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                mvId = (first["mv"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                maxBitrate = (privilege?.get("maxbr") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
                fee = (privilege?.get("fee") as? JsonPrimitive)?.intOrNull,
            )
        }
    }

    // ============ 云盘歌曲 ============
    /**
     * 解析云盘歌曲列表（user/cloud）。
     *
     * 兼容两种返回形态：
     * - `{ data: [{ songId, simpleSong/song: {...} }] }`（官方云盘）
     * - `{ data: [...track...] }`（直接曲目对象）
     */
    fun parseCloudSongs(json: JsonElement): MusicResult<List<TrackSummary>> {
        return json.toMusicResult {
            val array = (this["data"] as? JsonArray)
                ?: ((this["data"] as? JsonObject)?.get("data") as? JsonArray)
                ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val inner = (obj["song"] as? JsonObject)
                    ?: (obj["simpleSong"] as? JsonObject)
                    ?: obj
                inner.toTrackSummary().takeIf { it.id.isNotBlank() }
            }
        }
    }

    // ============ 私人 FM / 心动模式 ============

    /**
     * 解析私人 FM（`personal_fm`）与心动模式（`playmode/intelligence/list`）的歌曲列表。
     *
     * 兼容三种条目形态（与旧项目 `JsonUtils.parseSong` 对齐）：
     * - 心动模式：`{ data: [{ alg: "...", songInfo: { id, name, ... } }] }` —— **必须解包
     *   `songInfo`**，否则每个条目都取不到曲目字段，整页永远是「歌单暂无歌曲」；
     * - 私人 FM / 新歌速递：`{ data: [{ id, name, ar, al, dt }] }` —— 条目本身就是曲目；
     * - 部分音源：`{ songs: [...] }`。
     */
    fun parseFmSongs(json: JsonElement): MusicResult<List<TrackSummary>> {
        return json.toMusicResult {
            val array = (this["data"] as? JsonArray)
                ?: (this["songs"] as? JsonArray)
                ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = (el as? JsonObject)?.let { (it["songInfo"] as? JsonObject) ?: it }
                    ?: return@mapNotNull null
                obj.toTrackSummary().takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }
        }
    }

    // ============ 首页焦点图 ============

    /**
     * 解析首页焦点图（`banner`）：`{ banners: [...] }`。
     *
     * 上游每张图都带 `targetType`，但**只有单曲(1)与歌单(1000)在应用内有落地点**。
     * 这里刻意把其余类型过滤掉：留着一张点了没反应的图，比少一张图更糟。
     * `targetId` 在上游可能是数字也可能是字符串（`encodeId`），两种都取。
     */
    fun parseBanners(json: JsonElement): MusicResult<List<BannerItem>> {
        return json.toMusicResult {
            val array = (this["banners"] ?: this["data"]) as? JsonArray ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val image = (obj["imageUrl"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["picUrl"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val targetId = (obj["targetId"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["encodeId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                BannerItem(
                    id = (obj["adid"] as? JsonPrimitive)?.contentOrNull ?: targetId,
                    imageUrl = image,
                    title = (obj["title"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["typeTitle"] as? JsonPrimitive)?.contentOrNull
                        ?: "",
                    targetType = (obj["targetType"] as? JsonPrimitive)?.intOrNull ?: 0,
                    targetId = targetId,
                )
            }.filter { it.imageUrl.isNotBlank() && it.targetId.isNotBlank() && it.targetType in BANNER_SUPPORTED_TARGETS }
        }
    }

    // ============ 排行榜 ============

    /** 解析榜单列表（`toplist`）：`{ list: [...] }`。 */
    fun parseRankings(json: JsonElement): MusicResult<List<RankingSummary>> {
        return json.toMusicResult {
            val array = (this["list"] ?: this["data"]) as? JsonArray ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                RankingSummary(
                    id = id,
                    name = name,
                    coverUrl = (obj["coverImgUrl"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["picUrl"] as? JsonPrimitive)?.contentOrNull,
                    updateFrequency = (obj["updateFrequency"] as? JsonPrimitive)?.contentOrNull,
                    trackCount = (obj["trackCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                )
            }.filter { it.id != 0L && it.name.isNotBlank() }
        }
    }

    // ============ 新碟上架 / 歌手专辑 / 搜索专辑 ============

    /**
     * 解析新碟上架（`album/new`）：`{ albums: [...] }`。曲目数上游叫 `size`。
     */
    fun parseAlbums(json: JsonElement): MusicResult<List<AlbumSummary>> {
        return json.toMusicResult { parseAlbumArray(this, "albums", "data") }
    }

    /**
     * 解析歌手专辑（`artist/album`）：`{ hotAlbums: [...] }`。
     *
     * 与 [parseAlbums] 走**同一份**字段映射 —— 上游三处（新碟 / 搜索 / 歌手专辑）
     * 的专辑对象形状相同，各写一份迟早分叉。
     */
    fun parseArtistAlbums(json: JsonElement): MusicResult<List<AlbumSummary>> {
        return json.toMusicResult { parseAlbumArray(this, "hotAlbums") }
    }

    /**
     * 从 [root] 里按候选键取专辑数组并映射。
     *
     * 键名按出现频率排列，全部命不中时返回空列表（而不是报错）：专辑列表为空
     * 是「这位歌手没有专辑」的正常状态，不该让整页变成错误态。
     */
    private fun parseAlbumArray(root: JsonObject, vararg keys: String): List<AlbumSummary> {
        val array = keys.firstNotNullOfOrNull { root[it] as? JsonArray } ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            // `artist` 是单对象（新碟 / 歌手专辑），`artists` 是数组（专辑详情 / 部分搜索返回）。
            val artist = obj["artist"] as? JsonObject
            val firstArtist = (obj["artists"] as? JsonArray)?.firstOrNull() as? JsonObject
            AlbumSummary(
                id = id,
                name = name,
                coverUrl = (obj["picUrl"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["coverImgUrl"] as? JsonPrimitive)?.contentOrNull,
                artistName = (artist?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: (firstArtist?.get("name") as? JsonPrimitive)?.contentOrNull,
                trackCount = (obj["size"] as? JsonPrimitive)?.intOrNull
                    ?: (obj["trackCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                publishTimeMs = (obj["publishTime"] as? JsonPrimitive)?.longOrNull,
                artistId = (artist?.get("id") as? JsonPrimitive)?.longOrNull
                    ?: (firstArtist?.get("id") as? JsonPrimitive)?.longOrNull,
            )
        }.filter { it.id != 0L && it.name.isNotBlank() }
    }

    // ============ 专辑详情 ============

    /**
     * 解析专辑详情（`album`）：`{ album: { ... songs: [...] } }`。
     *
     * 曲目数组优先取 `album.songs`，回退到与 `album` 平级的 `songs`
     * （部分 Provider 会把歌曲提到顶层）。
     */
    fun parseAlbumDetail(json: JsonElement): MusicResult<AlbumDetail> {
        return json.toMusicResult {
            val album = this["album"] as? JsonObject ?: this["data"] as? JsonObject ?: this
            val artist = album["artist"] as? JsonObject
            val rawTracks = (album["songs"] as? JsonArray) ?: (this["songs"] as? JsonArray)
            val tracks = (rawTracks ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.toTrackSummary()?.takeIf { t -> t.id.isNotBlank() } }
            AlbumDetail(
                id = (album["id"] as? JsonPrimitive)?.longOrNull ?: 0L,
                name = (album["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                coverUrl = (album["picUrl"] as? JsonPrimitive)?.contentOrNull
                    ?: (album["blurPicUrl"] as? JsonPrimitive)?.contentOrNull,
                artistName = (artist?.get("name") as? JsonPrimitive)?.contentOrNull,
                artistId = (artist?.get("id") as? JsonPrimitive)?.longOrNull,
                publishTimeMs = (album["publishTime"] as? JsonPrimitive)?.longOrNull,
                company = (album["company"] as? JsonPrimitive)?.contentOrNull,
                description = (album["description"] as? JsonPrimitive)?.contentOrNull
                    ?: (album["briefDesc"] as? JsonPrimitive)?.contentOrNull,
                tracks = tracks,
            )
        }
    }

    // ============ 歌手资料 ============

    /**
     * 解析歌手资料（`artist/detail` 的 `data.artist`）。
     *
     * **返回 null 表示「这个 id 不是歌手」**，调用方据此回落到普通用户。
     * 刻意不用 [MusicResult]：`code=200` 但 `data.artist` 缺失是**正常分支**，
     * 不是错误 —— 把正常分支塞进 Error 会让「用户主页」永远显示成加载失败。
     */
    fun parseArtistProfile(json: JsonElement): ArtistProfile? {
        val root = json as? JsonObject ?: return null
        val data = (root["data"] as? JsonObject) ?: root
        val artist = data["artist"] as? JsonObject ?: return null
        val id = (artist["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val name = (artist["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val alias = (artist["alias"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val user = data["user"] as? JsonObject
        return ArtistProfile(
            id = id,
            name = name,
            avatarUrl = (artist["cover"] as? JsonPrimitive)?.contentOrNull
                ?: (artist["avatar"] as? JsonPrimitive)?.contentOrNull
                ?: (artist["picUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (artist["img1v1Url"] as? JsonPrimitive)?.contentOrNull,
            briefDesc = (artist["briefDesc"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() },
            alias = alias,
            albumSize = (artist["albumSize"] as? JsonPrimitive)?.intOrNull ?: 0,
            musicSize = (artist["musicSize"] as? JsonPrimitive)?.intOrNull ?: 0,
            followeds = (user?.get("followeds") as? JsonPrimitive)?.intOrNull
                ?: (user?.get("followedUsers") as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * 解析歌手全部歌曲 / 热门 50 首（`artist/songs`、`artist/top/song`）：根键 `songs`。
     *
     * 复用 [parsePlaylistTracks] 的曲目映射 —— 上游这三个端点的曲目对象形状一致，
     * 再写一份 `songs.map { toTrackSummary() }` 只会多一份会分叉的代码。
     */
    fun parseArtistSongs(json: JsonElement): MusicResult<List<TrackSummary>> =
        when (val page: MusicResult<PlaylistTracksPage> = parsePlaylistTracks(json)) {
            is BackendResult.Success -> BackendResult.Success(page.data.tracks)
            is BackendResult.Error -> BackendResult.Error(page.message)
            is BackendResult.Unsupported -> BackendResult.Unsupported(page.message)
        }

    // ============ 用户资料 ============

    /**
     * 解析用户资料（`user/detail`）：根键 `profile`。
     *
     * 返回 null 表示响应里没有 `profile`（未登录 / id 无效）。
     */
    fun parseUserDetail(json: JsonElement): cp.player.core.model.UserProfile? {
        val root = json as? JsonObject ?: return null
        val profile = (root["profile"] as? JsonObject)
            ?: (root["data"] as? JsonObject)?.get("profile") as? JsonObject
            ?: return null
        val uid = (profile["userId"] as? JsonPrimitive)?.longOrNull
            ?: (profile["id"] as? JsonPrimitive)?.longOrNull
            ?: return null
        return cp.player.core.model.UserProfile(
            userId = uid,
            nickname = (profile["nickname"] as? JsonPrimitive)?.contentOrNull ?: "",
            avatarUrl = (profile["avatarUrl"] as? JsonPrimitive)?.contentOrNull ?: "",
            signature = (profile["signature"] as? JsonPrimitive)?.contentOrNull,
            gender = (profile["gender"] as? JsonPrimitive)?.intOrNull ?: 0,
            province = (profile["province"] as? JsonPrimitive)?.intOrNull ?: 0,
            city = (profile["city"] as? JsonPrimitive)?.intOrNull ?: 0,
            birthday = (profile["birthday"] as? JsonPrimitive)?.longOrNull ?: 0L,
            followed = (profile["followed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            follows = (profile["follows"] as? JsonPrimitive)?.intOrNull ?: 0,
            followeds = (profile["followeds"] as? JsonPrimitive)?.intOrNull ?: 0,
            eventCount = (profile["eventCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            playlistCount = (profile["playlistCount"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * 解析用户听歌排行（`user/record`）。
     *
     * 条目形如 `{ playCount, score, song: { ... } }` —— **歌曲本体在 `song` 里**，
     * 直接按曲目对象解析会得到一个 id 为空的空壳。
     * `type=0` 用 `allData`，`type=1` 用 `weekData`，两个键都认。
     */
    fun parseUserRecords(json: JsonElement): MusicResult<List<TrackSummary>> {
        return json.toMusicResult {
            val array = (this["allData"] as? JsonArray)
                ?: (this["weekData"] as? JsonArray)
                ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val song = (obj["song"] as? JsonObject) ?: return@mapNotNull null
                song.toTrackSummary().takeIf { it.id.isNotBlank() }
            }
        }
    }

    /**
     * 解析关注 / 粉丝列表（`user/follows`、`user/followeds`）。
     *
     * 返回 [ArtistSummary] 而不是新建模型：这两处渲染的就是「圆头像 + 昵称」，
     * 与歌手列表项完全同构（`id` 装 `userId`）。
     */
    fun parseUserList(json: JsonElement): MusicResult<List<ArtistSummary>> {
        return json.toMusicResult {
            val array = (this["follow"] as? JsonArray)
                ?: (this["followeds"] as? JsonArray)
                ?: (this["data"] as? JsonArray)
                ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                // 上游在这两个端点用 `userId`；某些 Provider 会退化成 `id`。
                val id = (obj["userId"] as? JsonPrimitive)?.longOrNull
                    ?: (obj["id"] as? JsonPrimitive)?.longOrNull
                    ?: return@mapNotNull null
                val name = (obj["nickname"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                ArtistSummary(
                    id = id,
                    name = name,
                    avatarUrl = (obj["avatarUrl"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["picUrl"] as? JsonPrimitive)?.contentOrNull,
                )
            }.filter { it.id != 0L && it.name.isNotBlank() }
        }
    }

    // ============ 新歌速递 / 热门歌手 ============

    /** 解析新歌速递（`top/song`）：曲目直接挂在 `data` 数组下。 */
    fun parseTopSongs(json: JsonElement): MusicResult<List<TrackSummary>> = parseFmSongs(json)

    /**
     * 解析热门歌手（`top/artists`）：`{ artists: [...] }`。
     *
     * ⚠️ 这里**优先取 `picUrl`**，与 [parseSearchSongs] 走的 `toArtistSummary()` 相反：
     * 搜索接口的 `img1v1Url` 是歌手真实方图，而热门歌手列表里它常常是上游的默认占位头像，
     * 取错了整排人会长得一模一样。
     */
    fun parseTopArtists(json: JsonElement): MusicResult<List<ArtistSummary>> {
        return json.toMusicResult {
            val array = (this["artists"] ?: this["data"]) as? JsonArray ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                ArtistSummary(
                    id = id,
                    name = name,
                    avatarUrl = (obj["picUrl"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["img1v1Url"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["avatarUrl"] as? JsonPrimitive)?.contentOrNull,
                )
            }.filter { it.id != 0L && it.name.isNotBlank() }
        }
    }

    // ============ 单元解析扩展 ============

    private fun JsonObject.toPlaylistSummary(): PlaylistSummary {
        val creator = (this["creator"] as? JsonObject)
        return PlaylistSummary(
            id = (this["id"] as? JsonPrimitive)?.longOrNull ?: 0L,
            name = (this["name"] as? JsonPrimitive)?.contentOrNull ?: "",
            coverUrl = (this["picUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (this["coverImgUrl"] as? JsonPrimitive)?.contentOrNull,
            trackCount = (this["trackCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            creatorName = (creator?.get("nickname") as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun JsonObject.toTrackSummary(): TrackSummary = trackSummaryOf(this, rawTrackId(this))

    private fun JsonObject.toArtistSummary(): ArtistSummary {
        return ArtistSummary(
            id = (this["id"] as? JsonPrimitive)?.longOrNull ?: 0L,
            name = (this["name"] as? JsonPrimitive)?.contentOrNull ?: "",
            avatarUrl = (this["img1v1Url"] as? JsonPrimitive)?.contentOrNull
                ?: (this["picUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (this["avatarUrl"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    // ============ 便捷调用封装 ============

    /** 用 [api] 调用 + 直接解析的封装。 */
    suspend fun getPlaylistDetail(api: MusicApiService, id: Long): MusicResult<PlaylistDetail> =
        parsePlaylistDetail(api.getPlaylistDetail(id))

    suspend fun getPlaylistTracks(api: MusicApiService, id: Long, limit: Int = 300, offset: Int = 0): MusicResult<PlaylistTracksPage> =
        parsePlaylistTracks(api.getPlaylistTracks(id, limit, offset))

    suspend fun getRecommendedPlaylists(api: MusicApiService, limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        parseRecommendedPlaylists(api.getRecommendedPlaylists())

    suspend fun search(api: MusicApiService, keywords: String, type: Int = 1): MusicResult<SearchResult> =
        parseSearchSongs(api.search(keywords, type), type)

    suspend fun getUserPlaylists(api: MusicApiService, uid: Long): MusicResult<List<PlaylistSummary>> =
        parseUserPlaylists(api.getUserPlaylists(uid))

    suspend fun getUserCloud(api: MusicApiService, limit: Int = 200, offset: Int = 0): MusicResult<List<TrackSummary>> =
        parseCloudSongs(api.getUserCloud(limit, offset))

    suspend fun getPersonalFm(api: MusicApiService): MusicResult<List<TrackSummary>> =
        parseFmSongs(api.getPersonalFm())

    suspend fun getBanners(api: MusicApiService): MusicResult<List<BannerItem>> =
        parseBanners(api.getBanner())

    suspend fun getRankings(api: MusicApiService): MusicResult<List<RankingSummary>> =
        parseRankings(api.getToplist())

    suspend fun getNewAlbums(api: MusicApiService, area: String = "ALL", limit: Int = 30): MusicResult<List<AlbumSummary>> =
        parseAlbums(api.getTopAlbums(area = area, limit = limit))

    suspend fun getTopSongs(api: MusicApiService, type: Int = 0): MusicResult<List<TrackSummary>> =
        parseTopSongs(api.getTopSongs(type = type))

    suspend fun getHotArtists(api: MusicApiService, limit: Int = 30): MusicResult<List<ArtistSummary>> =
        parseTopArtists(api.getTopArtists(limit = limit))

    /** 专辑详情。 */
    suspend fun getAlbumDetail(api: MusicApiService, id: Long): MusicResult<AlbumDetail> =
        parseAlbumDetail(api.getAlbumDetail(id))

    /** 歌手热门歌曲（`artist/top/song`，恒返回最多 50 首）。 */
    suspend fun getArtistTopSongs(api: MusicApiService, id: Long): MusicResult<List<TrackSummary>> =
        parseArtistSongs(api.getArtistTopSong(id))

    /** 歌手歌曲全量（按热度，`artist/songs`）。 */
    suspend fun getArtistSongs(api: MusicApiService, id: Long, limit: Int = 100): MusicResult<List<TrackSummary>> =
        parseArtistSongs(api.getArtistSongs(id, limit))

    /** 歌手专辑。 */
    suspend fun getArtistAlbums(api: MusicApiService, id: Long, limit: Int = 50): MusicResult<List<AlbumSummary>> =
        parseArtistAlbums(api.getArtistAlbums(id, limit))

    suspend fun getHighQualityPlaylists(api: MusicApiService, cat: String = "全部", limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        parseRecommendedPlaylists(api.getHighqualityPlaylists(cat = cat, limit = limit))
}

// ============ 评论 DTO（仅供 [MusicSourceFromApi.parseComments] 使用） ============

@Serializable
private data class CommentResponseDto(
    val data: CommentDataDto? = null,
    val comments: List<CommentDto>? = null,
    val hotComments: List<CommentDto>? = null,
)

@Serializable
private data class CommentDataDto(
    val comments: List<CommentDto>? = null,
    val hotComments: List<CommentDto>? = null,
)

@Serializable
private data class CommentDto(
    val commentId: Long? = null,
    val id: Long? = null,
    val content: String? = null,
    val timeStr: String? = null,
    val time: Long? = null,
    val likedCount: Int? = null,
    val liked: Boolean? = null,
    val user: CommentUserDto? = null,
    val author: CommentUserDto? = null,
)

@Serializable
private data class CommentUserDto(
    val nickname: String? = null,
    val avatarUrl: String? = null,
)