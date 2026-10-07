package cp.player.app.i18n

/**
 * 歌词源插件（Lyrico Plugin API 兼容）的文案。
 *
 * 单独成组而不是并进 `SettingsStrings`：这组文案由**一个独立的管理页**消费，
 * 将来「播放页无歌词 → 一键换源」也会读同一组，挂在设置组下会让调用点看起来像跨页偷读。
 */
interface LyricsPluginStrings {
    // —— 设置入口 ——
    val entryTitle: String
    val entrySubtitle: String

    // —— 页面 ——
    val screenTitle: String
    val note: String

    // —— 排序与来源（统一来源体系） ——
    /** 排序区说明：讲清「顺序就是优先级」。 */
    val orderNote: String
    /** 上移 / 下移（Android 与桌面都可以用；桌面另有拖拽）。 */
    val moveUp: String
    val moveDown: String
    /** 已停用的来源行的说明后缀。 */
    val disabledHint: String
    /** 内置来源的展示名（`localizeName = true` 时按 id 查）。 */
    val builtinSidecar: String
    val builtinAmll: String
    val builtinProvider: String
    /** 内置来源的副标题。 */
    val builtinSidecarNote: String
    val builtinAmllNote: String
    val builtinProviderNote: String
    /** 播放页无歌词时的换源入口文案。 */
    val changeSourceAction: String

    // —— 导入 ——
    val importAction: String
    val importSuccess: String
    /** @param count 导入的插件数量 */
    fun importSuccessCount(count: Int): String
    val importFailed: String

    // —— 列表 ——
    val emptyTitle: String
    val emptyMessage: String
    val bundledBadge: String
    val enabled: String
    val disabled: String

    // —— 能力标签 ——
    val capabilitySearchSongs: String
    val capabilityGetLyrics: String
    val capabilitySearchCovers: String
    val capabilityLookupById: String

    // —— 删除 ——
    val deleteAction: String
    val deleteConfirmTitle: String
    /** @param name 插件名称 */
    fun deleteConfirmMessage(name: String): String
}

object LyricsPluginStringsZh : LyricsPluginStrings {
    override val entryTitle = "歌词来源"
    override val entrySubtitle = "排序与启用歌词来源（内置 + 插件）"

    override val screenTitle = "歌词来源"
    override val note =
        "歌词按下面的顺序查找，第一个命中的来源生效。" +
            "内置来源随应用提供；插件遵循 Lyrico Plugin API，按该规范编写的插件可直接导入。" +
            "插件只在你启用后才会联网。"

    override val orderNote = "拖动或使用箭头调整顺序；关闭的来源不参与查找。"
    override val moveUp = "上移"
    override val moveDown = "下移"
    override val disabledHint = "已停用"
    override val builtinSidecar = "本地边车歌词"
    override val builtinAmll = "AMLL 官方词库"
    override val builtinProvider = "音源自带歌词"
    override val builtinSidecarNote = "音频同目录的 .lrc / .ttml / .elrc"
    override val builtinAmllNote = "api.amll.dev · 逐字 TTML"
    override val builtinProviderNote = "当前音源的 lyric/new · 逐字 YRC"
    override val changeSourceAction = "换个歌词来源"

    override val importAction = "导入歌词源插件（zip）"
    override val importSuccess = "导入成功"
    override fun importSuccessCount(count: Int) = "已导入 $count 个插件"
    override val importFailed = "导入失败：文件不是有效的插件包"

    override val emptyTitle = "还没有歌词来源"
    override val emptyMessage = "导入一个 zip 插件包即可扩展歌词来源"
    override val bundledBadge = "内置"
    override val enabled = "已启用"
    override val disabled = "已停用"

    override val capabilitySearchSongs = "搜索歌曲"
    override val capabilityGetLyrics = "获取歌词"
    override val capabilitySearchCovers = "搜索封面"
    override val capabilityLookupById = "按 ID 精确定位"

    override val deleteAction = "删除插件"
    override val deleteConfirmTitle = "删除这个插件？"
    override fun deleteConfirmMessage(name: String) = "将删除「$name」及其配置，删除后需要重新导入。"
}

object LyricsPluginStringsEn : LyricsPluginStrings {
    override val entryTitle = "Lyric sources"
    override val entrySubtitle = "Order and toggle lyric sources (built-in + plugins)"

    override val screenTitle = "Lyric sources"
    override val note =
        "Lyrics are looked up in the order below; the first source that matches wins. " +
            "Built-in sources ship with the app; plugins follow the Lyrico Plugin API and any plugin " +
            "written to that spec can be imported. A plugin only reaches the network after you enable it."

    override val orderNote =
        "Drag or use the arrows to reorder. Disabled sources are skipped during lookup."
    override val moveUp = "Move up"
    override val moveDown = "Move down"
    override val disabledHint = "Disabled"
    override val builtinSidecar = "Local sidecar lyrics"
    override val builtinAmll = "AMLL official database"
    override val builtinProvider = "Provider lyrics"
    override val builtinSidecarNote = "Same-folder .lrc / .ttml / .elrc next to the audio"
    override val builtinAmllNote = "api.amll.dev · word-level TTML"
    override val builtinProviderNote = "Current provider's lyric/new · word-level YRC"
    override val changeSourceAction = "Change lyric source"

    override val importAction = "Import lyric plugin (zip)"
    override val importSuccess = "Imported"
    override fun importSuccessCount(count: Int) = "Imported $count plugin(s)"
    override val importFailed = "Import failed: not a valid plugin package"

    override val emptyTitle = "No lyric sources yet"
    override val emptyMessage = "Import a plugin zip to add more lyric sources"
    override val bundledBadge = "Built-in"
    override val enabled = "Enabled"
    override val disabled = "Disabled"

    override val capabilitySearchSongs = "Search songs"
    override val capabilityGetLyrics = "Fetch lyrics"
    override val capabilitySearchCovers = "Search covers"
    override val capabilityLookupById = "Exact lookup by ID"

    override val deleteAction = "Delete plugin"
    override val deleteConfirmTitle = "Delete this plugin?"
    override fun deleteConfirmMessage(name: String) =
        "\"$name\" and its configuration will be removed. You will need to import it again."
}
