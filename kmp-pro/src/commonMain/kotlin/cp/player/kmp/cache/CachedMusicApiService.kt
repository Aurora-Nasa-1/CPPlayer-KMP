package cp.player.kmp.cache

import cp.player.kmp.api.MusicApiMethod
import cp.player.kmp.api.MusicApiService
import cp.player.kmp.monitor.HealthMonitor
import cp.player.kmp.provider.ProviderManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 带"网络缓存 + 异步加载"的 [MusicApiService] 封装。
 *
 * ### 调用流程（每个请求返回多值的 [Flow]）
 *
 * 1. **先返回缓存**：若 [cache] 中存在条目，立即发射 [CacheResult.Cached]
 *    （标记 [CacheResult.Cached.isStale] = 是否超过 [config.freshTtlMs]）。
 * 2. **后台拉取 + 比对指纹**：异步调用底层 [MusicApiService]。
 *    - 请求成功 → 计算新响应指纹：
 *      - 与缓存指纹相同 → 发射 [CacheResult.NoChange]（可忽略）
 *      - 不同 → 发射 [CacheResult.Fresh]（"不同的较大数据"异步回传），并写回缓存
 *    - 请求失败 → 发射 [CacheResult.Error]，若存在缓存附 [CacheResult.Error.fallback]
 * 3. **健康监控深度集成**：根据 [HealthMonitor] 三级分类决定回退与告警：
 *    - [HealthMonitor.HealthLevel.ERROR]：触发多 Provider 容灾（[callWithAllProviders]），
 *      成功回退则把 [CacheResult.Fresh.source] 标为 [CacheResult.Source.FALLBACK]；
 *      失败则 [CacheResult.Error]
 *    - [HealthMonitor.HealthLevel.WARNING]：仍发射数据，附带 [warnings]
 *    - [HealthMonitor.HealthLevel.OK]：正常发射
 *
 * ### 缓存策略
 * - 键：`providerId#method#sortedParams#cookieHash`（见 [cacheKey]）
 * - 仅对幂等的"读"类接口缓存；写/动作类接口（登录、点赞、发评论、打卡等）直通网络。
 *
 * @param delegate 底层 [MusicApiService]（通常为 [cp.player.kmp.api.MusicApiServiceImpl]）
 * @param cache 缓存实现
 * @param providerManager 用于获取多 Provider 列表做容灾
 * @param config 缓存/回退配置
 */
class CachedMusicApiService(
    private val delegate: MusicApiService,
    private val cache: ApiCache,
    private val providerManager: ProviderManager,
    private val allProviders: () -> List<cp.player.kmp.provider.BackendProvider>,
    private val config: CacheConfig = CacheConfig()
) : MusicApiService by delegate {

    /**
     * 带缓存的 API 调用，返回多值 [Flow]。
     *
     * 首个值可能为 [CacheResult.Cached]（立即）；随后为 [CacheResult.Fresh] /
     * [CacheResult.NoChange] / [CacheResult.Error]（后台网络结果）。
     */
    fun callApiCached(
        method: String,
        params: Map<String, String> = emptyMap(),
        cookie: String? = null
    ): Flow<CacheResult<JsonElement>> = flow {
        if (!config.enableCache) {
            // 缓存总开关关闭：直接网络取，不写缓存
            emitNetworkResult(method, params, cookie, cached = null, key = null)
            return@flow
        }

        val providerId = providerManager.getCurrentProviderId()
        val key = cacheKey(providerId, method, params, cookie)

        // 1) 先返回缓存（若存在）
        val cached = cache.get(key)
        if (cached != null) {
            val now = Clock.System.now().toEpochMilliseconds()
            emit(CacheResult.Cached(
                data = cached.data,
                ageMs = cached.age(now),
                isStale = cached.age(now) > config.freshTtlMs,
                source = CacheResult.Source.CACHE
            ))
        }

        // 2) 后台拉取 + 比对指纹（同步在 Flow 上收集，调用方决定是否切线程）
        emitNetworkResult(method, params, cookie, cached, key)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<CacheResult<JsonElement>>.emitNetworkResult(
        method: String,
        params: Map<String, String>,
        cookie: String?,
        cached: CacheEntry?,
        key: String?
    ) {
        try {
            val fresh = delegate.callApi(method, params, cookie)
            val warnings = collectRecentWarnings(method)
            val freshLevel = classifyFresh(fresh, warnings)
            val fp = Fingerprinter.compute(fresh)

            // 健康监控 ERROR → 多 Provider 容灾
            if (freshLevel == HealthMonitor.HealthLevel.ERROR && config.enableFallback) {
                val fallback = tryFallback(method, params)
                if (fallback != null) {
                    if (key != null && isCacheable(method)) cache.putData(key, fallback)
                    emit(CacheResult.Fresh(
                        data = fallback,
                        source = CacheResult.Source.FALLBACK,
                        level = HealthMonitor.HealthLevel.OK,
                        warnings = warnings
                    ))
                    return
                }
            }

            // 比对指纹：与缓存相同且非 ERROR → 无需替换
            if (cached != null && cached.fingerprint == fp && freshLevel != HealthMonitor.HealthLevel.ERROR) {
                emit(CacheResult.NoChange)
                return
            }

            // 不同/无缓存 → 回传新数据并写回缓存
            if (key != null && isCacheable(method) && freshLevel != HealthMonitor.HealthLevel.ERROR) {
                cache.putData(key, fresh)
            }
            emit(CacheResult.Fresh(
                data = fresh,
                level = freshLevel,
                warnings = warnings
            ))
        } catch (e: Exception) {
            emit(CacheResult.Error(
                message = e.message ?: "network error",
                fallback = cached?.data,
                level = HealthMonitor.HealthLevel.ERROR
            ))
        }
    }

    /**
     * 多 Provider 容灾回退：当前响应为 [HealthMonitor.HealthLevel.ERROR] 时，
     * 依次尝试其它已加载 Provider 调用同一方法（经各 Provider [apiMap] 映射）。
     *
     * - 仅返回第一个"OK"响应（code ∈ {200,0,201,301}）
     * - 每次尝试记录到 [HealthMonitor]（成功时标记 wasFallback）
     */
    private suspend fun tryFallback(method: String, params: Map<String, String>): JsonElement? {
        val ordered = allProviders().toMutableList()
        val current = providerManager.currentProvider
        if (current != null) { ordered.remove(current); ordered.add(0, current) }
        for (provider in ordered) {
            if (provider.id == current?.id) continue // 跳过已失败的当前 Provider
            val mapped = provider.apiMap?.get(method) ?: method
            if (mapped.isEmpty() || mapped.equals("unsupported", ignoreCase = true)) continue
            val start = Clock.System.now().toEpochMilliseconds()
            try {
                val raw = provider.callApi(mapped, params)
                val parsed = parseOrNull(raw) ?: continue
                val code = ((parsed as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
                val ok = code == 200 || code == 0 || code == 201 || code == 301
                HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                    timestamp = start, providerId = provider.id, method = method,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    success = ok, wasFallback = true, fallbackFrom = current?.id,
                    responseCode = code
                ))
                if (ok) return parsed
            } catch (e: Exception) {
                HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                    timestamp = start, providerId = provider.id, method = method,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    success = false, errorMessage = e.message, wasFallback = true,
                    fallbackFrom = current?.id
                ))
            }
        }
        return null
    }

    private fun parseOrNull(raw: String): JsonElement? = try {
        parser.parseToJsonElement(raw)
    } catch (_: Exception) { null }

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 取最近一条与 method 相关的告警（从 HealthMonitor）。 */
    private fun collectRecentWarnings(method: String): List<HealthMonitor.ResponseWarning> {
        return HealthMonitor.getRecentRecords(limit = 1, onlyWarnings = true)
            .firstOrNull { it.method == method }?.responseWarnings ?: emptyList()
    }

    private fun classifyFresh(json: JsonElement, warnings: List<HealthMonitor.ResponseWarning>): HealthMonitor.HealthLevel {
        val code = ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
        val success = code == 200 || code == 0 || code == 201 || code == 301
        return when {
            !success -> HealthMonitor.HealthLevel.ERROR
            warnings.any { HealthMonitor.classify(it) == HealthMonitor.HealthLevel.ERROR } -> HealthMonitor.HealthLevel.ERROR
            warnings.isEmpty() -> HealthMonitor.HealthLevel.OK
            else -> HealthMonitor.HealthLevel.WARNING
        }
    }

    /** 仅幂等读类接口缓存；写/动作类直通网络。 */
    private fun isCacheable(method: String): Boolean = when (method) {
        MusicApiMethod.SONG_URL_V1, MusicApiMethod.SONG_URL_V1_302, MusicApiMethod.SONG_DOWNLOAD_URL,
        MusicApiMethod.SONG_DETAIL, MusicApiMethod.LYRIC_NEW, MusicApiMethod.ALBUM_DETAIL,
        MusicApiMethod.PLAYLIST_DETAIL, MusicApiMethod.PLAYLIST_TRACK_ALL,
        MusicApiMethod.USER_PLAYLIST, MusicApiMethod.USER_DETAIL, MusicApiMethod.USER_CLOUD,
        MusicApiMethod.USER_LIKE_LIST, MusicApiMethod.ARTIST_DETAIL, MusicApiMethod.ARTIST_SONGS,
        MusicApiMethod.ARTIST_ALBUM, MusicApiMethod.SEARCH_CLOUD, MusicApiMethod.SEARCH_HOT_DETAIL,
        MusicApiMethod.TOPLIST, MusicApiMethod.TOPLIST_DETAIL, MusicApiMethod.PERSONALIZED,
        MusicApiMethod.PERSONALIZED_NEWSONG, MusicApiMethod.BANNER, MusicApiMethod.SIMI_SONG,
        MusicApiMethod.SIMI_ARTIST, MusicApiMethod.SIMI_PLAYLIST, MusicApiMethod.COMMENT_MUSIC,
        MusicApiMethod.COMMENT_PLAYLIST, MusicApiMethod.COMMENT_ALBUM -> true
        else -> false
    }
}