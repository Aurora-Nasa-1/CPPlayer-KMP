package cp.player.core.lyrics

import cp.player.core.model.LyricsInfo

/**
 * 歌词引擎 —— 按**用户排序**的来源列表依次取词，首个命中即胜出。
 *
 * ### 它取代了什么
 * 改造前这段逻辑是 `PlaybackControllerImpl.fetchLyricsFor()` 里的一堆手写分支：
 * 先判 `LyricsSourceMode` 三档、再判是否本地曲、再穿插插件兜底。那些分支把「有哪些来源」
 * 与「按什么顺序找」两件事耦死在一个方法里，导致用户无法表达
 * 「AMLL 优先 → 插件 X → 音源」这类意图。
 *
 * 现在顺序完全由 [LyricsSourceRegistry.orderedSources] 决定，引擎只负责遍历与容错。
 *
 * ### 为什么不做并发竞速
 * - Rhino JS 运行时**非线程安全**（第三方插件都跑在同一个单线程 dispatcher 上），并发会引入竞态；
 * - 竞速的结果不确定（同一首歌两次可能来自不同来源），歌词显示会不稳定；
 * - 流量翻倍：本来第一条就命中的场景会同时打满所有来源。
 *
 * 顺序求值 + 首个命中即止，与改造前行为一致，也是唯一可解释的行为。
 */
class LyricsEngine(
    private val registry: LyricsSourceRegistry,
) {

    /**
     * 按顺序取词。
     *
     * @return 命中的结果（已填好 `sourceId` / `sourceName`）；全部落空返回 null
     */
    suspend fun resolve(request: LyricsRequest): LyricsSourceResult? {
        if (request.title.isBlank()) return null
        for (source in registry.orderedSources()) {
            if (LyricsCapability.GET_LYRICS !in source.capabilities) continue
            val result = runCatching { source.fetch(request) }.getOrNull() ?: continue
            if (result.lines.isEmpty()) continue
            // 引擎统一盖名字：来源自身不必关心自己被显示成什么（也避免它在 UI 文案上做手脚）。
            return result.copy(sourceId = source.id, sourceName = source.name)
        }
        return null
    }

    /**
     * 取词并转成 UI 用的 `LyricsState` + `LyricsInfo`。
     *
     * 兼容层：改造前 `fetchLyricsFor` 返回的就是这一对，`refreshLyrics()` 的调用点不用改。
     */
    suspend fun resolveToState(request: LyricsRequest): Pair<cp.player.core.playback.LyricsState, LyricsInfo?> {
        val hit = resolve(request) ?: return cp.player.core.playback.LyricsState.NoLyrics to null
        val source = registry.orderedSources().firstOrNull { it.id == hit.sourceId }
        val displayName = source?.let { registry.displayNameOf(it) } ?: hit.sourceName
        return cp.player.core.playback.LyricsState.Success(hit.lines) to LyricsInfo(
            source = displayName,
            format = formatLabelOf(hit),
            hasWordLevel = hit.hasWordLevel,
            hasTranslation = hit.hasTranslation,
            hasPhonetic = hit.hasPhonetic,
        )
    }

    /** 展示用格式标签；与改造前 `fetchFromAmll` / `fetchFromPlugins` 的措辞保持一致。 */
    private fun formatLabelOf(hit: LyricsSourceResult): String = when (hit.sourceId) {
        BuiltinLyricsSourceIds.AMLL -> if (hit.hasWordLevel) "TTML (Karaoke)" else "TTML"
        BuiltinLyricsSourceIds.SIDECAR -> if (hit.hasWordLevel) "Karaoke" else "LRC"
        BuiltinLyricsSourceIds.PROVIDER -> if (hit.hasWordLevel) "YRC (Karaoke)" else "LRC"
        else -> if (hit.hasWordLevel) "Plugin (Karaoke)" else "Plugin"
    }
}
