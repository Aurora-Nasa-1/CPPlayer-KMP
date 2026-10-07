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

    // —— 删除 ——
    val deleteAction: String
    val deleteConfirmTitle: String
    /** @param name 插件名称 */
    fun deleteConfirmMessage(name: String): String
}

object LyricsPluginStringsZh : LyricsPluginStrings {
    override val entryTitle = "歌词源插件"
    override val entrySubtitle = "导入第三方歌词源（兼容 Lyrico 插件）"

    override val screenTitle = "歌词源插件"
    override val note =
        "插件遵循 Lyrico Plugin API，按该规范编写的插件可直接导入。" +
            "启用后会在官方词库与音源歌词都拿不到时参与取词。插件只在你启用后才会联网。"

    override val importAction = "导入插件（zip）"
    override val importSuccess = "导入成功"
    override fun importSuccessCount(count: Int) = "已导入 $count 个插件"
    override val importFailed = "导入失败：文件不是有效的插件包"

    override val emptyTitle = "还没有歌词源插件"
    override val emptyMessage = "导入一个 zip 插件包即可扩展歌词来源"
    override val bundledBadge = "内置"
    override val enabled = "已启用"
    override val disabled = "已停用"

    override val capabilitySearchSongs = "搜索歌曲"
    override val capabilityGetLyrics = "获取歌词"
    override val capabilitySearchCovers = "搜索封面"

    override val deleteAction = "删除插件"
    override val deleteConfirmTitle = "删除这个插件？"
    override fun deleteConfirmMessage(name: String) = "将删除「$name」及其配置，删除后需要重新导入。"
}

object LyricsPluginStringsEn : LyricsPluginStrings {
    override val entryTitle = "Lyric source plugins"
    override val entrySubtitle = "Import third-party lyric sources (Lyrico-compatible)"

    override val screenTitle = "Lyric source plugins"
    override val note =
        "Plugins follow the Lyrico Plugin API, so any plugin written to that spec can be imported. " +
            "Once enabled they are consulted only when neither the official TTML database nor the " +
            "provider returns lyrics. A plugin only reaches the network after you enable it."

    override val importAction = "Import plugin (zip)"
    override val importSuccess = "Imported"
    override fun importSuccessCount(count: Int) = "Imported $count plugin(s)"
    override val importFailed = "Import failed: not a valid plugin package"

    override val emptyTitle = "No lyric source plugins yet"
    override val emptyMessage = "Import a plugin zip to add more lyric sources"
    override val bundledBadge = "Built-in"
    override val enabled = "Enabled"
    override val disabled = "Disabled"

    override val capabilitySearchSongs = "Search songs"
    override val capabilityGetLyrics = "Fetch lyrics"
    override val capabilitySearchCovers = "Search covers"

    override val deleteAction = "Delete plugin"
    override val deleteConfirmTitle = "Delete this plugin?"
    override fun deleteConfirmMessage(name: String) =
        "\"$name\" and its configuration will be removed. You will need to import it again."
}
