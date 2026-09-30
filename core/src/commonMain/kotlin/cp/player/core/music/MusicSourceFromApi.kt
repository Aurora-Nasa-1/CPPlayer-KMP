package cp.player.core.music

import cp.player.core.BackendResult
import cp.player.core.api.ApiResponseCodes
import cp.player.core.api.MusicApiService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

    /** 从 `result` 子对象解析搜索结果（单曲类型 type=1）。 */
    fun parseSearchSongs(json: JsonElement, type: Int = 1): MusicResult<SearchResult> {
        return json.toMusicResult {
            val result = this["result"] as? JsonObject ?: this
            val songs = (result["songs"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject.toTrackSummary() }
            val playlists = (result["playlists"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject.toPlaylistSummary() }
            val artists = (result["artists"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject.toArtistSummary() }
            SearchResult(songs = songs, playlists = playlists, artists = artists)
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

    // ============ 私人 FM ============

    /** 解析私人 FM 歌曲列表（personal/fm）：`{ data: [...track...] }`。 */
    fun parseFmSongs(json: JsonElement): MusicResult<List<TrackSummary>> {
        return json.toMusicResult {
            val array = (this["data"] as? JsonArray)
                ?: (this["songs"] as? JsonArray)
                ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                (el as? JsonObject)?.toTrackSummary()?.takeIf { it.id.isNotBlank() }
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

    // ============ 新碟上架 ============

    /** 解析新碟上架（`album/new`）：`{ albums: [...] }`。曲目数上游叫 `size`。 */
    fun parseAlbums(json: JsonElement): MusicResult<List<AlbumSummary>> {
        return json.toMusicResult {
            val array = (this["albums"] ?: this["data"]) as? JsonArray ?: JsonArray(emptyList())
            array.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val artist = obj["artist"] as? JsonObject
                AlbumSummary(
                    id = id,
                    name = name,
                    coverUrl = (obj["picUrl"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["coverImgUrl"] as? JsonPrimitive)?.contentOrNull,
                    artistName = (artist?.get("name") as? JsonPrimitive)?.contentOrNull
                        ?: ((obj["artists"] as? JsonArray)?.firstOrNull() as? JsonObject)
                            ?.let { (it["name"] as? JsonPrimitive)?.contentOrNull },
                    trackCount = (obj["size"] as? JsonPrimitive)?.intOrNull
                        ?: (obj["trackCount"] as? JsonPrimitive)?.intOrNull ?: 0,
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

    suspend fun getHighQualityPlaylists(api: MusicApiService, cat: String = "全部", limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        parseRecommendedPlaylists(api.getHighqualityPlaylists(cat = cat, limit = limit))
}