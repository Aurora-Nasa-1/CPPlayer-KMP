/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/source/PluginJsonParser.kt
 * Changes: package renamed; result conversion targets cp.player.core.playback.SyncedLyricLine and
 *          reuses this repo's TtmlParser / LyricsParser instead of upstream's own LRC writers;
 *          raw-lyric rendering back into LRC/TTML was dropped (not needed for this integration).
 *          See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricsplugin

import cp.player.core.playback.LyricsParser
import cp.player.core.playback.SyncedLyricLine
import cp.player.core.playback.TtmlParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 把插件返回的 JSON 解析成宿主模型。
 *
 * 只做「宽容解析」：字段名有多个候选（插件生态里同一含义常有多套命名），
 * 解析不出就跳过该条，绝不抛异常 —— 一个插件的脏数据不该拖垮整次搜索。
 */
class LyricsPluginJsonParser(private val json: Json = pluginJson) {

    fun parseSongResults(rawJson: String, pluginId: String, pluginName: String): List<PluginSongSearchResult> =
        parseSongResultItems(rawJson, pluginId, pluginName, requireId = true)

    private fun parseSongResultItems(
        rawJson: String,
        pluginId: String,
        pluginName: String,
        requireId: Boolean,
    ): List<PluginSongSearchResult> {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("items", "results", "songs", "data") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val coverUrl = obj.string("picUrl", "coverUrl", "cover_url", "artworkUrl").orEmpty()
            val id = obj.string("id", "songId", "trackId")
                ?: if (requireId) return@mapIndexedNotNull null else coverUrl.ifBlank { "$pluginId:cover:$index" }
            PluginSongSearchResult(
                id = id,
                pluginId = pluginId,
                pluginName = pluginName,
                title = obj.string("title", "name", "songName").orEmpty(),
                artist = obj.string("artist", "artists", "singer").orEmpty(),
                album = obj.string("album", "albumName").orEmpty(),
                duration = obj.long("duration", "durationMs", "duration_ms") ?: 0L,
                date = obj.string("year", "date", "releaseDate", "release_date").orEmpty(),
                trackNumber = obj.string("trackNumber", "trackerNumber", "track_number").orEmpty(),
                picUrl = coverUrl,
                fields = obj.stringMap("fields", "metadata").orEmpty(),
                // internal 是插件给 getLyrics 用的私有透传数据：限长限宽，避免被塞进超大对象
                internal = obj.stringMap("internal").orEmpty()
                    .filter { (key, value) -> key.isNotBlank() && key.length <= 64 && value.length <= 4096 }
                    .entries.take(64).associate { it.key to it.value },
            )
        }
    }

    /** 解析 `getLyrics` 的返回；拿不到可用负载返回 null。 */
    fun parseLyrics(rawJson: String): PluginLyricsResult? =
        parseLyricsElement(runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return null)

    private fun parseLyricsElement(root: JsonElement): PluginLyricsResult? {
        if (root is JsonArray) {
            // API 4 允许 getLyrics 返回多个候选；本宿主取第一个可用项。
            return root.firstNotNullOfOrNull { parseLyricsElement(it) }
        }
        if (root is JsonNull) return null
        if (root is JsonPrimitive) {
            val lrc = root.contentOrNull.orEmpty()
            return lrc.takeIf { it.isNotBlank() }?.let {
                PluginLyricsResult(payloadType = PluginLyricsPayloadType.RAW_PLAIN_LRC, rawPlainLrc = it)
            }
        }
        val obj = root as? JsonObject ?: return null
        if (obj.boolean("notFound") == true) return null

        val tags = obj.stringMap("tags").orEmpty()
        val payloadType = obj.primitiveString("type")?.toPayloadType() ?: PluginLyricsPayloadType.STRUCTURED
        val rawPlain = obj.primitiveString(
            "rawPlainLrc", "raw_plain_lrc", "plainLrc", "plain_lrc", "lrc", "originalLrc", "original_lrc",
        ).orEmpty()
        val rawOriginal = obj.primitiveString("original").orEmpty()
        val rawVerbatim = obj.primitiveString("rawVerbatimLrc", "raw_verbatim_lrc").orEmpty()
        val rawEnhanced = obj.primitiveString("rawEnhancedLrc", "raw_enhanced_lrc").orEmpty()
        val rawTtml = obj.primitiveString("rawTtml", "raw_ttml").orEmpty()
        val rawMulti = obj.primitiveString("rawMultiPersonEnhancedLrc", "raw_multi_person_enhanced_lrc").orEmpty()

        if (payloadType != PluginLyricsPayloadType.STRUCTURED) {
            val plain = rawPlain.ifBlank { rawOriginal }
            val hasRaw = when (payloadType) {
                PluginLyricsPayloadType.RAW_PLAIN_LRC -> plain.isNotBlank()
                PluginLyricsPayloadType.RAW_VERBATIM_LRC -> rawVerbatim.isNotBlank()
                PluginLyricsPayloadType.RAW_ENHANCED_LRC -> rawEnhanced.isNotBlank()
                PluginLyricsPayloadType.RAW_TTML -> rawTtml.isNotBlank()
                PluginLyricsPayloadType.RAW_MULTI_PERSON_ENHANCED_LRC -> rawMulti.isNotBlank()
                PluginLyricsPayloadType.STRUCTURED -> false
            }
            if (!hasRaw) return null
            return PluginLyricsResult(
                tags = tags,
                payloadType = payloadType,
                rawPlainLrc = plain,
                rawVerbatimLrc = rawVerbatim,
                rawEnhancedLrc = rawEnhanced,
                rawTtml = rawTtml,
                rawMultiPersonEnhancedLrc = rawMulti,
            )
        }

        val original = obj.array("original", "lines").parseWordLines()
        if (original.isEmpty()) return null
        return PluginLyricsResult(
            tags = tags,
            original = original,
            translated = obj.array("translated", "translation", "translations").parseTextLines().takeIf { it.isNotEmpty() },
            romanization = obj.array("romanization", "romanized", "roma").parseWordLines().takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        /**
         * 把插件歌词负载转成播放管线使用的 [SyncedLyricLine]。
         *
         * - 原始文本负载（LRC / 增强 LRC / TTML）交给本仓库已有的解析器；
         * - 结构化负载直接映射，翻译 / 罗马音按时间就近（±1.5s）配对。
         */
        fun toSyncedLines(result: PluginLyricsResult): List<SyncedLyricLine> {
            if (result.payloadType != PluginLyricsPayloadType.STRUCTURED) {
                val raw = result.rawTtml
                    .ifBlank { result.rawEnhancedLrc }
                    .ifBlank { result.rawMultiPersonEnhancedLrc }
                    .ifBlank { result.rawVerbatimLrc }
                    .ifBlank { result.rawPlainLrc }
                if (raw.isBlank()) return emptyList()
                // TTML 走 TTML 解析器；增强 / 逐字 LRC 走逐字解析器（不丢词级时间戳）；
                // 其余按行级 LRC 解析。
                return when (result.payloadType) {
                    PluginLyricsPayloadType.RAW_TTML -> TtmlParser.parse(raw)
                    PluginLyricsPayloadType.RAW_ENHANCED_LRC,
                    PluginLyricsPayloadType.RAW_VERBATIM_LRC,
                    PluginLyricsPayloadType.RAW_MULTI_PERSON_ENHANCED_LRC,
                    -> LyricsParser.parseEnhancedLrc(raw)
                    else -> LyricsParser.parseLrc(raw)
                }
            }
            return result.original.sortedBy { it.start }.mapNotNull { line ->
                val text = line.text
                if (text.isBlank()) return@mapNotNull null
                val translation = result.translated.nearestLine(line.start)?.text?.trim()?.takeIf { it.isNotBlank() }
                val roman = result.romanization.nearestLine(line.start)
                SyncedLyricLine(
                    time = line.start,
                    text = text,
                    endTime = line.end,
                    translation = translation,
                    romanization = roman?.text?.trim()?.takeIf { it.isNotBlank() },
                    words = line.words.map { SyncedLyricLine.SyncedWord(it.text, it.start, it.end) },
                )
            }
        }
    }
}

fun PluginSongSearchResult.toPluginSongRequest(): PluginSongRequest =
    PluginSongRequest(
        id = id,
        title = title,
        artist = artist,
        album = album,
        date = date,
        duration = duration,
        sourceId = pluginId,
        pluginId = pluginId,
        fields = fields,
        internal = internal,
    )

private fun List<PluginLyricsLine>?.nearestLine(start: Long): PluginLyricsLine? =
    this?.minByOrNull { kotlin.math.abs(it.start - start) }
        ?.takeIf { kotlin.math.abs(it.start - start) <= 1500L }

private fun JsonArray?.parseWordLines(): List<PluginLyricsLine> = this?.mapNotNull { element ->
    val line = element as? JsonArray ?: return@mapNotNull null
    val start = line.longAt(0) ?: return@mapNotNull null
    val end = line.longAt(1) ?: start
    val wordsArray = line.arrayAt(2)
    val text = line.stringAt(2)
    val words = when {
        wordsArray != null -> wordsArray.mapNotNull { wordElement ->
            val word = wordElement as? JsonArray ?: return@mapNotNull null
            PluginLyricsWord(
                start = word.longAt(0) ?: start,
                end = word.longAt(1) ?: end,
                text = word.stringAt(2).orEmpty(),
            ).takeIf { it.text.isNotEmpty() }
        }
        !text.isNullOrEmpty() -> listOf(PluginLyricsWord(start, end, text))
        else -> emptyList()
    }
    PluginLyricsLine(start, end, words).takeIf { words.isNotEmpty() }
}.orEmpty()

private fun JsonArray?.parseTextLines(): List<PluginLyricsLine> = this?.mapNotNull { element ->
    val line = element as? JsonArray ?: return@mapNotNull null
    val start = line.longAt(0) ?: return@mapNotNull null
    val end = line.longAt(1) ?: start
    val text = line.stringAt(2).orEmpty()
    PluginLyricsLine(start, end, listOf(PluginLyricsWord(start, end, text))).takeIf { text.isNotBlank() }
}.orEmpty()

private fun JsonArray.longAt(index: Int): Long? =
    (getOrNull(index) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

private fun JsonArray.stringAt(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.contentOrNull
private fun JsonArray.arrayAt(index: Int): JsonArray? = getOrNull(index) as? JsonArray
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.array(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { this[it] as? JsonArray }
private fun JsonObject.primitiveString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.long(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } }

private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    when (val value = this[key]) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.joinToString("/") { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull.orEmpty()
                is JsonObject -> item.string("name", "title", "value").orEmpty()
                else -> ""
            }
        }.takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun JsonObject.stringMap(vararg keys: String): Map<String, String>? {
    val obj = keys.firstNotNullOfOrNull { this[it] as? JsonObject } ?: return null
    return obj.mapNotNull { (key, value) ->
        val text = when (value) {
            is JsonPrimitive -> value.contentOrNull
            else -> value.toString()
        }
        text?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
}

private fun String.toPayloadType(): PluginLyricsPayloadType? = when (trim()) {
    "structured", "STRUCTURED" -> PluginLyricsPayloadType.STRUCTURED
    "rawPlainLrc", "raw_plain_lrc", "RAW_PLAIN_LRC", "plainLrc", "plain_lrc", "lrc" -> PluginLyricsPayloadType.RAW_PLAIN_LRC
    "rawVerbatimLrc", "raw_verbatim_lrc", "RAW_VERBATIM_LRC" -> PluginLyricsPayloadType.RAW_VERBATIM_LRC
    "rawEnhancedLrc", "raw_enhanced_lrc", "RAW_ENHANCED_LRC" -> PluginLyricsPayloadType.RAW_ENHANCED_LRC
    "rawTtml", "raw_ttml", "RAW_TTML", "ttml" -> PluginLyricsPayloadType.RAW_TTML
    "rawMultiPersonEnhancedLrc", "raw_multi_person_enhanced_lrc", "RAW_MULTI_PERSON_ENHANCED_LRC" ->
        PluginLyricsPayloadType.RAW_MULTI_PERSON_ENHANCED_LRC
    else -> null
}
