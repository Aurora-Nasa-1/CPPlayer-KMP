package cp.player.core.api

import cp.player.core.lyrics.LyricsMatchScorer
import cp.player.core.util.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 歌词来源模式（对应旧版 CPPlayer 的三档设置，默认 AMLL 优先）。
 *
 * - [PROVIDER_ONLY]：只走音源 API（网易云 lyric/new 一类）
 * - [AMLL_FIRST]：先试 AMLL TTML（官方词库 API），无匹配回退音源 API
 * - [AMLL_ONLY]：只走 AMLL TTML，无匹配即无歌词
 */
enum class LyricsSourceMode(val key: String) {
    PROVIDER_ONLY("provider_only"),
    AMLL_FIRST("amll_first"),
    AMLL_ONLY("amll_only");

    companion object {
        /** SettingsStorage 的持久化键（app 侧设置页与 core 侧 controller 共用）。 */
        const val SETTINGS_KEY = "lyrics_source_mode"

        fun fromKey(raw: String?): LyricsSourceMode =
            entries.firstOrNull { it.key == raw } ?: AMLL_FIRST
    }
}

/**
 * 音源 providerId → AMLL 平台参数名映射。
 *
 * 取代旧版靠 provider 显示名称字符串猜测平台的启发式：
 * KMP 侧 [cp.player.core.music.CPMediaId.providerId] 是受控枚举式标识，直接映射即可。
 */
enum class AmllPlatform(val queryParam: String) {
    NCM("ncmMusicId"),
    QQ("qqMusicId"),
    APPLE("appleMusicId"),
    SPOTIFY("spotifyId"),
}

fun amllPlatformFor(providerId: String?): AmllPlatform? {
    if (providerId.isNullOrBlank()) return null
    return when (providerId.lowercase()) {
        "netease", "ncm" -> AmllPlatform.NCM
        "qq", "qqmusic", "tencent" -> AmllPlatform.QQ
        "apple", "applemusic", "apple_music", "am" -> AmllPlatform.APPLE
        "spotify" -> AmllPlatform.SPOTIFY
        else -> null
    }
}

// ============ OpenAPI DTO（https://amll.dev/api/ttml/openapi.yaml） ============

@Serializable
data class AmllSongItemDto(
    val id: Long = 0L,
    val filename: String? = null,
    val musicNames: List<String> = emptyList(),
    val artistNames: List<String> = emptyList(),
    val albumNames: List<String> = emptyList(),
    val ncmMusicIds: List<String> = emptyList(),
    val qqMusicIds: List<String> = emptyList(),
    val appleMusicIds: List<String> = emptyList(),
    val spotifyIds: List<String> = emptyList(),
    /** 搜索接口固定不带歌词（null），只有 get 接口有。 */
    val lyrics: String? = null,
)

@Serializable
data class AmllGetResponseDto(val status: Int = 0, val data: AmllSongItemDto? = null)

@Serializable
data class AmllSearchDataDto(val items: List<AmllSongItemDto> = emptyList())

@Serializable
data class AmllSearchResponseDto(val status: Int = 0, val data: AmllSearchDataDto? = null)

/**
 * AMLL TTML DataBase 官方 API 客户端（https://api.amll.dev）。
 *
 * 取词顺序（对齐并超越旧版直抓 GitHub raw 的实现）：
 * 1. 平台歌曲 ID 精确取：`/v1/lyrics/get?<platform>=<songId>`
 * 2. 标题/歌手/专辑搜索回退：`/v1/lyrics/search` → 命中后按 `id` 精确取
 *    （旧版没有搜索能力，本地歌曲 / 无平台 ID 的歌拿不到 TTML，这里补上了）
 *
 * ### 缓存只有内存一层（刻意）
 * 按 id / filename 取到的内容官方保证不变，所以 [memo] 里存一份就够本会话反复切歌用。
 * 之前还有一层 `SettingsStorage` 磁盘缓存，已经删掉：
 * - 它把 TTML 原文塞进 properties 文件 ⇒ **每次写入都要整文件重写**（条目越多越慢）；
 * - 容量上限只有十几条（再大就更慢），实际命中率极低；
 * - 真离线时音源本身也拿不到音频，磁盘里那点歌词并没有使用场景。
 *
 * 限流：单 IP 50 rps；客户端每曲最多 2 个请求，触发 429 时退避重试一次。
 */
class AmllTtmlClient(
    baseUrl: String = DEFAULT_BASE_URL,
) {
    /** 由 [baseUrl] 派生，**不要**在方法里再引用常量：换镜像时构造参数必须生效。 */
    private val apiBase: String = baseUrl.trimEnd('/') + "/v1"

    private val client: HttpClient by lazy { createHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /** 内存 LRU：key → TTML 原文 */
    private val memo = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_MEMO_SIZE
        }
    }

    /** 已知不存在的 key，避免重复打 404。**只收「上游明确说没有」的结论**，见 [Lookup]。 */
    private val notFound = mutableSetOf<String>()

    /**
     * 取 TTML 歌词原文。
     *
     * @param providerId 音源 providerId（netease/qq/apple/spotify...），无法映射平台时忽略；
     *                   传 null 表示跳过平台 ID 精确取（本地歌曲）只走搜索。
     * @param songId 音源平台歌曲 ID
     * @param name 歌名（搜索回退用，可空）
     * @param artist 歌手（搜索回退用，可空）
     * @param album 专辑（搜索回退用，可空）
     * @return TTML 原文；拿不到返回 null
     */
    suspend fun fetchLyricsTtml(
        providerId: String?,
        songId: String?,
        name: String?,
        artist: String?,
        album: String?,
        durationMs: Long = 0L,
    ): String? = withContext(Dispatchers.IO) {
        val platform = amllPlatformFor(providerId)

        // 1) 平台 ID 精确取
        if (platform != null && !songId.isNullOrBlank()) {
            val key = "p:${platform.name}:$songId"
            cachedTtml(key)?.let { return@withContext it }
            if (isNotFound(key)) return@withContext searchFallback(platform, songId, name, artist, album, durationMs)
            val lookup = lookupByPlatform(platform, songId)
            val item = lookup.item
            if (item == null) {
                // ⚠️ 只有「明确没有」才能进负缓存。网络超时 / 5xx 也走这里，
                // 若一并记成「这首歌没词」，弱网时听过的那批歌会在整个进程内永远无词。
                if (lookup.absent) notFoundAdd(key)
                return@withContext searchFallback(platform, songId, name, artist, album, durationMs)
            }
            val ttml = item.lyrics?.takeIf { it.isNotBlank() }
            if (ttml != null) {
                memoPut(key, ttml)
                return@withContext ttml
            }
            notFoundAdd(key)
            return@withContext searchFallback(platform, songId, name, artist, album, durationMs)
        }

        // 2) 无平台 ID：只走搜索
        searchFallback(platform, songId, name, artist, album, durationMs)
    }

    /** 搜索回退：标题/歌手/专辑命中后按 id 精确取全文。 */
    private suspend fun searchFallback(
        platform: AmllPlatform?,
        songId: String?,
        name: String?,
        artist: String?,
        album: String?,
        durationMs: Long = 0L,
    ): String? {
        if (name.isNullOrBlank()) return null
        // ⚠️ 搜索键必须带歌手：本地曲目 platform / songId 都是 null，只按歌名会撞车 ——
        // 不同歌手的同名曲共用同一 key，会把**错误的歌词**当作正确结果缓存。
        val searchKey = "s:$platform:$songId:${name.lowercase()}:${artist.orEmpty().lowercase()}"
        cachedTtml(searchKey)?.let { return it }

        val params = buildList {
            add("musicName" to name)
            if (!artist.isNullOrBlank()) add("artistName" to artist)
            if (!album.isNullOrBlank()) add("albumName" to album)
        }
        val items = search(params) ?: return null
        val target = LyricsMatchScorer.Target(
            title = name,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )
        val hit = LyricsMatchScorer.findBestMatch(
            target = target,
            candidates = items,
        ) { item ->
            LyricsMatchScorer.Candidate(
                titles = item.musicNames.ifEmpty { listOfNotNull(item.filename) },
                artists = item.artistNames,
                albums = item.albumNames,
                durationMs = 0L,
            )
        } ?: run {
            notFoundAdd(searchKey)
            return null
        }
        if (hit.lyrics != null && hit.lyrics.isNotBlank()) {
            // 防御：协议规定搜索不返回歌词，这里兜一层以防服务端变化
            memoPut(searchKey, hit.lyrics)
            return hit.lyrics
        }
        val lookup = lookupById(hit.id)
        val ttml = lookup.item?.lyrics?.takeIf { it.isNotBlank() }
        if (ttml == null) {
            if (lookup.absent) notFoundAdd(searchKey)
            return null
        }
        memoPut(searchKey, ttml)
        return ttml
    }

    /** `GET /v1/lyrics/get?<queryParam>=<songId>`。 */
    private suspend fun lookupByPlatform(platform: AmllPlatform, songId: String): Lookup =
        lookup(listOf(platform.queryParam to songId))

    private suspend fun lookupById(id: Long): Lookup = lookup(listOf("id" to id.toString()))

    /** `GET /v1/lyrics/get` 的统一解析 + **三态**判定（见 [Lookup]）。 */
    private suspend fun lookup(params: List<Pair<String, String>>): Lookup {
        val (code, body) = httpGet("$apiBase/lyrics/get", params)
        if (body == null) return Lookup(null, absent = code == 404 || code == 204)
        val dto = runCatching { json.decodeFromString<AmllGetResponseDto>(body) }.getOrNull()
        val item = dto?.takeIf { it.status == 200 }?.data
        // 200 但没有数据 / status 非 200：上游明确表示「查无此词」。
        return Lookup(item, absent = item == null)
    }

    /** `GET /v1/lyrics/search`。失败返回 null（调用方回退音源 API）。 */
    internal suspend fun search(params: List<Pair<String, String>>): List<AmllSongItemDto>? {
        val (_, body) = httpGet("$apiBase/lyrics/search", params + ("pageSize" to "10"))
        if (body == null) return null
        return runCatching { json.decodeFromString<AmllSearchResponseDto>(body) }
            .getOrNull()
            ?.takeIf { it.status == 200 }
            ?.data
            ?.items
    }

    /**
     * GET + 429 退避重试一次。
     *
     * @return `(HTTP 状态码, 响应体)`；网络异常时状态码为 0、响应体为 null。
     *   调用方靠这个码区分「404 = 真没有」与「超时 = 不知道」。
     */
    private suspend fun httpGet(url: String, params: List<Pair<String, String>>): Pair<Int, String?> {
        repeat(2) { attempt ->
            val code: Int
            val body: String
            try {
                val resp = client.get(url) {
                    params.forEach { (k, v) -> parameter(k, v) }
                }
                code = resp.status.value
                body = resp.bodyAsText()
            } catch (e: CancellationException) {
                // ⚠️ 必须先于 Exception 捕获：切歌 / 退出页面会取消歌词协程，
                // 若把取消当「网络失败」吞掉，调用方会当成拿不到歌词而**再发一次**搜索请求
                // —— 取消被延迟，且已取消的协程继续联网。取消必须原样上抛。
                throw e
            } catch (_: Exception) {
                return 0 to null
            }
            when {
                code == 429 && attempt == 0 -> delay(RETRY_BACKOFF_MS)
                code in 200..299 -> return code to body
                else -> return code to null
            }
        }
        return 0 to null
    }

    // ============ 缓存 ============

    private fun cachedTtml(key: String): String? = synchronized(memo) { memo[key] }

    private fun memoPut(key: String, ttml: String) {
        synchronized(memo) { memo[key] = ttml }
    }

    private fun notFoundAdd(key: String) {
        synchronized(notFound) {
            if (notFound.size >= MAX_NOT_FOUND_SIZE) notFound.clear()
            notFound.add(key)
        }
    }

    /** 负缓存查询也必须加锁：写入在别的歌词协程里，裸读会有集合并发问题。 */
    private fun isNotFound(key: String): Boolean = synchronized(notFound) { key in notFound }

    /** 清空内存缓存。 */
    fun clearMemo() {
        synchronized(memo) { memo.clear() }
        synchronized(notFound) { notFound.clear() }
    }

    /**
     * 释放底层 HTTP 客户端（连接池 + 线程）。
     *
     * 持有方（`MusicBackend`）应在重建 / 退出时调用，否则每次重建都会残留一套连接池与线程。
     */
    fun close() {
        runCatching { client.close() }
    }

    /**
     * 一次取词的结果。
     *
     * [absent] 区分**结论**与**失败**：上游明确说「没有这首」（404 / 200 但空）才为 true，
     * 网络异常与 5xx 一律 false —— 只有前者允许写进 [notFound]。
     */
    private class Lookup(val item: AmllSongItemDto?, val absent: Boolean)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.amll.dev"
        private const val MAX_MEMO_SIZE = 100
        private const val MAX_NOT_FOUND_SIZE = 500
        private const val RETRY_BACKOFF_MS = 1_000L
    }
}
