package cp.player.kmp.music

import cp.player.kmp.BackendResult
import cp.player.kmp.api.MusicApiService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    // ============ code 判定（跨 Provider） ============

    private fun codeOf(json: JsonElement): Int? =
        ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            ?: ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull

    private fun isSuccess(json: JsonElement): Boolean {
        val c = codeOf(json) ?: return false
        return c == 200 || c == 0 || c == 201 || c == 301
    }

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
            val array = (this["recommend"] ?: this["result"] ?: this["playlists"]) as? JsonArray
                ?: JsonArray(emptyList())
            array.map { it.jsonObject.toPlaylistSummary() }
        }
    }

    // ============ 推荐歌曲（日推） ============

    /** 从 `data.dailySongs` / `data` / `recommend` 数组解析推荐歌曲。 */
    fun parseRecommendedSongs(json: JsonElement): MusicResult<List<TrackSummary>> {
        return json.toMusicResult {
            val root = this["data"] as? JsonObject ?: this
            val array = (root["dailySongs"] ?: root["songs"] ?: this["recommend"]) as? JsonArray
                ?: JsonArray(emptyList())
            array.map { it.jsonObject.toTrackSummary() }
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

    private fun JsonObject.toTrackSummary(): TrackSummary {
        val artists = (this["ar"] as? JsonArray) ?: (this["artists"] as? JsonArray)
        val artistNames = artists?.joinToString(" / ") {
            ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        } ?: ((this["artist"] as? JsonPrimitive)?.contentOrNull ?: "")
        val albumObj = (this["al"] as? JsonObject) ?: (this["album"] as? JsonObject)
        return TrackSummary(
            id = (this["id"] as? JsonPrimitive)?.contentOrNull ?: (this["songId"] as? JsonPrimitive)?.contentOrNull ?: "",
            name = (this["name"] as? JsonPrimitive)?.contentOrNull ?: ((this["song"] as? JsonPrimitive)?.contentOrNull ?: ""),
            artist = artistNames,
            album = (albumObj?.get("name") as? JsonPrimitive)?.contentOrNull,
            coverUrl = (albumObj?.get("picUrl") as? JsonPrimitive)?.contentOrNull,
            durationMs = ((this["dt"] ?: this["duration"]) as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

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

    suspend fun getRecommendedPlaylists(api: MusicApiService, limit: Int = 30): MusicResult<List<PlaylistSummary>> =
        parseRecommendedPlaylists(api.getRecommendedPlaylists())

    suspend fun search(api: MusicApiService, keywords: String, type: Int = 1): MusicResult<SearchResult> =
        parseSearchSongs(api.search(keywords, type), type)

    suspend fun getUserPlaylists(api: MusicApiService, uid: Long): MusicResult<List<PlaylistSummary>> =
        parseUserPlaylists(api.getUserPlaylists(uid))

    suspend fun getUserCloud(api: MusicApiService, limit: Int = 200, offset: Int = 0): MusicResult<List<TrackSummary>> =
        parseCloudSongs(api.getUserCloud(limit, offset))
}