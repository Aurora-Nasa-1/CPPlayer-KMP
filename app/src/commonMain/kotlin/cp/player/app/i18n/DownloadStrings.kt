package cp.player.app.i18n

/**
 * 下载管理页、歌曲 / 歌单操作弹层、桌面播放页的文案。
 *
 * ### 复用优先
 *
 * 下面**故意不重复**已经在别的分组里的词，调用点直接读那一处，避免同一条文案两种译法：
 *
 * | 调用点 | 复用 |
 * | --- | --- |
 * | 「下载中 / 已完成」页签、任务状态「已完成」 | `library.downloading` / `library.completed` |
 * | 播放 / 分享 / 下载 / 已下载 / 歌曲信息 / 更多 | `player.*` |
 * | 取消（下载）、重试 | `common.dismiss` / `player.retry` |
 * | 按名称 / 按歌手 / 删除歌单 / 取消收藏 / 歌单 | `library.*` |
 * | 正在播放 / 队列 / 随机播放 / 上一首 / 下一首 / 循环 | `player.*` / `library.sourceNowPlaying` |
 *
 * 只有**字面量与现成成员不一致**的（如「添加到队列」≠「加入队列」、「排序方式」≠「排序」、
 * 「默认」≠「默认顺序」）才在这里新开成员 —— 中文一律照搬界面上现有的字面量。
 *
 * ### 带数字的句子
 *
 * `itemCount` / `scanningFound` / `scanCompleted` / `imported` 都是函数：
 * 中英语序不同（「共 128 个媒体文件」vs「128 media files found」），在调用方拼就译不了。
 */
interface DownloadStrings {
    // —— 页面与页签 ——
    val screenTitle: String
    val tabLocalLibrary: String

    // —— 三个页签的空态（ContentState 的 title + message）——
    val emptyActiveTitle: String
    val emptyActiveNote: String
    val emptyCompletedTitle: String
    val emptyCompletedNote: String
    val emptyLocalTitle: String
    val emptyLocalNote: String

    // —— 任务卡片 ——
    val unknownArtist: String
    val pause: String
    val resume: String
    val deleteRecord: String
    val deleteFileAndRecord: String
    val delete: String
    val deleteConfirmTitle: String

    /** @param title 任务（曲目）名。 */
    fun deleteConfirmMessage(title: String): String

    // —— 本地媒体库 ——
    val localImported: String

    /** 分组头右侧的计数。 */
    fun itemCount(count: Int): String

    val removeFromLibrary: String
    val scanDevice: String
    val scanning: String

    /** @param count 已扫描到的条目数。 */
    fun scanningFound(count: Int): String

    val importFolder: String
    val importing: String

    // —— 任务状态（见 I18N.md §5.3：状态文案不能在类里固化）——
    val statusPending: String

    /** @param percent 0..100；@param size 已 / 总字节的可读串。 */
    fun statusDownloading(percent: Int, size: String): String

    fun statusPaused(size: String): String

    /** @param error 后端给的失败原因；为 null 时界面上会显示 `null`，与迁移前一致。 */
    fun statusFailed(error: String?): String

    val unknownError: String
    val statusCancelled: String

    // —— ScreenModel 提示语（协程里读不到 CompositionLocal，由调用方传 CpStrings）——
    val downloadCancelled: String
    val fileAndRecordDeleted: String
    val recordRemoved: String
    val permissionRequested: String

    /** @param reason 异常 message，可空。 */
    fun scanFailed(reason: String?): String

    /** @param total 扫到的媒体文件数（>0 时才走这条）。 */
    fun scanCompleted(total: Int): String

    val scanCompletedEmpty: String
    val importFailed: String
    val importNoNewFiles: String

    /** @param count 新增条数。 */
    fun imported(count: Int): String

    val removedFromLibrary: String
    val unsupportedMedia: String

    // —— 歌曲 / 歌单操作弹层（MoreOptionsSheet）——
    val favorite: String
    val playNext: String
    val addToQueue: String
    val addAllToQueue: String
    val sortOrder: String
    val sortDefault: String

    // —— 桌面播放页 ——
    val collapsePlayer: String
    val lyrics: String
    val comments: String
    val similar: String

    // —— 本地曲库浏览 ——
    val tabSongs: String
    val tabAlbums: String
    val tabArtists: String
    val tabFavorites: String
    val tabFolders: String
    val librarySearchHint: String
    val playAll: String
    val shuffleAll: String
    val addedToFavorites: String
    val removedFromFavorites: String

    /** @param count 专辑数。 */
    fun albumCount(count: Int): String
    val emptyFavoritesTitle: String
    val emptyFavoritesNote: String
    val emptySearchTitle: String
    val unknownAlbum: String
    val favoriteSongs: String
}

/** 简体中文实现（界面现有字面量照搬）。 */
object DownloadStringsZh : DownloadStrings {
    override val screenTitle = "下载管理"
    override val tabLocalLibrary = "本地媒体库"

    override val emptyActiveTitle = "没有进行中的下载"
    override val emptyActiveNote = "在歌曲更多菜单或歌单页点击「下载」，任务会显示在这里"
    override val emptyCompletedTitle = "还没有下载完成的内容"
    override val emptyCompletedNote = "下载完成的音频与视频会保存在这里"
    override val emptyLocalTitle = "本地媒体库还是空的"
    override val emptyLocalNote = "下载歌曲，或扫描设备、导入本地文件夹后会显示在这里"

    override val unknownArtist = "未知艺术家"
    override val pause = "暂停"
    override val resume = "继续"
    override val deleteRecord = "删除记录"
    override val deleteFileAndRecord = "删除文件与记录"
    override val delete = "删除"
    override val deleteConfirmTitle = "删除下载"
    override fun deleteConfirmMessage(title: String) =
        "确定删除「$title」吗？已下载的文件与记录会被一并移除。"

    override val localImported = "本地导入"
    override fun itemCount(count: Int) = "$count 项"
    override val removeFromLibrary = "从库中移除"
    override val scanDevice = "扫描设备"
    override val scanning = "扫描中…"
    override fun scanningFound(count: Int) = "扫描中 · 已发现 $count 项"
    override val importFolder = "导入文件夹"
    override val importing = "导入中…"

    override val statusPending = "等待下载"
    override fun statusDownloading(percent: Int, size: String) = "下载中 $percent% · $size"
    override fun statusPaused(size: String) = "已暂停 · $size"
    override fun statusFailed(error: String?) = "下载失败：$error"
    override val unknownError = "未知错误"
    override val statusCancelled = "已取消"

    override val downloadCancelled = "已取消下载"
    override val fileAndRecordDeleted = "已删除文件与记录"
    override val recordRemoved = "已移除记录"
    override val permissionRequested = "已请求媒体读取权限，授权后请重新扫描"
    override fun scanFailed(reason: String?) = "扫描失败：$reason"
    override fun scanCompleted(total: Int) = "扫描完成，共 $total 个媒体文件"
    override val scanCompletedEmpty = "扫描完成"
    override val importFailed = "导入失败"
    override val importNoNewFiles = "该文件夹没有新的媒体文件"
    override fun imported(count: Int) = "已导入 $count 个媒体文件"
    override val removedFromLibrary = "已从媒体库移除"
    override val unsupportedMedia = "暂不支持播放该媒体"

    override val favorite = "收藏"
    override val playNext = "下一首播放"
    override val addToQueue = "添加到队列"
    override val addAllToQueue = "全部加入队列"
    override val sortOrder = "排序方式"
    override val sortDefault = "默认"

    override val collapsePlayer = "收起播放页"
    override val lyrics = "歌词"
    override val comments = "评论"
    override val similar = "相似"

    override val tabSongs = "歌曲"
    override val tabAlbums = "专辑"
    override val tabArtists = "艺术家"
    override val tabFavorites = "收藏"
    override val tabFolders = "文件夹"
    override val librarySearchHint = "搜索本地歌曲、专辑、艺术家"
    override val playAll = "播放全部"
    override val shuffleAll = "随机播放"
    override val addedToFavorites = "已加入收藏"
    override val removedFromFavorites = "已取消收藏"
    override fun albumCount(count: Int) = "$count 张专辑"
    override val emptyFavoritesTitle = "还没有收藏的本地歌曲"
    override val emptyFavoritesNote = "点击歌曲右侧的心形图标即可收藏"
    override val emptySearchTitle = "没有匹配的结果"
    override val unknownAlbum = "未知专辑"
    override val favoriteSongs = "我喜欢的本地音乐"
}

/** 英文实现。 */
object DownloadStringsEn : DownloadStrings {
    override val screenTitle = "Downloads"
    override val tabLocalLibrary = "Local library"

    override val emptyActiveTitle = "Nothing downloading right now"
    override val emptyActiveNote =
        "Tap \"Download\" in a song's menu or on a playlist, and the task will show up here"
    override val emptyCompletedTitle = "Nothing downloaded yet"
    override val emptyCompletedNote = "Audio and video you finish downloading will be saved here"
    override val emptyLocalTitle = "Your local library is empty"
    override val emptyLocalNote =
        "Download songs, scan this device, or import a local folder — they'll show up here"

    override val unknownArtist = "Unknown artist"
    override val pause = "Pause"
    override val resume = "Resume"
    override val deleteRecord = "Delete record"
    override val deleteFileAndRecord = "Delete file and record"
    override val delete = "Delete"
    override val deleteConfirmTitle = "Delete download"
    override fun deleteConfirmMessage(title: String) =
        "Delete \"$title\"? The downloaded file and its record will both be removed."

    override val localImported = "Imported"
    override fun itemCount(count: Int) = if (count == 1) "1 item" else "$count items"
    override val removeFromLibrary = "Remove from library"
    override val scanDevice = "Scan device"
    override val scanning = "Scanning…"
    override fun scanningFound(count: Int) = "Scanning · $count found"
    override val importFolder = "Import folder"
    override val importing = "Importing…"

    override val statusPending = "Waiting to download"
    override fun statusDownloading(percent: Int, size: String) = "Downloading $percent% · $size"
    override fun statusPaused(size: String) = "Paused · $size"
    override fun statusFailed(error: String?) = "Download failed: $error"
    override val unknownError = "Unknown error"
    override val statusCancelled = "Cancelled"

    override val downloadCancelled = "Download cancelled"
    override val fileAndRecordDeleted = "File and record deleted"
    override val recordRemoved = "Record removed"
    override val permissionRequested = "Media access requested — grant it, then scan again"
    override fun scanFailed(reason: String?) = "Scan failed: $reason"
    override fun scanCompleted(total: Int) =
        if (total == 1) "Scan complete — 1 media file found"
        else "Scan complete — $total media files found"
    override val scanCompletedEmpty = "Scan complete"
    override val importFailed = "Import failed"
    override val importNoNewFiles = "That folder has no new media files"
    override fun imported(count: Int) =
        if (count == 1) "Imported 1 media file" else "Imported $count media files"
    override val removedFromLibrary = "Removed from your library"
    override val unsupportedMedia = "This media type can't be played yet"

    override val favorite = "Favorite"
    override val playNext = "Play next"
    override val addToQueue = "Add to queue"
    override val addAllToQueue = "Add all to queue"
    override val sortOrder = "Sort by"
    override val sortDefault = "Default"

    override val tabSongs = "Songs"
    override val tabAlbums = "Albums"
    override val tabArtists = "Artists"
    override val tabFavorites = "Favorites"
    override val tabFolders = "Folders"
    override val librarySearchHint = "Search local songs, albums, artists"
    override val playAll = "Play all"
    override val shuffleAll = "Shuffle"
    override val addedToFavorites = "Added to favorites"
    override val removedFromFavorites = "Removed from favorites"
    override fun albumCount(count: Int) = if (count == 1) "1 album" else "$count albums"
    override val emptyFavoritesTitle = "No favorite local songs yet"
    override val emptyFavoritesNote = "Tap the heart icon next to a song to add it"
    override val emptySearchTitle = "No matches"
    override val unknownAlbum = "Unknown album"
    override val favoriteSongs = "Liked local music"

    override val collapsePlayer = "Collapse the player"
    override val lyrics = "Lyrics"
    override val comments = "Comments"
    override val similar = "Similar"
}
