package cp.player.core.cache

import cp.player.core.api.ApiResponseCodes
import cp.player.core.api.MusicApiMethod
import cp.player.core.api.MusicApiService
import cp.player.core.monitor.HealthMonitor
import cp.player.core.provider.ProviderManager
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 在 [MusicApiService] 实现之上再包一层的**读透缓存**装饰器。
 *
 * 它实现同一个 [MusicApiService] 接口，所以对调用方是透明的 ——
 * `MusicBackend.musicApi` 交出去的就是它，裸实现不外泄。
 *
 * ### 主路径：读透（read-through）
 *
 * 覆写了 [isCacheable] 名单内的全部读类方法：
 *
 * ```
 * 1) 命中且未超过 [CacheConfig.freshTtlMs]  → 直接返回缓存，不发网络请求
 * 2) 未命中 / 已过期                        → 回源；成功则写回缓存并返回
 * 3) 回源抛异常 / 返回非成功码               → 多 Provider 容灾
 *    → 旧缓存（哪怕过期）→ 原样交出失败响应
 * ```
 *
 * 失败判定只看**业务码**（[ApiResponseCodes]），不看 [HealthMonitor] 的字段级告警：
 * 告警说的是「这份响应不完整但勉强能用」，属于可用性；把可用性当成业务成败会让
 * 一次缺了可选字段的 200 响应被当成故障去连打多个 Provider。
 *
 * ### 副路径：流式 [callApiCached]
 * 需要「先渲染缓存、后台刷新」时用。两条路径**共用同一份 [cache] 与同一套
 * [cacheKey] 键格式**，只是读透层用自己算出的稳定参数 —— 刻意剔除 `timestamp`
 * 这类每次调用都变的参数，否则永远命中不了。
 *
 * ### 缓存键
 * `providerId#method#sortedParams#cookieHash`，cookie 参与键 ⇒ 同机多账号必须隔离。
 *
 * @param delegate 底层 [MusicApiService]（通常为 [cp.player.core.api.MusicApiServiceImpl]）
 * @param cache 缓存实现
 * @param providerManager 取当前 Provider / cookie，并提供多 Provider 容灾列表
 * @param allProviders 已加载的全部 Provider
 * @param config 缓存/回退配置
 */
class CachedMusicApiService(
    private val delegate: MusicApiService,
    private val cache: ApiCache,
    private val providerManager: ProviderManager,
    private val allProviders: () -> List<cp.player.core.provider.BackendProvider>,
    private val config: CacheConfig = CacheConfig()
) : MusicApiService by delegate {

    private val _stats = MutableStateFlow(CacheStats())

    /** 可观测计数（`backend.cachedApi.stats`），见 [CacheStats]。 */
    val stats: StateFlow<CacheStats> = _stats.asStateFlow()

    // ======================== 读透：核心 ========================

    /**
     * 一次读透。
     *
     * @param params **稳定**的键参数：必须能唯一定位这次请求，且不能包含
     *   每次调用都变的值（如 `likelist` 的 `timestamp`）。
     */
    private suspend fun read(
        method: String,
        params: Map<String, String>,
        block: suspend () -> JsonElement
    ): JsonElement {
        // [isCacheable] 是策略名单：未列入的读类接口（如 comment/mv）直通网络。
        // 不加这道闸，声明的策略就和实际行为对不上了。
        if (!config.enableCache || !isCacheable(method)) return block()

        val key = keyFor(method, params)
        val cached = cache.get(key)
        if (cached != null && cached.age(currentTimeMillis()) <= config.freshTtlMs) {
            _stats.update { it.copy(hits = it.hits + 1) }
            return cached.data
        }
        _stats.update { it.copy(misses = it.misses + 1) }
        return fetch(method, params, key, cached, block)
    }

    /** 回源 → 成功写回 / 失败先容灾 → 旧缓存 → 原样交出。 */
    private suspend fun fetch(
        method: String,
        params: Map<String, String>,
        key: String,
        cached: CacheEntry?,
        block: suspend () -> JsonElement
    ): JsonElement {
        val fresh = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tryFallback(method, params)?.let { store(key, it); return it }
            return staleOrNull(cached) ?: throw e
        }

        if (isSuccessResponse(method, fresh)) {
            store(key, fresh)
            return fresh
        }
        // 非成功码 ⇒ 上游/Provider 故障：容灾 → 旧缓存 → 原样交出失败响应
        tryFallback(method, params)?.let { store(key, it); return it }
        return staleOrNull(cached) ?: fresh
    }

    /** 降级返回旧缓存并计数；无旧缓存则返回 null（调用方自行抛/交出失败响应）。 */
    private fun staleOrNull(cached: CacheEntry?): JsonElement? =
        cached?.also { _stats.update { s -> s.copy(staleServed = s.staleServed + 1) } }?.data

    private fun store(key: String, data: JsonElement) {
        cache.putData(key, data)
        _stats.update { it.copy(stores = it.stores + 1) }
    }

    private fun keyFor(method: String, params: Map<String, String>): String {
        val providerId = providerManager.getCurrentProviderId()
        return cacheKey(
            providerId = providerId,
            method = method,
            params = params,
            cookie = providerManager.cookieStorage.getCookie(providerId),
        )
    }

    // ======================== 读透：读类方法覆写 ========================

    // 播放/下载链接：TTL 远短于链接有效期，且键里带账号 cookie
    override suspend fun getSongUrl(songId: String, level: String): JsonElement =
        read(MusicApiMethod.SONG_URL_V1_302, mapOf("id" to songId, "level" to level)) {
            delegate.getSongUrl(songId, level)
        }

    override suspend fun getSongUrlFallback(songId: String, level: String): JsonElement =
        read(MusicApiMethod.SONG_URL_V1, mapOf("id" to songId, "level" to level)) {
            delegate.getSongUrlFallback(songId, level)
        }

    override suspend fun getSongDownloadUrl(songId: String, level: String): JsonElement =
        read(MusicApiMethod.SONG_DOWNLOAD_URL, mapOf("id" to songId, "level" to level)) {
            delegate.getSongDownloadUrl(songId, level)
        }

    override suspend fun getSongDetail(ids: List<String>): JsonElement =
        read(MusicApiMethod.SONG_DETAIL, mapOf("ids" to ids.joinToString(","))) {
            delegate.getSongDetail(ids)
        }

    override suspend fun getLyric(songId: String): JsonElement =
        read(MusicApiMethod.LYRIC_NEW, mapOf("id" to songId)) { delegate.getLyric(songId) }

    // 专辑 / 歌手
    override suspend fun getAlbumDetail(id: Long): JsonElement =
        read(MusicApiMethod.ALBUM_DETAIL, mapOf("id" to id.toString())) { delegate.getAlbumDetail(id) }

    override suspend fun getArtistDetail(id: Long): JsonElement =
        read(MusicApiMethod.ARTIST_DETAIL, mapOf("id" to id.toString())) { delegate.getArtistDetail(id) }

    override suspend fun getArtistSongs(id: Long, limit: Int): JsonElement =
        read(MusicApiMethod.ARTIST_SONGS, mapOf("id" to id.toString(), "limit" to limit.toString())) {
            delegate.getArtistSongs(id, limit)
        }

    override suspend fun getArtistAlbums(id: Long, limit: Int): JsonElement =
        read(MusicApiMethod.ARTIST_ALBUM, mapOf("id" to id.toString(), "limit" to limit.toString())) {
            delegate.getArtistAlbums(id, limit)
        }

    // 歌单
    override suspend fun getPlaylistDetail(id: Long): JsonElement =
        read(MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to id.toString())) {
            delegate.getPlaylistDetail(id)
        }

    override suspend fun getPlaylistTracks(id: Long, limit: Int, offset: Int): JsonElement =
        read(
            MusicApiMethod.PLAYLIST_TRACK_ALL,
            mapOf("id" to id.toString(), "limit" to limit.toString(), "offset" to offset.toString())
        ) { delegate.getPlaylistTracks(id, limit, offset) }

    // 用户（账号相关 ⇒ cookie 必须进键，否则 B 账号读到 A 账号的歌单）
    override suspend fun getUserPlaylists(uid: Long): JsonElement =
        read(MusicApiMethod.USER_PLAYLIST, mapOf("uid" to uid.toString())) {
            delegate.getUserPlaylists(uid)
        }

    override suspend fun getUserDetail(uid: Long): JsonElement =
        read(MusicApiMethod.USER_DETAIL, mapOf("uid" to uid.toString())) { delegate.getUserDetail(uid) }

    override suspend fun getUserCloud(limit: Int, offset: Int): JsonElement =
        read(
            MusicApiMethod.USER_CLOUD,
            mapOf("limit" to limit.toString(), "offset" to offset.toString())
        ) { delegate.getUserCloud(limit, offset) }

    /** 键里刻意**不含** `timestamp`：底层每次调用都现取 `now()`，带上就永远命中不了。 */
    override suspend fun getLikeList(uid: Long): JsonElement =
        read(MusicApiMethod.USER_LIKE_LIST, mapOf("uid" to uid.toString())) {
            delegate.getLikeList(uid)
        }

    // 搜索
    override suspend fun search(keywords: String, type: Int): JsonElement =
        read(MusicApiMethod.SEARCH_CLOUD, mapOf("keywords" to keywords, "type" to type.toString())) {
            delegate.search(keywords, type)
        }

    override suspend fun getHotSearches(): JsonElement =
        read(MusicApiMethod.SEARCH_HOT_DETAIL, emptyMap()) { delegate.getHotSearches() }

    // 排行榜 / 推荐 / 相似
    override suspend fun getToplist(): JsonElement =
        read(MusicApiMethod.TOPLIST, emptyMap()) { delegate.getToplist() }

    override suspend fun getToplistDetail(): JsonElement =
        read(MusicApiMethod.TOPLIST_DETAIL, emptyMap()) { delegate.getToplistDetail() }

    override suspend fun getPersonalizedPlaylists(limit: Int): JsonElement =
        read(MusicApiMethod.PERSONALIZED, mapOf("limit" to limit.toString())) {
            delegate.getPersonalizedPlaylists(limit)
        }

    override suspend fun getPersonalizedNewSongs(limit: Int): JsonElement =
        read(MusicApiMethod.PERSONALIZED_NEWSONG, mapOf("limit" to limit.toString())) {
            delegate.getPersonalizedNewSongs(limit)
        }

    override suspend fun getBanner(): JsonElement =
        read(MusicApiMethod.BANNER, mapOf("type" to "1")) { delegate.getBanner() }

    override suspend fun getSimilarSongs(songId: String): JsonElement =
        read(MusicApiMethod.SIMI_SONG, mapOf("id" to songId)) { delegate.getSimilarSongs(songId) }

    override suspend fun getSimilarArtists(artistId: Long): JsonElement =
        read(MusicApiMethod.SIMI_ARTIST, mapOf("id" to artistId.toString())) {
            delegate.getSimilarArtists(artistId)
        }

    override suspend fun getSimilarPlaylists(songId: String): JsonElement =
        read(MusicApiMethod.SIMI_PLAYLIST, mapOf("id" to songId)) {
            delegate.getSimilarPlaylists(songId)
        }

    /**
     * 评论列表。
     *
     * `type` **必须进键**：`music` 与未知类型都会落到 `comment/music`（端点相同），
     * 键里不带 type 就只能靠端点区分 ⇒ 两种评论互相串数据。
     */
    override suspend fun getComments(
        id: String,
        type: String,
        limit: Int,
        offset: Int,
        sortType: Int
    ): JsonElement = read(
        getCommentMethod(type),
        mapOf(
            "id" to id,
            "type" to type,
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "sortType" to sortType.toString(),
        )
    ) { delegate.getComments(id, type, limit, offset, sortType) }

    // ======================== 写透：写操作失效读缓存 ========================

    /**
     * 写操作：`finally` 里失效，写抛异常也失效。
     *
     * 代价只是一次多余回源；反过来**漏失效**的代价是用户刚改完歌单却看不到变化 ——
     * 这层一旦过度生效，表现不是崩溃而是「数据不更新」，更难查。
     */
    private suspend fun write(vararg methods: String, block: suspend () -> JsonElement): JsonElement {
        try {
            return block()
        } finally {
            invalidate(*methods)
        }
    }

    private fun invalidate(vararg methods: String) {
        if (!config.enableCache || methods.isEmpty()) return
        val providerId = providerManager.getCurrentProviderId()
        var removed = 0
        for (m in methods) removed += cache.removeByPrefix("$providerId#$m")
        if (removed > 0) _stats.update { it.copy(invalidated = it.invalidated + removed) }
    }

    override suspend fun addTracksToPlaylist(pid: Long, trackIds: List<String>): JsonElement =
        write(MusicApiMethod.PLAYLIST_TRACK_ALL, MusicApiMethod.PLAYLIST_DETAIL) {
            delegate.addTracksToPlaylist(pid, trackIds)
        }

    override suspend fun removeTracksFromPlaylist(pid: Long, trackIds: List<String>): JsonElement =
        write(MusicApiMethod.PLAYLIST_TRACK_ALL, MusicApiMethod.PLAYLIST_DETAIL) {
            delegate.removeTracksFromPlaylist(pid, trackIds)
        }

    override suspend fun createPlaylist(name: String, privacy: Int): JsonElement =
        write(MusicApiMethod.USER_PLAYLIST) { delegate.createPlaylist(name, privacy) }

    override suspend fun deletePlaylist(id: Long): JsonElement = write(
        MusicApiMethod.USER_PLAYLIST,
        MusicApiMethod.PLAYLIST_DETAIL,
        MusicApiMethod.PLAYLIST_TRACK_ALL,
    ) { delegate.deletePlaylist(id) }

    override suspend fun subscribePlaylist(id: Long, t: Int): JsonElement =
        write(MusicApiMethod.USER_PLAYLIST, MusicApiMethod.PLAYLIST_DETAIL) {
            delegate.subscribePlaylist(id, t)
        }

    override suspend fun updatePlaylist(
        id: Long,
        name: String?,
        desc: String?,
        tags: List<String>?
    ): JsonElement = write(MusicApiMethod.PLAYLIST_DETAIL) {
        delegate.updatePlaylist(id, name, desc, tags)
    }

    override suspend fun likeSong(id: String, like: Boolean): JsonElement =
        write(MusicApiMethod.USER_LIKE_LIST) { delegate.likeSong(id, like) }

    override suspend fun dislikeSong(id: String): JsonElement =
        write(MusicApiMethod.USER_LIKE_LIST) { delegate.dislikeSong(id) }

    /** 评论只失效 `type` 对应的那一个端点，别把整个评论域都打掉。 */
    override suspend fun likeComment(id: String, cid: Long, type: String, liked: Boolean): JsonElement =
        write(getCommentMethod(type)) { delegate.likeComment(id, cid, type, liked) }

    override suspend fun postComment(
        id: String,
        type: String,
        content: String,
        replyId: Long?
    ): JsonElement = write(getCommentMethod(type)) { delegate.postComment(id, type, content, replyId) }

    override suspend fun deleteUserCloud(songIds: List<String>): JsonElement =
        write(MusicApiMethod.USER_CLOUD) { delegate.deleteUserCloud(songIds) }

    override suspend fun cloudImport(songId: String, matchSongId: String): JsonElement =
        write(MusicApiMethod.USER_CLOUD) { delegate.cloudImport(songId, matchSongId) }

    /** 登出会换掉账号 ⇒ 清全表（按前缀删不掉「同一路径、不同 cookie」的键）。 */
    override suspend fun logout(): JsonElement {
        try {
            return delegate.logout()
        } finally {
            if (config.enableCache) {
                val removed = cache.size()
                cache.clear()
                if (removed > 0) _stats.update { it.copy(invalidated = it.invalidated + removed) }
            }
        }
    }

    // ======================== 副路径：流式 callApiCached ========================

    /**
     * 带缓存的 API 调用，返回多值 [Flow]。
     *
     * 首个值可能为 [CacheResult.Cached]（立即）；随后为 [CacheResult.Fresh] /
     * [CacheResult.NoChange] / [CacheResult.Error]（后台网络结果）。
     *
     * 与读透层**共用同一份 [cache] 与同一套 [cacheKey] 键格式**，只是这里的
     * `params` / `cookie` 由调用方给定 —— 调用方必须传与 `delegate` 实际发出的一致，
     * 否则同一份数据会占两个键。
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

        val key = cacheKey(providerManager.getCurrentProviderId(), method, params, cookie)

        // 1) 先返回缓存（若存在）
        val cached = cache.get(key)
        if (cached != null) {
            val now = currentTimeMillis()
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
                    if (key != null && isCacheable(method)) store(key, fallback)
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
                store(key, fresh)
            }
            emit(CacheResult.Fresh(
                data = fresh,
                level = freshLevel,
                warnings = warnings
            ))
        } catch (e: CancellationException) {
            // 采集方取消协程必须把取消原样传下去，否则会被当成「网络失败」
            throw e
        } catch (e: Exception) {
            emit(CacheResult.Error(
                message = e.message ?: "network error",
                fallback = cached?.data,
                level = HealthMonitor.HealthLevel.ERROR
            ))
        }
    }

    /**
     * 多 Provider 容灾回退：当前响应不可用时，依次尝试其它已加载 Provider
     * 调用同一方法（经各 Provider `apiMap` 映射）。
     *
     * - 仅返回第一个业务码判定为成功（见 [ApiResponseCodes]）的响应
     * - 每次尝试记录到 [HealthMonitor]（成功时标记 wasFallback）
     */
    private suspend fun tryFallback(method: String, params: Map<String, String>): JsonElement? {
        if (!config.enableFallback) return null
        val ordered = allProviders().toMutableList()
        val current = providerManager.currentProvider
        if (current != null) { ordered.remove(current); ordered.add(0, current) }
        for (provider in ordered) {
            if (provider.id == current?.id) continue // 跳过已失败的当前 Provider
            val mapped = provider.apiMap?.get(method) ?: method
            if (mapped.isEmpty() || mapped.equals("unsupported", ignoreCase = true)) continue
            val start = currentTimeMillis()
            try {
                val raw = provider.callApi(mapped, params)
                val parsed = parseOrNull(raw) ?: continue
                val code = codeOf(parsed)
                val ok = ApiResponseCodes.isSuccess(code)
                HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                    timestamp = start, providerId = provider.id, method = method,
                    durationMs = currentTimeMillis() - start,
                    success = ok, wasFallback = true, fallbackFrom = current?.id,
                    responseCode = code
                ))
                if (ok) return parsed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                    timestamp = start, providerId = provider.id, method = method,
                    durationMs = currentTimeMillis() - start,
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

    /**
     * 取该 method 最近一条告警（从 [HealthMonitor]）。
     *
     * 必须**先按 method 过滤再截断**：曾经写成 `getRecentRecords(limit = 1, onlyWarnings = true)`
     * 等于只取「全站最近一条告警」再按 method 挑，几乎永远挑不中。
     */
    private fun collectRecentWarnings(method: String): List<HealthMonitor.ResponseWarning> =
        HealthMonitor.getRecentRecords(limit = 1, method = method, onlyWarnings = true)
            .firstOrNull()?.responseWarnings ?: emptyList()

    private fun classifyFresh(
        json: JsonElement,
        warnings: List<HealthMonitor.ResponseWarning>
    ): HealthMonitor.HealthLevel {
        val success = ApiResponseCodes.isSuccess(codeOf(json))
        return when {
            !success -> HealthMonitor.HealthLevel.ERROR
            warnings.any { HealthMonitor.classify(it) == HealthMonitor.HealthLevel.ERROR } ->
                HealthMonitor.HealthLevel.ERROR
            warnings.isEmpty() -> HealthMonitor.HealthLevel.OK
            else -> HealthMonitor.HealthLevel.WARNING
        }
    }

    /**
     * 读透层的唯一成功闸门。
     *
     * `song/url/v1/302` 按 RFC 7231 形态返回 `{cookie, level, redirectUrl}`，
     * **没有 code 字段** —— 只要含有效 http(s) URL 即为成功，否则这类响应会被判成
     * 失败而永不写回缓存（与 [cp.player.core.api.MusicApiServiceImpl.callApi] 同一分支）。
     */
    private fun isSuccessResponse(method: String, json: JsonElement): Boolean {
        if (method == MusicApiMethod.SONG_URL_V1_302 && httpUrlOf(json) != null) return true
        return ApiResponseCodes.isSuccess(codeOf(json))
    }

    private fun codeOf(json: JsonElement): Int? =
        ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            ?: ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull

    private fun httpUrlOf(element: JsonElement?): String? {
        when (element) {
            is JsonPrimitive -> {
                val s = element.contentOrNull
                if (s != null && s.startsWith("http")) return s
            }
            is JsonObject -> {
                for (key in listOf("redirectUrl", "url")) {
                    val p = element[key]
                    if (p is JsonPrimitive) {
                        val s = p.contentOrNull
                        if (s != null && s.startsWith("http")) return s
                    }
                }
                for ((_, v) in element) httpUrlOf(v)?.let { return it }
            }
            is JsonArray -> for (e in element) httpUrlOf(e)?.let { return it }
            else -> Unit
        }
        return null
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
        MusicApiMethod.COMMENT_PLAYLIST, MusicApiMethod.COMMENT_ALBUM,
        // 首页发现区的公共内容：新歌速递 / 新碟上架 / 热门歌手 / 精品歌单。
        // 这几张表按天甚至按周才变一次，不缓存的话每次切回首页都要重打一遍 ——
        // 首页一屏会并发十几个请求，这几个是纯公共数据、最没必要重复拉的那部分。
        MusicApiMethod.TOP_SONG, MusicApiMethod.TOP_ALBUM, MusicApiMethod.TOP_ARTISTS,
        MusicApiMethod.TOP_PLAYLIST_HIGHQUALITY -> true
        else -> false
    }
}
