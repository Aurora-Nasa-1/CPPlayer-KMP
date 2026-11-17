package cp.player.core.api

import cp.player.core.lyrics.LyricsMatchScorer
import cp.player.core.util.MusicSearchSanitizer
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

@Serializable
data class LrclibSongDto(
    val id: Long = 0L,
    val name: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    /** 秒（由歌词最大结束时间戳算出），不是毫秒。 */
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * AMLL 官方词库的 LrcLib 兼容接口客户端（`api.amll.dev/v1/lrclib/…`）。
 *
 * ### 为什么需要它
 * AMLL 词库只收**逐字 TTML**。大量歌只有行级同步歌词（以及没有平台 ID 的本地曲），
 * 这类请求在 `/v1/lyrics/…` 上必然落空。官方在同一个域名下提供了 LrcLib 兼容接口：
 * - `GET /v1/lrclib/get?track_name=&artist_name=` 模糊匹配**一首**；
 * - 未命中返回 404，直接当作「没有」。
 *
 * 它返回的是 LRC，解析后仍然是行级歌词 —— 对「TTML 没有、但至少有行级词」的场景，
 * 这是最省事且不需要额外第三方的补位方式。
 *
 * ### 与 [AmllTtmlClient] 的缓存关系
 * 这里只缓存**命中结果**（按歌名+歌手+专辑+时长），不缓存「没找到」：
 * 歌曲元数据（尤其本地文件标签）常被用户手动修正，负缓存会让改完标签后仍然拿不到词。
 */
class LrclibLyricsClient(
    baseUrl: String = AmllTtmlClient.DEFAULT_BASE_URL,
) {
    private val apiBase: String = baseUrl.trimEnd('/') + "/v1"

    private val client: HttpClient by lazy { createHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    private val memo = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_MEMO_SIZE
    }

    /**
     * 取同步 LRC 原文。
     *
     * @return 命中的 LRC；没有匹配 / 网络失败返回 null
     */
    suspend fun fetchSyncedLyrics(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long = 0L,
    ): String? = withContext(Dispatchers.IO) {
        val sanitized = MusicSearchSanitizer.sanitize(title, artist)
        val name = sanitized.title.takeIf { it.isNotBlank() } ?: return@withContext null
        val mainArtist = sanitized.artist.takeIf { it.isNotBlank() }

        val key = listOf(
            name.lowercase(),
            mainArtist.orEmpty().lowercase(),
            album.orEmpty().lowercase(),
            durationMs.toString(),
        ).joinToString("|")
        cached(key)?.let { return@withContext it }

        val params = buildList {
            add("track_name" to name)
            if (mainArtist != null) add("artist_name" to mainArtist)
            if (!album.isNullOrBlank()) add("album_name" to album)
        }
        // `get` 的 artist_name 是必填项；没有歌手时直接走 search，避免一次注定 400 的请求。
        val first = if (mainArtist != null) requestGet(params) else null
        if (first != null) {
            val hit = pickBest(listOf(first), name, artist, album, durationMs)
            if (hit != null) {
                memoPut(key, hit)
                return@withContext hit
            }
        }

        // `get` 是模糊匹配，含糊时会挑错版本；再用 `search` 拿候选按本仓评分器选一次。
        // 即使没有歌手也继续：本地文件标签常常缺 artist，LrcLib 至少还能按歌名 + 时长兜底。
        val results = requestSearch(params + ("pageSize" to "10")) ?: return@withContext null
        val best = pickBest(results, name, artist, album, durationMs) ?: return@withContext null
        memoPut(key, best)
        best
    }

    /** 用本仓的评分器在候选里挑最优；没有达到门槛就认「没匹配」。 */
    private fun pickBest(
        songs: List<LrclibSongDto>,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long,
    ): String? {
        if (songs.isEmpty()) return null
        val target = LyricsMatchScorer.Target(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )
        val best = LyricsMatchScorer.findBestMatch(target, songs.filter { !it.instrumental }) { song ->
            LyricsMatchScorer.Candidate(
                titles = listOfNotNull(song.trackName, song.name).distinct().ifEmpty { listOf("") },
                artists = listOfNotNull(song.artistName?.takeIf { it.isNotBlank() }),
                albums = listOfNotNull(song.albumName?.takeIf { it.isNotBlank() }),
                durationMs = (song.duration * 1_000.0).toLong(),
            )
        } ?: return null
        return best.syncedLyrics?.takeIf { it.isNotBlank() }
    }

    private suspend fun requestGet(params: List<Pair<String, String>>): LrclibSongDto? {
        val (code, body) = httpGet("$apiBase/lrclib/get", params)
        if (body == null || code !in 200..299) return null
        return runCatching { json.decodeFromString<LrclibSongDto>(body) }.getOrNull()
    }

    private suspend fun requestSearch(params: List<Pair<String, String>>): List<LrclibSongDto>? {
        val (code, body) = httpGet("$apiBase/lrclib/search", params)
        if (body == null || code !in 200..299) return null
        return runCatching { json.decodeFromString<List<LrclibSongDto>>(body) }.getOrNull()
    }

    /** GET + 429 退避一次；返回 `(状态码, 响应体)`，网络异常为 `(0, null)`。 */
    private suspend fun httpGet(url: String, params: List<Pair<String, String>>): Pair<Int, String?> {
        repeat(2) { attempt ->
            val code: Int
            val body: String
            try {
                val response = client.get(url) { params.forEach { (key, value) -> parameter(key, value) } }
                code = response.status.value
                body = response.bodyAsText()
            } catch (e: CancellationException) {
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

    private fun cached(key: String): String? = synchronized(memo) { memo[key] }

    private fun memoPut(key: String, lyrics: String) {
        synchronized(memo) { memo[key] = lyrics }
    }

    fun close() {
        runCatching { client.close() }
    }

    private companion object {
        const val MAX_MEMO_SIZE = 64
        const val RETRY_BACKOFF_MS = 1_000L
    }
}
