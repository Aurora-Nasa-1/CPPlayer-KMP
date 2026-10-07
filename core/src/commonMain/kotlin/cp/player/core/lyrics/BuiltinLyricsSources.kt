package cp.player.core.lyrics

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.MusicApiService
import cp.player.core.api.amllPlatformFor
import cp.player.core.playback.LyricsParser
import cp.player.core.playback.SidecarLyricLoader
import cp.player.core.playback.TtmlParser
import cp.player.core.playback.SyncedLyricLine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 三条**内置**歌词来源的实现。
 *
 * ### 为什么是 Kotlin 而不是 JS 插件
 * 内置来源要与第三方 Lyrico 插件**接口一致、地位一致**（都在同一个可排序列表里），
 * 但不需要**实现一致**。把 AMLL / 边车 / 音源 API 包成 JS 只会带来 Rhino 求值开销，
 * 并让它们难以访问 Ktor 客户端与平台文件 API。所以这里只实现 [LyricsSource] 接口。
 *
 * ### 与改造前的关系（逐条对齐，避免行为漂移）
 * | 内置源 | 改造前位置 | 命中判据 |
 * |---|---|---|
 * | [SidecarLyricsSource] | `SidecarLyrics.load()`（仅本地曲） | 同目录同名 `.lrc/.ttml/.elrc` 解析出行 |
 * | [AmllLyricsSource] | `fetchFromAmll()` | AMLL 返回 TTML 且解析出行 |
 * | [ProviderLyricsSource] | `api.getLyric()` + `LyricsParser.parse` | 解析出行 |
 */

/**
 * 边车歌词来源（本地曲优先）。
 *
 * ⚠️ **只在 [LyricsRequest.isLocal] 为真时命中**：非本地曲的 `resourceId` 是平台 ID，
 * 拿它去拼文件路径没有意义（且可能误命中同名的本地文件）。
 */
internal class SidecarLyricsSource : LyricsSource {
    override val id = BuiltinLyricsSourceIds.SIDECAR
    override val name = "Sidecar lyrics"
    override val bundled = true
    override val localizeName = true
    override val capabilities = setOf(LyricsCapability.GET_LYRICS)

    override suspend fun fetch(request: LyricsRequest): LyricsSourceResult? {
        if (!request.isLocal) return null
        val resourceId = request.mediaId?.resourceId ?: return null
        val hit = SidecarLyricLoader.load(resourceId) ?: return null
        // 边车命中的 format 由扩展名决定，交给名字里带出来的信息即可：这里只要行。
        return LyricsSourceResult(lines = hit.first)
    }
}

/**
 * AMLL 官方词库来源（api.amll.dev，TTML）。
 *
 * 取词顺序（与改造前 `fetchFromAmll` 完全一致）：
 * 1. 平台 ID 精确取 —— 但**本地曲不做**（`local://` 没有平台 ID），由 [LyricsRequest.isLocal] 判断
 * 2. 标题/歌手/专辑搜索回退
 */
internal class AmllLyricsSource(
    private val client: AmllTtmlClient,
) : LyricsSource {
    override val id = BuiltinLyricsSourceIds.AMLL
    override val name = "AMLL TTML"
    override val bundled = true
    override val localizeName = true

    override val capabilities = setOf(
        LyricsCapability.GET_LYRICS,
        LyricsCapability.LOOKUP_BY_ID,
        LyricsCapability.SEARCH_SONGS,
    )

    override suspend fun fetch(request: LyricsRequest): LyricsSourceResult? {
        val mediaId = request.mediaId
        // 本地曲不能按平台 ID 取（providerId = "local" 不是任何平台）。
        val allowPlatformLookup = !request.isLocal && amllPlatformFor(mediaId?.providerId) != null
        val ttml = client.fetchLyricsTtml(
            providerId = mediaId?.providerId?.takeIf { allowPlatformLookup },
            songId = mediaId?.resourceId,
            name = request.title.ifBlank { null },
            artist = request.artist.ifBlank { null },
            album = request.album.ifBlank { null },
        ) ?: return null
        val lines = TtmlParser.parse(ttml)
        if (lines.isEmpty()) return null
        return LyricsSourceResult(lines = lines)
    }
}

/**
 * 音源（Provider）自带歌词来源 —— `lyric/new` 一类的接口。
 *
 * ### 为什么它是一条「歌词源」而不是音源的能力开关
 * 这是本次融合最核心的一步：把改造前 `LyricsSourceMode` 里那两档
 * （`PROVIDER_ONLY` / `AMLL_FIRST`）自然表达成**列表顺序** ——
 * 把 AMLL 拖到最前 = AMLL 优先；把它关掉 = 仅音源。不再需要枚举语义。
 */
internal class ProviderLyricsSource(
    private val api: MusicApiService,
) : LyricsSource {
    override val id = BuiltinLyricsSourceIds.PROVIDER
    override val name = "Provider lyrics"
    override val bundled = true
    override val localizeName = true
    override val capabilities = setOf(LyricsCapability.GET_LYRICS)

    override suspend fun fetch(request: LyricsRequest): LyricsSourceResult? {
        // 本地曲没有音源歌词可回退（resourceId 是文件路径，不是平台歌曲 ID）。
        if (request.isLocal) return null
        val songId = request.mediaId?.resourceId ?: return null
        val json = api.getLyric(songId)
        val lines = LyricsParser.parse(json)
        if (lines.isEmpty()) return null
        return LyricsSourceResult(lines = lines)
    }

    /** 音源 JSON → 展示用 [cp.player.core.model.LyricsInfo]（保留改造前 `extractLyricsInfo` 的判据）。 */
    internal fun infoOf(json: JsonElement, lines: List<SyncedLyricLine>): ProviderLyricsInfo {
        val obj = json as? JsonObject
        val yrc = ((obj?.get("yrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        val tlyric = ((obj?.get("tlyric") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        val romalrc = ((obj?.get("romalrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        val lrc = ((obj?.get("lrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?: ((obj?.get("klyric") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        return ProviderLyricsInfo(
            format = when {
                !yrc.isNullOrBlank() -> "YRC"
                !lrc.isNullOrBlank() -> "LRC"
                else -> "Unknown"
            },
            hasWordLevel = lines.any { it.words.isNotEmpty() },
            hasTranslation = lines.any { !it.translation.isNullOrBlank() } || !tlyric.isNullOrBlank(),
            hasPhonetic = lines.any { !it.romanization.isNullOrBlank() } || !romalrc.isNullOrBlank(),
        )
    }
}

/** [ProviderLyricsSource.infoOf] 的纯数据结果，避免 core/lyrics 依赖 model 层。 */
internal data class ProviderLyricsInfo(
    val format: String,
    val hasWordLevel: Boolean,
    val hasTranslation: Boolean,
    val hasPhonetic: Boolean,
)
