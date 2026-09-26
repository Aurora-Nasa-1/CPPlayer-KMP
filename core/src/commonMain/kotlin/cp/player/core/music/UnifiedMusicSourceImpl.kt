package cp.player.core.music

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.local.LocalMediaSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class UnifiedMusicSourceImpl(
    private val musicApiService: MusicApiService,
    private val localMusicSource: LocalMediaSource,
    /**
     * 当前活跃音源 id（惰性求值）。
     *
     * `search` / `getUserPlaylists` 的 `providers` / `providerId` 参数曾经**从未被读取** ——
     * KDoc 写着「支持跨音源」，实现里却一律用活跃音源，调用方传什么都拿到同一个结果，
     * 属静默错误结果。跨音源聚合尚未实现，正确行为是显式报错而不是假装成功。
     */
    private val activeProviderId: () -> String?,
) : UnifiedMusicSource {

    /**
     * 解析 mediaId，畸形输入一律变成 [BackendResult.Error] 而**不是抛异常**。
     *
     * [CPMediaId.parse] 抛 `IllegalArgumentException`；原先它在 `try` 之外，
     * 异常会直接穿过 —— 违反 `MusicBackend` KDoc 承诺的「可失败操作一律返回 BackendResult」，
     * 对外 API 场景下表现为 500 而不是 400。
     */
    private fun parseId(mediaId: String): CPMediaId? = try {
        CPMediaId.parse(mediaId)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 音源参数校验；null 表示放行，否则是应当直接返回给调用方的错误。 */
    private fun checkProviderList(providers: List<String>?): BackendResult<Nothing>? {
        if (providers == null) return null // 未指定 ⇒ 用活跃音源
        if (providers.isEmpty()) return BackendResult.Error("providers 为空列表：语义不明（是「不限」还是「无」？），请显式指定")
        val active = activeProviderId()
            ?: return BackendResult.Error("当前没有活跃音源，无法判定 providers 是否匹配")
        if (active in providers) return null
        return BackendResult.Unsupported("该音源不支持跨 Provider 查询（活跃=$active，请求=$providers）")
    }

    private fun checkProviderId(providerId: String): BackendResult<Nothing>? {
        if (providerId.isBlank()) return BackendResult.Error("providerId 不能为空")
        val active = activeProviderId()
            ?: return BackendResult.Error("当前没有活跃音源，无法判定 providerId 是否匹配")
        if (providerId == active) return null
        return BackendResult.Unsupported("该音源不支持跨 Provider 查询（活跃=$active，请求=$providerId）")
    }

    override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> {
        val id = parseId(mediaId) ?: return BackendResult.Error("Invalid mediaId: $mediaId")
        if (id.providerId == "local") {
            val list = localMusicSource.items().value
            val item = list.find { it.path == id.resourceId }
            return if (item != null) {
                BackendResult.Success(
                    TrackSummary(
                        id = mediaId,
                        name = item.title,
                        artist = item.artist ?: "Unknown",
                        album = item.album,
                        coverUrl = item.coverUri,
                        durationMs = item.durationMs
                    )
                )
            } else {
                BackendResult.Error("Local song not found")
            }
        }
        return try {
            val json = musicApiService.getSongDetail(listOf(id.resourceId))
            val songs = (json as? JsonObject)?.get("songs")?.jsonArray
            if (songs != null && songs.isNotEmpty()) {
                val track = songs[0].jsonObject
                BackendResult.Success(track.toTrackSummary(mediaId))
            } else {
                BackendResult.Error("Song not found: $mediaId")
            }
        } catch (e: Exception) {
            BackendResult.Error("Failed to get track detail: ${e.message}", cause = e)
        }
    }

    override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> {
        val summaries = mutableListOf<TrackSummary>()
        val parsedIds = mediaIds.map { parseId(it) ?: return BackendResult.Error("Invalid mediaId in batch") }

        val localIds = parsedIds.filter { it.providerId == "local" }
        if (localIds.isNotEmpty()) {
            val list = localMusicSource.items().value
            // Bolt: Optimize O(N^2) lookup to O(N) by using a hash map
            val localMap = list.associateBy { it.path }
            for (id in localIds) {
                val item = localMap[id.resourceId]
                if (item != null) {
                    summaries.add(TrackSummary(
                        id = id.toString(),
                        name = item.title,
                        artist = item.artist ?: "Unknown",
                        album = item.album,
                        coverUrl = item.coverUri,
                        durationMs = item.durationMs
                    ))
                }
            }
        }
        
        val apiIds = parsedIds.filter { it.providerId != "local" }
        if (apiIds.isNotEmpty()) {
            // 分批请求以避免 URI 过长
            for (chunk in apiIds.chunked(500)) {
                try {
                    val json = musicApiService.getSongDetail(chunk.map { it.resourceId })
                    val songs = (json as? JsonObject)?.get("songs")?.jsonArray
                    songs?.forEach { songJson ->
                        val trackObj = songJson.jsonObject
                        val rid = rawTrackId(trackObj).ifEmpty { return@forEach }
                        val matchedApiId = chunk.find { it.resourceId == rid }
                        if (matchedApiId != null) {
                            summaries.add(trackObj.toTrackSummary(matchedApiId.toString()))
                        }
                    }
                } catch (e: Exception) {
                    // 忽略或记录错误
                }
            }
        }
        
        return BackendResult.Success(summaries)
    }

    override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> {
        val id = parseId(mediaId) ?: return BackendResult.Error("Invalid mediaId: $mediaId")
        if (id.providerId == "local") {
            val item = localMusicSource.items().value.find { it.path == id.resourceId }
                // 条目缺失时返回 Error（与 getTrackDetail 行为一致），不得以裸路径伪装 Success
                ?: return BackendResult.Error("Local media not found: $mediaId")
            return BackendResult.Success(SongUrl(item.path, level, item.sizeBytes, null))
        }
        return try {
            val json = musicApiService.getSongUrl(id.resourceId, level)
            val url = extractUrl(json)
            if (url != null && url.startsWith("http")) {
                val sizeBytes = ((json as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull
                val cookie = ((json as? JsonObject)?.get("cookie") as? JsonPrimitive)?.contentOrNull
                BackendResult.Success(SongUrl(url, level, sizeBytes, null, cookie))
            } else {
                BackendResult.Error("No valid URL returned for $mediaId")
            }
        } catch (e: Exception) {
            BackendResult.Error("Failed to get song URL: ${e.message}", cause = e)
        }
    }

    override suspend fun search(keywords: String, type: Int, providers: List<String>?): MusicResult<SearchResult> {
        checkProviderList(providers)?.let { return it }
        return MusicSourceFromApi.search(musicApiService, keywords, type)
    }

    override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> {
        checkProviderId(providerId)?.let { return it }
        return MusicSourceFromApi.getUserPlaylists(musicApiService, uid)
    }

    // ======================== 内部解析 ========================

    /** 与 [MusicSourceFromApi] 共用同一份映射，两条路径不允许再各写一份。 */
    private fun JsonObject.toTrackSummary(mediaId: String): TrackSummary =
        trackSummaryOf(this, mediaId)

    private fun extractUrl(body: kotlinx.serialization.json.JsonElement): String? {
        if (body !is JsonObject) return null
        (body["redirectUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.startsWith("http")) return it }
        return findUrlRecursive(body)
    }

    private fun findUrlRecursive(element: kotlinx.serialization.json.JsonElement?): String? {
        if (element == null) return null
        when (element) {
            is JsonPrimitive -> {
                val s = element.contentOrNull ?: return null
                if (s.startsWith("http") && s.length > 12 && !s.contains("null")) return s
            }
            is JsonObject -> {
                for (key in listOf("url", "picUrl", "coverImgUrl", "avatarUrl")) {
                    val p = element[key]
                    if (p is JsonPrimitive) {
                        val s = p.contentOrNull
                        if (s != null && s.startsWith("http") && s.length > 12 && !s.contains("null")) return s
                    }
                }
                for ((_, v) in element) { findUrlRecursive(v)?.let { return it } }
            }
            is JsonArray -> {
                for (e in element) { findUrlRecursive(e)?.let { return it } }
            }
        }
        return null
    }
}
