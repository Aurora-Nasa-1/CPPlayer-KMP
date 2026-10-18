package cp.player.app.i18n

/**
 * 外部歌单导入页的文案。
 *
 * 单独成组（与 `library` / `playlistSheet` 并列）而不是塞进那两组：这一页同时消费
 * 「粘贴文本」「链接解析」「新建 / 追加歌单」三件事，混进媒体库分组会让那边继续膨胀，
 * 而这里将来还要接「从文件导入」（`.txt` / `.m3u`）—— 入口是同一页。
 */
interface PlaylistImportStrings {
    val screenTitle: String

    // —— 输入 ——
    val inputTitle: String
    val inputNote: String
    val inputPlaceholder: String
    val pasteFromClipboard: String
    val clear: String
    val analyze: String
    val analyzing: String
    fun progress(done: Int, total: Int): String
    val emptyInput: String
    val noEntries: String
    fun unsupportedLinks(count: Int): String
    val analyzeFailed: String

    // —— 识别结果 ——
    val resultTitle: String
    fun recognized(count: Int): String
    fun unmatchedCount(count: Int): String
    fun fromPlaylist(name: String): String
    val unmatchedTitle: String
    val previewTitle: String
    val previewHint: String
    val selectAll: String
    val deselectAll: String
    val emptySelection: String

    // —— 导入 ——
    val nameLabel: String
    val namePlaceholder: String
    val defaultName: String
    val importToNew: String
    val importToExisting: String
    val appendTitle: String
    val importing: String
    fun importProgress(done: Int, total: Int): String
    fun importSuccess(count: Int, name: String): String
    val importFailed: String
    val partialFailure: String
    val openPlaylist: String
    val done: String

    // —— 源歌单 ——
    val subscribeSource: String
    val subscribedSource: String
    val subscribeFailed: String
}

object PlaylistImportStringsZh : PlaylistImportStrings {
    override val screenTitle = "导入外部歌单"

    override val inputTitle = "粘贴歌单内容"
    override val inputNote = "支持网易云歌单链接、单曲链接，或每行一首「歌名 - 歌手」。"
    override val inputPlaceholder = "https://music.163.com/playlist?id=…\n或\n晴天 - 周杰伦\n七里香 - 周杰伦"
    override val pasteFromClipboard = "从剪贴板粘贴"
    override val clear = "清空"
    override val analyze = "识别"
    override val analyzing = "正在识别…"
    override fun progress(done: Int, total: Int) = "已识别 $done / $total"
    override val emptyInput = "请先粘贴歌单内容"
    override val noEntries = "没有识别到可导入的歌曲"
    override fun unsupportedLinks(count: Int) = "有 $count 条链接暂不支持（请使用网易云音乐完整链接）"
    override val analyzeFailed = "识别失败，请检查网络后重试"

    override val resultTitle = "识别结果"
    override fun recognized(count: Int) = "已识别 $count 首歌曲"
    override fun unmatchedCount(count: Int) = "未匹配 $count 条"
    override fun fromPlaylist(name: String) = "来源歌单：$name"
    override val unmatchedTitle = "未匹配的条目"
    override val previewTitle = "曲目预览"
    override val previewHint = "点击可取消勾选，未勾选的歌曲不会导入"
    override val selectAll = "全选"
    override val deselectAll = "全不选"
    override val emptySelection = "请至少选择一首歌曲"

    override val nameLabel = "新歌单名称"
    override val namePlaceholder = "输入歌单名称"
    override val defaultName = "导入歌单"
    override val importToNew = "新建歌单并导入"
    override val importToExisting = "追加到已有歌单"
    override val appendTitle = "选择目标歌单"
    override val importing = "正在导入…"
    override fun importProgress(done: Int, total: Int) = "已导入 $done / $total"
    override fun importSuccess(count: Int, name: String) = "已导入 $count 首到「$name」"
    override val importFailed = "导入失败"
    override val partialFailure = "部分歌曲导入失败，请稍后在歌单里核对"
    override val openPlaylist = "打开歌单"
    override val done = "完成"

    override val subscribeSource = "收藏源歌单"
    override val subscribedSource = "已收藏源歌单"
    override val subscribeFailed = "收藏失败"
}

object PlaylistImportStringsEn : PlaylistImportStrings {
    override val screenTitle = "Import playlist"

    override val inputTitle = "Paste playlist content"
    override val inputNote = "Paste a NetEase playlist link, song links, or one \"title - artist\" per line."
    override val inputPlaceholder = "https://music.163.com/playlist?id=…\nor\nSunny Day - Jay Chou\nCommon Jasmine Orange - Jay Chou"
    override val pasteFromClipboard = "Paste from clipboard"
    override val clear = "Clear"
    override val analyze = "Analyze"
    override val analyzing = "Analyzing…"
    override fun progress(done: Int, total: Int) = "Matched $done / $total"
    override val emptyInput = "Paste something first"
    override val noEntries = "No importable songs were recognized"
    override fun unsupportedLinks(count: Int) = "$count link(s) are not supported yet (use a full NetEase link)"
    override val analyzeFailed = "Couldn't analyze. Check your connection and try again."

    override val resultTitle = "Results"
    override fun recognized(count: Int) = "$count song(s) recognized"
    override fun unmatchedCount(count: Int) = "$count unmatched"
    override fun fromPlaylist(name: String) = "Source playlist: $name"
    override val unmatchedTitle = "Unmatched entries"
    override val previewTitle = "Track preview"
    override val previewHint = "Tap to unselect; unchecked songs won't be imported"
    override val selectAll = "Select all"
    override val deselectAll = "Select none"
    override val emptySelection = "Select at least one song"

    override val nameLabel = "New playlist name"
    override val namePlaceholder = "Enter a playlist name"
    override val defaultName = "Imported playlist"
    override val importToNew = "Create playlist and import"
    override val importToExisting = "Add to an existing playlist"
    override val appendTitle = "Choose a playlist"
    override val importing = "Importing…"
    override fun importProgress(done: Int, total: Int) = "Imported $done / $total"
    override fun importSuccess(count: Int, name: String) = "Imported $count song(s) into \"$name\""
    override val importFailed = "Import failed"
    override val partialFailure = "Some songs failed to import. Check the playlist later."
    override val openPlaylist = "Open playlist"
    override val done = "Done"

    override val subscribeSource = "Save source playlist"
    override val subscribedSource = "Source playlist saved"
    override val subscribeFailed = "Couldn't save the playlist"
}
