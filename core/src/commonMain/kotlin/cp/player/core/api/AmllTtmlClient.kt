package cp.player.core.api

import cp.player.core.util.SettingsStorage
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
 * 缓存策略（官方建议，见 https://amll.dev/reference/http-api/overview）：
 * - 按 id / filename 取到的内容**永久不变** → 内存 LRU + [diskCache] 持久层
 * - 搜索结果可能随词库更新变化 → 不持久化，只做本次会话内存缓存
 * - 404 记入负缓存，避免重复打 404
 *
 * 限流：单 IP 50 rps；客户端每曲最多 2 个请求，触发 429 时退避重试一次。
 *
 * @param diskCache 可选持久层（键值存储）。传 null 则只有内存缓存。
 */
class AmllTtmlClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val diskCache: SettingsStorage? = null,
) {

    private val client: HttpClient by lazy { createHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /** 内存 LRU：key → TTML 原文 */
    private val memo = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_MEMO_SIZE
        }
    }

    /** 已知不存在的 key，避免重复打 404 */
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
    ): String? = withContext(Dispatchers.IO) {
        val platform = amllPlatformFor(providerId)

        // 1) 平台 ID 精确取
        if (platform != null && !songId.isNullOrBlank()) {
            val key = "p:${platform.name}:$songId"
            cachedTtml(key)?.let { return@withContext it }
            if (isNotFound(key)) return@withContext searchFallback(platform, songId, name, artist, album)
            getByPlatform(platform, songId)?.let { item ->
                val ttml = item.lyrics?.takeIf { it.isNotBlank() }
                if (ttml != null) {
                    memoPut(key, ttml)
                    diskPut(key, ttml)
                }
                return@withContext ttml
                    ?: searchFallback(platform, songId, name, artist, album)
            }
            notFoundAdd(key)
            return@withContext searchFallback(platform, songId, name, artist, album)
        }

        // 2) 无平台 ID：只走搜索
        searchFallback(platform, songId, name, artist, album)
    }

    /** 搜索回退：标题/歌手/专辑命中后按 id 精确取全文。 */
    private suspend fun searchFallback(
        platform: AmllPlatform?,
        songId: String?,
        name: String?,
        artist: String?,
        album: String?,
    ): String? {
        if (name.isNullOrBlank()) return null
        // ⚠️ 搜索键必须带歌手：本地曲目 platform / songId 都是 null，只按歌名会撞车 ——
        // 不同歌手的同名曲共用同一 key，会把**错误的歌词**当作正确结果缓存并落盘。
        val searchKey = "s:$platform:$songId:${name.lowercase()}:${artist.orEmpty().lowercase()}"
        cachedTtml(searchKey)?.let { return it }

        val params = buildList {
            add("musicName" to name)
            if (!artist.isNullOrBlank()) add("artistName" to artist)
            if (!album.isNullOrBlank()) add("albumName" to album)
        }
        val items = search(params) ?: return null
        val hit = items.firstOrNull() ?: run {
            notFoundAdd(searchKey)
            return null
        }
        if (hit.lyrics != null && hit.lyrics.isNotBlank()) {
            // 防御：协议规定搜索不返回歌词，这里兜一层以防服务端变化
            memoPut(searchKey, hit.lyrics)
            return hit.lyrics
        }
        val ttml = getById(hit.id)?.lyrics?.takeIf { it.isNotBlank() } ?: run {
            notFoundAdd(searchKey)
            return null
        }
        memoPut(searchKey, ttml)
        // 命中的条目有稳定 id/filename，同时写进平台键与磁盘（内容按 id 永久不变）
        diskPut(searchKey, ttml)
        hit.filename?.let { fn -> diskPut("f:$fn", ttml) }
        return ttml
    }

    /** `GET /v1/lyrics/get?<queryParam>=<songId>`，HTTP/业务失败返回 null。 */
    internal suspend fun getByPlatform(platform: AmllPlatform, songId: String): AmllSongItemDto? {
        val text = httpGet("$BASE_API/lyrics/get", listOf(platform.queryParam to songId)) ?: return null
        return runCatching { json.decodeFromString<AmllGetResponseDto>(text) }
            .getOrNull()
            ?.takeIf { it.status == 200 }
            ?.data
    }

    internal suspend fun getById(id: Long): AmllSongItemDto? {
        val text = httpGet("$BASE_API/lyrics/get", listOf("id" to id.toString())) ?: return null
        return runCatching { json.decodeFromString<AmllGetResponseDto>(text) }
            .getOrNull()
            ?.takeIf { it.status == 200 }
            ?.data
    }

    /** `GET /v1/lyrics/search`。失败返回 null（调用方回退音源 API）。 */
    internal suspend fun search(params: List<Pair<String, String>>): List<AmllSongItemDto>? {
        val text = httpGet("$BASE_API/lyrics/search", params + ("pageSize" to "10")) ?: return null
        return runCatching { json.decodeFromString<AmllSearchResponseDto>(text) }
            .getOrNull()
            ?.takeIf { it.status == 200 }
            ?.data
            ?.items
    }

    /**
     * GET + 429 退避重试一次。返回响应体文本；HTTP 非 2xx 或网络失败返回 null。
     */
    private suspend fun httpGet(url: String, params: List<Pair<String, String>>): String? {
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
                return null
            }
            when {
                code == 429 && attempt == 0 -> delay(RETRY_BACKOFF_MS)
                code in 200..299 -> return body
                else -> return null
            }
        }
        return null
    }

    // ============ 缓存 ============

    private fun cachedTtml(key: String): String? {
        synchronized(memo) { memo[key] }?.let { return it }
        // 磁盘命中后回填内存（按 id/filename 的内容官方保证不变，可放心长存）
        val disk = diskCache?.getString(diskKey(key))?.takeIf { it.isNotBlank() } ?: return null
        synchronized(memo) { memo[key] = disk }
        return disk
    }

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

    /**
     * 磁盘持久缓存（只存「内容永久不变」的键）。
     *
     * [SettingsStorage] 是 KV 接口，没有按前缀枚举能力，用索引键维护 LRU：
     * 索引是换行分隔的键列表，超容量时从最旧一端淘汰（连同其数据键）。
     */
    private fun diskPut(key: String, ttml: String) {
        val disk = diskCache ?: return
        // ⚠️ 「读索引 → 改 → 写回」不是原子操作。本函数可能被多个歌词协程并发调用
        // （多首预取 / 快速切歌），不加锁会互相覆盖索引 ⇒ 条目丢失、数据键成为孤儿
        // （占空间且永远回收不到）。整个读改写必须串行。
        synchronized(this) {
            val dataKey = diskKey(key)
            val indexRaw = disk.getString(INDEX_KEY) ?: ""
            val index = indexRaw.split('\n').filter { it.isNotBlank() }.toMutableList()
            if (disk.getString(dataKey) == null) {
                index.add(key)
            }
            while (index.size > MAX_DISK_ENTRIES) {
                val evicted = index.removeAt(0)
                disk.remove(diskKey(evicted))
            }
            disk.putString(dataKey, ttml)
            disk.putString(INDEX_KEY, index.joinToString("\n"))
        }
    }

    private fun diskKey(key: String): String = "ttml:$key"

    /** 清空内存缓存（磁盘缓存内容按官方承诺不变，不清）。 */
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.amll.dev"
        private const val BASE_API = "$DEFAULT_BASE_URL/v1"
        private const val MAX_MEMO_SIZE = 100
        private const val MAX_NOT_FOUND_SIZE = 500
        private const val MAX_DISK_ENTRIES = 15
        private const val INDEX_KEY = "ttml:index"
        private const val RETRY_BACKOFF_MS = 1_000L
    }
}
