package cp.player.core.lyrics

import cp.player.core.lyricsplugin.LyricsPluginService
import cp.player.core.lyricsplugin.PluginCapability
import cp.player.core.lyricsplugin.PluginConfigField
import cp.player.core.lyricsplugin.PluginConfigFieldType
import cp.player.core.lyricsplugin.PluginLyricsOutcome
import cp.player.core.lyricsplugin.LyricsPluginSourceInfo
import cp.player.core.lyricsplugin.defaultValueString

/**
 * 把第三方 Lyrico 插件适配成 [LyricsSource]。
 *
 * ### 它取代了什么
 * 改造前插件是「一锅端」的：`LyricsPluginService.fetchLyrics(title, artist, album)` 内部
 * 遍历**全部**已启用插件、返回第一个命中 —— 顺序由目录名字典序决定，用户不可见不可控。
 * 现在每个插件是一个独立的 [LyricsSource]，插进同一个可排序列表。
 *
 * ### 新增：把 CPMediaId 透传给插件
 * 改造前只传 `title/artist/album`，插件只能模糊搜歌。现在 [LyricsRequest.mediaId] 一路透传，
 * 插件若能识别该音源的 ID 就能精确取词。⚠️ 宿主**不做猜测**：插件 manifest 需要显式声明
 * 自己认识的平台（`PluginManifest.platformIdField`），否则只走关键词 —— 猜错会取到别人的词。
 */
internal class PluginLyricsSource(
    private val info: LyricsPluginSourceInfo,
    private val service: LyricsPluginService,
) : LyricsSource {

    override val id: String get() = info.id
    override val name: String get() = info.name
    override val bundled: Boolean get() = info.bundled

    override val capabilities: Set<LyricsCapability> = buildSet {
        val declared = info.capabilities.ifEmpty { setOf(PluginCapability.SEARCH_SONGS) }
        if (PluginCapability.GET_LYRICS in declared) add(LyricsCapability.GET_LYRICS)
        if (PluginCapability.SEARCH_SONGS in declared) add(LyricsCapability.SEARCH_SONGS)
        if (PluginCapability.SEARCH_COVERS in declared) add(LyricsCapability.SEARCH_COVERS)
    }

    override suspend fun fetch(request: LyricsRequest): LyricsSourceResult? {
        val outcome: PluginLyricsOutcome = service.fetchLyricsFrom(
            id = id,
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
        ) ?: return null
        if (outcome.lines.isEmpty()) return null
        return LyricsSourceResult(lines = outcome.lines, hasWordLevel = outcome.hasWordLevel)
    }
}

internal fun PluginConfigFieldType.toSourceFieldType(): LyricsSourceConfigFieldType = when (this) {
    PluginConfigFieldType.TEXT -> LyricsSourceConfigFieldType.TEXT
    PluginConfigFieldType.PASSWORD -> LyricsSourceConfigFieldType.PASSWORD
    PluginConfigFieldType.NUMBER -> LyricsSourceConfigFieldType.NUMBER
    PluginConfigFieldType.SWITCH -> LyricsSourceConfigFieldType.SWITCH
    PluginConfigFieldType.DROPDOWN -> LyricsSourceConfigFieldType.DROPDOWN
    PluginConfigFieldType.TEXTAREA -> LyricsSourceConfigFieldType.TEXTAREA
    PluginConfigFieldType.MARKDOWN -> LyricsSourceConfigFieldType.MARKDOWN
}

internal fun PluginConfigField.toSourceField(): LyricsSourceConfigField = LyricsSourceConfigField(
    key = key,
    title = title,
    summary = summary,
    group = group,
    type = type.toSourceFieldType(),
    required = required,
    defaultValue = defaultValueString(),
    options = options.map { LyricsSourceConfigOption(it.value, it.label, it.summary) },
)
