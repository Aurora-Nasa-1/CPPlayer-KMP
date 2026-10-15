package cp.player.core.media

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.local.LocalMediaSource
import cp.player.core.music.CPMediaId
import kotlinx.coroutines.CancellationException
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
 * 统一多媒体流解析器接口。
 *
 * 职责：
 * 统一根据媒体资源标识（[CPMediaId]）提取真实播放流地址、请求头、媒体元数据及可选清晰度列表。
 * 无缝聚合本地媒体文件与云端音源 Provider（MV 与音频）。
 */
interface UnifiedMediaResolver {

    /**
     * 解析指定媒体条目的播放流。
     *
     * @param mediaId 统一媒体 ID（例如 `local://video/{path}` 或 `netease://mv/{mvId}`）
     * @param preferredQuality 期望的清晰度/画质（如 "1080", "720", "480", "lossless", "standard"），null 则优先最高画质
     */
    suspend fun resolveStream(
        mediaId: String,
        preferredQuality: String? = null,
    ): BackendResult<MediaStream>

    /**
     * 获取指定媒体条目支持的所有清晰度 / 音质选项列表。
     */
    suspend fun getAvailableQualities(mediaId: String): BackendResult<List<MediaQualityOption>>
}

class UnifiedMediaResolverImpl(
    private val localMediaSource: LocalMediaSource,
    private val musicApiService: MusicApiService,
    private val cookieProvider: () -> String? = { null },
) : UnifiedMediaResolver {

    private fun parseId(mediaId: String): CPMediaId? = try {
        CPMediaId.parse(mediaId)
    } catch (_: IllegalArgumentException) {
        null
    }

    override suspend fun resolveStream(
        mediaId: String,
        preferredQuality: String?,
    ): BackendResult<MediaStream> {
        val parsed = parseId(mediaId)
            ?: return BackendResult.Error("非法的媒体 ID 格式: $mediaId")

        return when {
            // ============ 本地媒体（视频或音频） ============
            parsed.providerId == "local" -> resolveLocalMedia(parsed, mediaId)

            // ============ 云端 MV / 视频 ============
            parsed.resourceType == "mv" || parsed.resourceType == "video" -> {
                resolveCloudMv(parsed, mediaId, preferredQuality)
            }

            // ============ 云端歌曲 / 音频 ============
            parsed.resourceType == "song" || parsed.resourceType == "audio" -> {
                resolveCloudSong(parsed, mediaId, preferredQuality ?: "standard")
            }

            else -> BackendResult.Unsupported("未知的媒体资源类型: ${parsed.resourceType}")
        }
    }

    override suspend fun getAvailableQualities(mediaId: String): BackendResult<List<MediaQualityOption>> {
        val parsed = parseId(mediaId)
            ?: return BackendResult.Error("非法的媒体 ID 格式: $mediaId")

        if (parsed.providerId == "local") {
            return BackendResult.Success(
                listOf(MediaQualityOption(id = "original", label = "本地原画", resolution = 0))
            )
        }

        if (parsed.resourceType == "mv") {
            return try {
                val detailJson = musicApiService.getMvDetail(parsed.resourceId)
                val qualities = extractMvQualities(detailJson)
                BackendResult.Success(qualities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackendResult.Error("获取 MV 清晰度列表失败: ${e.message}", cause = e)
            }
        }

        // 音频默认档位
        return BackendResult.Success(
            listOf(
                MediaQualityOption(id = "standard", label = "标准音质 128k"),
                MediaQualityOption(id = "exhigh", label = "极高音质 320k"),
                MediaQualityOption(id = "lossless", label = "无损 FLAC"),
                MediaQualityOption(id = "hires", label = "Hi-Res 高解析"),
            )
        )
    }

    // ======================== 私有解析分支 ========================

    private fun resolveLocalMedia(parsed: CPMediaId, mediaId: String): BackendResult<MediaStream> {
        val allItems = localMediaSource.items().value
        val item = allItems.find { it.path == parsed.resourceId }
            ?: return BackendResult.Error("未找到本地媒体文件: ${parsed.resourceId}")

        val defaultQuality = MediaQualityOption(id = "original", label = "本地原画", sizeBytes = item.sizeBytes)
        return BackendResult.Success(
            MediaStream(
                mediaId = mediaId,
                url = item.path,
                headers = emptyMap(),
                currentQuality = defaultQuality,
                availableQualities = listOf(defaultQuality),
                mediaType = item.mediaType,
                title = item.title,
                artist = item.artist,
                coverUrl = item.coverUri,
                durationMs = item.durationMs,
            )
        )
    }

    private suspend fun resolveCloudMv(
        parsed: CPMediaId,
        mediaId: String,
        preferredQuality: String?,
    ): BackendResult<MediaStream> {
        return try {
            // 1. 获取 MV 详情
            val detailJson = musicApiService.getMvDetail(parsed.resourceId)
            val dataObj = (detailJson as? JsonObject)?.get("data")?.jsonObject
                ?: (detailJson as? JsonObject)

            val name = (dataObj?.get("name") as? JsonPrimitive)?.contentOrNull
            val artistName = (dataObj?.get("artistName") as? JsonPrimitive)?.contentOrNull
                ?: (dataObj?.get("artists") as? JsonArray)?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            val cover = (dataObj?.get("cover") as? JsonPrimitive)?.contentOrNull
                ?: (dataObj?.get("coverUrl") as? JsonPrimitive)?.contentOrNull
            val duration = (dataObj?.get("duration") as? JsonPrimitive)?.longOrNull ?: 0L

            val availableQualities = extractMvQualities(detailJson)
            val targetRes = preferredQuality?.toIntOrNull()
                ?: availableQualities.firstOrNull()?.resolution
                ?: 1080

            // 2. 获取目标清晰度的流地址
            val urlJson = musicApiService.getMvUrl(parsed.resourceId, targetRes)
            val streamUrl = extractUrlFromMvUrlResponse(urlJson)
                ?: return BackendResult.Error("无法获取 MV 播放流 (清晰度=$targetRes)")

            val cookie = cookieProvider()?.takeIf { it.isNotBlank() }
            val headers = buildMap {
                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                put("Referer", "https://music.163.com/")
                if (cookie != null) {
                    put("Cookie", cookie)
                }
            }

            val currentQuality = availableQualities.find { it.resolution == targetRes }
                ?: MediaQualityOption(id = targetRes.toString(), label = "${targetRes}P", resolution = targetRes)

            BackendResult.Success(
                MediaStream(
                    mediaId = mediaId,
                    url = streamUrl,
                    headers = headers,
                    currentQuality = currentQuality,
                    availableQualities = availableQualities,
                    mediaType = MediaType.VIDEO,
                    title = name,
                    artist = artistName,
                    coverUrl = cover,
                    durationMs = duration,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackendResult.Error("获取 MV 播放流异常: ${e.message}", cause = e)
        }
    }

    private suspend fun resolveCloudSong(
        parsed: CPMediaId,
        mediaId: String,
        level: String,
    ): BackendResult<MediaStream> {
        return try {
            val json = musicApiService.getSongUrl(parsed.resourceId, level)
            val songObj = ((json as? JsonObject)?.get("data") as? JsonArray)?.firstOrNull()?.jsonObject
                ?: (json as? JsonObject)
            val url = (songObj?.get("url") as? JsonPrimitive)?.contentOrNull
                ?: return BackendResult.Error("未获取到歌曲播放地址: $mediaId")

            val size = (songObj?.get("size") as? JsonPrimitive)?.longOrNull ?: 0L
            val cookie = cookieProvider()?.takeIf { it.isNotBlank() }
            val headers = buildMap {
                if (cookie != null) put("Cookie", cookie)
            }

            val currentQuality = MediaQualityOption(id = level, label = level, sizeBytes = size)
            BackendResult.Success(
                MediaStream(
                    mediaId = mediaId,
                    url = url,
                    headers = headers,
                    currentQuality = currentQuality,
                    mediaType = MediaType.AUDIO,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackendResult.Error("获取歌曲播放流异常: ${e.message}", cause = e)
        }
    }

    private fun extractMvQualities(json: JsonElement): List<MediaQualityOption> {
        val dataObj = (json as? JsonObject)?.get("data")?.jsonObject ?: (json as? JsonObject) ?: return emptyList()
        val brsObj = dataObj["brs"]?.jsonObject
        if (brsObj != null && brsObj.isNotEmpty()) {
            return brsObj.keys.mapNotNull { it.toIntOrNull() }
                .sortedDescending()
                .map { res ->
                    MediaQualityOption(
                        id = res.toString(),
                        label = formatResolutionLabel(res),
                        resolution = res,
                    )
                }
        }

        val brsArray = dataObj["brs"]?.jsonArray
        if (brsArray != null && brsArray.isNotEmpty()) {
            return brsArray.mapNotNull {
                val obj = it.jsonObject
                val r = (obj["br"] ?: obj["r"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                MediaQualityOption(
                    id = r.toString(),
                    label = formatResolutionLabel(r),
                    resolution = r,
                    sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.sortedByDescending { it.resolution }
        }

        // 默认保底分辨率选项
        return listOf(
            MediaQualityOption(id = "1080", label = "1080P 高清", resolution = 1080),
            MediaQualityOption(id = "720", label = "720P 高清", resolution = 720),
            MediaQualityOption(id = "480", label = "480P 标清", resolution = 480),
        )
    }

    private fun extractUrlFromMvUrlResponse(json: JsonElement): String? {
        val dataObj = (json as? JsonObject)?.get("data")?.jsonObject ?: (json as? JsonObject)
        val url = (dataObj?.get("url") as? JsonPrimitive)?.contentOrNull
        if (!url.isNullOrBlank()) return url
        // 兼容直接包含 url 的结构
        return (json as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
    }

    private fun formatResolutionLabel(res: Int): String = when {
        res >= 1080 -> "1080P 高清"
        res >= 720 -> "720P 高清"
        res >= 480 -> "480P 标清"
        res >= 240 -> "240P 极速"
        else -> "${res}P"
    }
}
