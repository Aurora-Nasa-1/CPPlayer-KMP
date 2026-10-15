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

        if (parsed.resourceType == "mv" || parsed.resourceType == "video") {
            return try {
                val isVideo = parsed.resourceType == "video" || !parsed.resourceId.all { it.isDigit() }
                val detailJson = if (isVideo) {
                    musicApiService.getVideoDetail(parsed.resourceId)
                } else {
                    musicApiService.getMvDetail(parsed.resourceId)
                }
                val qualities = if (isVideo) extractVideoQualities(detailJson) else extractMvQualities(detailJson)
                BackendResult.Success(qualities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackendResult.Error("获取清晰度列表失败: ${e.message}", cause = e)
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
            val isVideo = parsed.resourceType == "video" || !parsed.resourceId.all { it.isDigit() }

            // 1. 获取详情（根据资源类型分流到 MV 或短视频 Video）
            val detailJson = if (isVideo) {
                musicApiService.getVideoDetail(parsed.resourceId)
            } else {
                musicApiService.getMvDetail(parsed.resourceId)
            }

            val dataObj = (detailJson as? JsonObject)?.get("data") as? JsonObject
                ?: (detailJson as? JsonObject)

            val name = (dataObj?.get("name") ?: dataObj?.get("title"))?.jsonPrimitive?.contentOrNull
            val artistName = (dataObj?.get("artistName") as? JsonPrimitive)?.contentOrNull
                ?: (dataObj?.get("creator") as? JsonObject)?.get("nickname")?.jsonPrimitive?.contentOrNull
                ?: (dataObj?.get("artists") as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }?.get("name")?.jsonPrimitive?.contentOrNull
            val cover = (dataObj?.get("cover") ?: dataObj?.get("coverUrl"))?.jsonPrimitive?.contentOrNull

            val rawDuration = (dataObj?.get("duration")
                ?: dataObj?.get("durationms")
                ?: dataObj?.get("durationMs")
                ?: dataObj?.get("dt"))?.jsonPrimitive?.longOrNull ?: 0L
            val duration = if (rawDuration in 1..9999) rawDuration * 1000L else rawDuration

            val availableQualities = if (isVideo) {
                extractVideoQualities(detailJson)
            } else {
                extractMvQualities(detailJson)
            }

            val brsObj = dataObj?.get("brs") as? JsonObject

            // 2. 构建清晰度尝试优先级列表：用户期望 -> 可选清晰度 -> 默认降级阶梯(1080 -> 720 -> 480 -> 240)
            val preferredRes = preferredQuality?.toIntOrNull()
            val candidateResolutions = buildList {
                if (preferredRes != null) add(preferredRes)
                availableQualities.forEach { add(it.resolution) }
                addAll(listOf(1080, 720, 480, 240))
            }.filter { it > 0 }.distinct()

            var streamUrl: String? = null
            var resolvedRes = 0

            // 3. 循环尝试各清晰度，支持 brs 直链与 API 流解析降级
            for (res in candidateResolutions) {
                // a. 优先检测详情中是否已内嵌对应清晰度的直链 (brs)
                val directBrsUrl = brsObj?.get(res.toString())?.jsonPrimitive?.contentOrNull
                if (!directBrsUrl.isNullOrBlank() && (directBrsUrl.startsWith("http://") || directBrsUrl.startsWith("https://"))) {
                    streamUrl = directBrsUrl
                    resolvedRes = res
                    break
                }

                // b. 调用接口拉取清晰度流地址
                val urlJson = try {
                    if (isVideo) {
                        musicApiService.getVideoUrl(parsed.resourceId, res)
                    } else {
                        musicApiService.getMvUrl(parsed.resourceId, res)
                    }
                } catch (_: Exception) {
                    null
                }

                val fetchedUrl = urlJson?.let { extractUrlFromMvUrlResponse(it) }
                if (!fetchedUrl.isNullOrBlank() && (fetchedUrl.startsWith("http://") || fetchedUrl.startsWith("https://"))) {
                    streamUrl = fetchedUrl
                    resolvedRes = res
                    break
                }
            }

            // 4. 若上述均未命中，尝试从 brs 对象中任意获取一条非空链接兜底
            if (streamUrl.isNullOrBlank() && brsObj != null) {
                for ((k, v) in brsObj) {
                    val fallback = v.jsonPrimitive.contentOrNull
                    if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                        streamUrl = fallback
                        resolvedRes = k.toIntOrNull() ?: 720
                        break
                    }
                }
            }

            // 5. 兼容短视频详情内的 urlInfos 兜底
            if (streamUrl.isNullOrBlank() && isVideo) {
                val urlInfos = dataObj?.get("urlInfos")?.jsonArray
                val firstInfoUrl = urlInfos?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                if (!firstInfoUrl.isNullOrBlank() && (firstInfoUrl.startsWith("http://") || firstInfoUrl.startsWith("https://"))) {
                    streamUrl = firstInfoUrl
                    resolvedRes = urlInfos.firstOrNull()?.jsonObject?.get("r")?.jsonPrimitive?.intOrNull ?: 720
                }
            }

            if (streamUrl.isNullOrBlank()) {
                return BackendResult.Error("无法获取播放地址（可能需要 VIP 权限或资源暂不可用）")
            }

            val cookie = cookieProvider()?.takeIf { it.isNotBlank() }
            val headers = buildMap {
                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                put("Referer", "https://music.163.com/")
                if (cookie != null) {
                    put("Cookie", cookie)
                }
            }

            val currentQuality = availableQualities.find { it.resolution == resolvedRes }
                ?: MediaQualityOption(
                    id = resolvedRes.toString(),
                    label = formatResolutionLabel(resolvedRes),
                    resolution = resolvedRes,
                )

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

    private fun extractVideoQualities(json: JsonElement): List<MediaQualityOption> {
        val dataObj = (json as? JsonObject)?.get("data") as? JsonObject ?: (json as? JsonObject) ?: return emptyList()
        val resolutions = dataObj["resolutions"] as? JsonArray
        if (resolutions != null && resolutions.isNotEmpty()) {
            return resolutions.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val res = obj["resolution"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                MediaQualityOption(
                    id = res.toString(),
                    label = formatResolutionLabel(res),
                    resolution = res,
                    sizeBytes = size,
                )
            }.sortedByDescending { it.resolution }
        }
        return extractMvQualities(json)
    }

    private fun extractMvQualities(json: JsonElement): List<MediaQualityOption> {
        val dataObj = (json as? JsonObject)?.get("data") as? JsonObject ?: (json as? JsonObject) ?: return emptyList()
        val brsObj = dataObj["brs"] as? JsonObject
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

        val brsArray = dataObj["brs"] as? JsonArray
        if (brsArray != null && brsArray.isNotEmpty()) {
            return brsArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
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
        val rootObj = json as? JsonObject ?: return null
        val dataObj = rootObj["data"] as? JsonObject ?: rootObj

        // 1. 常规 data.url
        val url = dataObj["url"]?.jsonPrimitive?.contentOrNull
        if (!url.isNullOrBlank()) return url

        // 2. 短视频 urls 列表
        val urlsArray = rootObj["urls"] as? JsonArray
        val urlFromUrls = (urlsArray?.firstOrNull() as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        if (!urlFromUrls.isNullOrBlank()) return urlFromUrls

        // 3. data 为数组情况
        val dataArray = rootObj["data"] as? JsonArray
        val urlFromArray = (dataArray?.firstOrNull() as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        if (!urlFromArray.isNullOrBlank()) return urlFromArray

        return null
    }

    private fun formatResolutionLabel(res: Int): String = when {
        res >= 1080 -> "1080P 高清"
        res >= 720 -> "720P 高清"
        res >= 480 -> "480P 标清"
        res >= 240 -> "240P 极速"
        else -> "${res}P"
    }
}
