/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/source/PluginSourceModels.kt
 * Changes: package renamed to cp.player.core.lyricsplugin; Android File type replaced by a
 *          platform-neutral cache dir path; see THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricsplugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 发给插件 `searchSongs(request)` 的请求体（JSON 编码后传入 JS）。
 *
 * 字段名必须与 Lyrico Plugin API 一致 —— 插件是按这些名字读参数的。
 */
@Serializable
data class PluginSearchSongsRequest(
    val keyword: String,
    val page: Int = 1,
    val pageSize: Int = 20,
    val separator: String = "/",
    val config: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginGetLyricsRequest(
    val song: PluginSongRequest,
    val page: Int = 1,
    val pageSize: Int = 20,
    val config: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginSearchCoversRequest(
    val keyword: String,
    val song: PluginSongRequest? = null,
    val page: Int = 1,
    val pageSize: Int = 5,
    val config: Map<String, String> = emptyMap(),
)

/** 插件侧看到的歌曲对象（`getLyrics` 入参的 `song`）。 */
@Serializable
data class PluginSongRequest(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val date: String = "",
    val duration: Long,
    val sourceId: String,
    val pluginId: String,
    val fields: Map<String, String> = emptyMap(),
    val internal: Map<String, String> = emptyMap(),
)

/** 插件 `searchSongs` 返回的一条候选（已解析、宿主侧模型）。 */
data class PluginSongSearchResult(
    val id: String,
    val pluginId: String,
    val pluginName: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    val date: String = "",
    val trackNumber: String = "",
    val picUrl: String = "",
    val fields: Map<String, String> = emptyMap(),
    val internal: Map<String, String> = emptyMap(),
)

/** 一次搜索命中，带来源插件信息（用于 UI 展示「来自哪个插件」）。 */
data class PluginSearchHit(
    val sourceId: String,
    val sourceName: String,
    val song: PluginSongSearchResult,
)

/**
 * 插件返回的歌词负载。
 *
 * [payloadType] 决定后续用哪条字段；结构化负载用 [original]/[translated]/[romanization]，
 * 其余为原始文本（LRC/TTML），直接交给本仓库已有的解析器。
 */
data class PluginLyricsResult(
    val tags: Map<String, String> = emptyMap(),
    val original: List<PluginLyricsLine> = emptyList(),
    val translated: List<PluginLyricsLine>? = null,
    val romanization: List<PluginLyricsLine>? = null,
    val payloadType: PluginLyricsPayloadType = PluginLyricsPayloadType.STRUCTURED,
    val rawPlainLrc: String = "",
    val rawVerbatimLrc: String = "",
    val rawEnhancedLrc: String = "",
    val rawTtml: String = "",
    val rawMultiPersonEnhancedLrc: String = "",
)

data class PluginLyricsWord(
    val start: Long,
    val end: Long,
    val text: String,
)

data class PluginLyricsLine(
    val start: Long,
    val end: Long,
    val words: List<PluginLyricsWord>,
) {
    val text: String get() = words.joinToString("") { it.text }
}

enum class PluginLyricsPayloadType {
    STRUCTURED,
    RAW_PLAIN_LRC,
    RAW_VERBATIM_LRC,
    RAW_ENHANCED_LRC,
    RAW_TTML,
    RAW_MULTI_PERSON_ENHANCED_LRC,
}

/**
 * 一个已载入的插件源。
 *
 * @param assetDir 插件目录的绝对路径（导入型）或 `asset://…`（内置型）
 * @param script 合并 include 后的可执行脚本
 * @param cacheRootDir 插件 `Platform.cache.*` 的落盘根目录
 */
data class LyricoPluginSource(
    val manifest: PluginManifest,
    val assetDir: String,
    val script: String,
    val cacheRootDir: String? = null,
    val bundled: Boolean = false,
)

/** 插件 JSON 编解码统一配置（与上游一致）。 */
val pluginJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
