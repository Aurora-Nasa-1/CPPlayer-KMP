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

    // —— 「下载中」页签顶部的批量操作条 ——

    /**
     * 批量操作条的标题。
     *
     * 写成函数而不是「队列任务 (N)」拼串：中英语序/量词不同（「队列任务（3）」vs
     * 「3 queued tasks」），在调用方拼就译不了（同本文件头部的说明）。
     */
    fun queueTasks(count: Int): String

    val pauseAll: String
    val resumeAll: String
    val cancelAll: String

    /** 单条取消（取消任务按钮 / 语义描述）。 */
    val cancel: String

    // —— 取消下载的二次确认（破坏性：会丢掉未完成的临时文件）——
    val cancelAllConfirmTitle: String
    val cancelAllConfirmMessage: String
    val cancelConfirmTitle: String

    /** @param title 任务（曲目）名。 */
    fun cancelConfirmMessage(title: String): String

    /** 已完成任务卡片的操作提示（列表行尾 / 右键菜单）。 */
    val playVideo: String
    val addToLocalPlaylist: String
    val openInFileManager: String
    val favoriteCloud: String
    val unfavoriteCloud: String

    /** @param target 当前要执行的动作；true = 收藏。 */
    fun favoriteCloudDone(favorited: Boolean): String

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

    /**
     * 下载中的状态行：**只带体积，不带百分比**。
     *
     * 百分比由卡片右端单独显示（`labelMedium` + 主题色），状态行再带一遍就是
     * 同一屏里同一个数字读两遍。这里保留 [statusDownloading] 是给**其它布局**
     * （没有独立百分比位置的地方）用的，两者不要在同一条状态行里同时出现。
     *
     * @param size 已 / 总字节的可读串。
     */
    fun statusDownloadingSize(size: String): String

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

    // —— 本地曲库管理 ——
    val tabLocalFavorites: String
    val tabLocalPlaylists: String
    val emptyLocalFavoritesTitle: String
    val emptyLocalFavoritesNote: String
    val emptyLocalPlaylistsTitle: String
    val emptyLocalPlaylistsNote: String
    val newLocalPlaylist: String
    val playlistName: String
    val rename: String
    val playPlaylist: String
    val playlistOptions: String
    val emptyPlaylistTitle: String
    val emptyPlaylistNote: String
    val filterAndScanSettings: String
    val losslessOnly: String
    val duplicatesOnly: String
    val missingCoverOnly: String
    val missingTagsOnly: String
    val clearFilters: String
    val scanSettings: String
    fun scanRuleCount(count: Int): String
    val scanSettingsNote: String
    val minimumDuration: String
    val skipVideoFiles: String
    val excludedFolders: String
    val save: String
    val sortName: String
    val sortCount: String
    val sortRecent: String
    val removeConfirmTitle: String
    fun removeConfirmMessage(title: String): String
    val deleteLocalFile: String
    fun deleteLocalFileMessage(title: String): String
    fun playlistTrackCount(count: Int): String
    fun deleteLocalPlaylistMessage(name: String): String
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

    override fun queueTasks(count: Int) = "队列任务（$count）"
    override val pauseAll = "全部暂停"
    override val resumeAll = "全部继续"
    override val cancelAll = "全部取消"
    override val cancel = "取消任务"
    override val cancelAllConfirmTitle = "取消全部下载"
    override val cancelAllConfirmMessage =
        "确定取消所有进行中的下载任务吗？未完成的临时文件将被清除。"
    override val cancelConfirmTitle = "取消下载"
    override fun cancelConfirmMessage(title: String) =
        "确定取消「$title」的下载吗？已下载的临时部分会被清除，之后需要重新下载。"

    override val playVideo = "播放视频"
    override val addToLocalPlaylist = "添加到本地歌单"
    override val openInFileManager = "在文件管理器中打开"
    override val favoriteCloud = "云端收藏"
    override val unfavoriteCloud = "取消云端收藏"
    override fun favoriteCloudDone(favorited: Boolean) =
        if (favorited) "已收藏到云端" else "已取消云端收藏"

    override val localImported = "本地导入"
    override fun itemCount(count: Int) = "$count 项"
    override val removeFromLibrary = "从库中移除"
    override val scanDevice = "扫描设备"
    override val scanning = "扫描中…"
    override fun scanningFound(count: Int) = "扫描中 · 已发现 $count 项"
    override val importFolder = "导入文件夹"
    override val importing = "导入中…"

    override val statusPending = "等待下载"
    override fun statusDownloadingSize(size: String) = "下载中 · $size"
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
    override val tabLocalFavorites = "本地喜欢"
    override val tabLocalPlaylists = "本地歌单"
    override val emptyLocalFavoritesTitle = "暂无本地喜欢歌曲"
    override val emptyLocalFavoritesNote = "在歌曲菜单中选择「喜欢」，即可集中查看纯本地歌曲"
    override val emptyLocalPlaylistsTitle = "暂无本地歌单"
    override val emptyLocalPlaylistsNote = "创建歌单，将不同来源的本地歌曲整理到一起"
    override val newLocalPlaylist = "新建本地歌单"
    override val playlistName = "歌单名称"
    override val rename = "重命名"
    override val playPlaylist = "播放歌单"
    override val playlistOptions = "歌单选项"
    override val emptyPlaylistTitle = "此歌单暂无歌曲"
    override val emptyPlaylistNote = "可从歌曲菜单中选择「添加到本地歌单」"
    override val filterAndScanSettings = "筛选与扫描设置"
    override val losslessOnly = "只看无损音频"
    override val duplicatesOnly = "只看重复曲目"
    override val missingCoverOnly = "只看缺少封面"
    override val missingTagsOnly = "只看标签残缺"
    override val clearFilters = "清除筛选"
    override val scanSettings = "扫描设置"
    override fun scanRuleCount(count: Int) = "$count 条规则"
    override val scanSettingsNote = "改动会在下一次扫描时生效，并同步清理已被规则排除的条目。"
    override val minimumDuration = "最短时长（秒），0 表示不过滤"
    override val skipVideoFiles = "扫描时跳过视频文件"
    override val excludedFolders = "排除目录（每行一个）"
    override val save = "保存"
    override val sortName = "名称"
    override val sortCount = "数量"
    override val sortRecent = "最近修改"
    override val removeConfirmTitle = "从媒体库移除"
    override fun removeConfirmMessage(title: String) = "从媒体库移除《$title》？磁盘中的文件会保留。"
    override val deleteLocalFile = "彻底删除本地文件"
    override fun deleteLocalFileMessage(title: String) = "确定要将《$title》从磁盘彻底删除吗？此操作无法撤销。"
    override fun playlistTrackCount(count: Int) = "$count 首歌曲"
    override fun deleteLocalPlaylistMessage(name: String) = "确定删除本地歌单《$name》吗？歌单内的本地音频文件不会被删除。"
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

    override fun queueTasks(count: Int) = if (count == 1) "1 queued task" else "$count queued tasks"
    override val pauseAll = "Pause all"
    override val resumeAll = "Resume all"
    override val cancelAll = "Cancel all"
    override val cancel = "Cancel download"
    override val cancelAllConfirmTitle = "Cancel all downloads"
    override val cancelAllConfirmMessage =
        "Cancel every download in progress? Partial temporary files will be removed."
    override val cancelConfirmTitle = "Cancel download"
    override fun cancelConfirmMessage(title: String) =
        "Cancel downloading \"$title\"? The partial file will be removed and you'll need to start over."

    override val playVideo = "Play video"
    override val addToLocalPlaylist = "Add to local playlist"
    override val openInFileManager = "Show in file manager"
    override val favoriteCloud = "Favorite in cloud"
    override val unfavoriteCloud = "Remove cloud favorite"
    override fun favoriteCloudDone(favorited: Boolean) =
        if (favorited) "Added to cloud favorites" else "Removed from cloud favorites"

    override val localImported = "Imported"
    override fun itemCount(count: Int) = if (count == 1) "1 item" else "$count items"
    override val removeFromLibrary = "Remove from library"
    override val scanDevice = "Scan device"
    override val scanning = "Scanning…"
    override fun scanningFound(count: Int) = "Scanning · $count found"
    override val importFolder = "Import folder"
    override val importing = "Importing…"

    override val statusPending = "Waiting to download"
    override fun statusDownloadingSize(size: String) = "Downloading · $size"
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
    override val tabLocalFavorites = "Local favorites"
    override val tabLocalPlaylists = "Local playlists"
    override val emptyLocalFavoritesTitle = "No local favorites yet"
    override val emptyLocalFavoritesNote = "Choose \"Favorite\" from a song menu to collect local tracks here"
    override val emptyLocalPlaylistsTitle = "No local playlists yet"
    override val emptyLocalPlaylistsNote = "Create a playlist to organize local songs from different folders"
    override val newLocalPlaylist = "New local playlist"
    override val playlistName = "Playlist name"
    override val rename = "Rename"
    override val playPlaylist = "Play playlist"
    override val playlistOptions = "Playlist options"
    override val emptyPlaylistTitle = "This playlist is empty"
    override val emptyPlaylistNote = "Choose \"Add to local playlist\" from a song menu"
    override val filterAndScanSettings = "Filters and scan settings"
    override val losslessOnly = "Lossless audio only"
    override val duplicatesOnly = "Duplicate tracks only"
    override val missingCoverOnly = "Missing artwork only"
    override val missingTagsOnly = "Incomplete tags only"
    override val clearFilters = "Clear filters"
    override val scanSettings = "Scan settings"
    override fun scanRuleCount(count: Int) = if (count == 1) "1 rule" else "$count rules"
    override val scanSettingsNote = "Changes apply on the next scan and remove indexed items excluded by these rules."
    override val minimumDuration = "Minimum duration in seconds (0 disables filtering)"
    override val skipVideoFiles = "Skip video files while scanning"
    override val excludedFolders = "Excluded folders (one per line)"
    override val save = "Save"
    override val sortName = "Name"
    override val sortCount = "Track count"
    override val sortRecent = "Recently modified"
    override val removeConfirmTitle = "Remove from library"
    override fun removeConfirmMessage(title: String) = "Remove \"$title\" from the library? The file will remain on disk."
    override val deleteLocalFile = "Delete local file permanently"
    override fun deleteLocalFileMessage(title: String) = "Permanently delete \"$title\" from disk? This can't be undone."
    override fun playlistTrackCount(count: Int) = if (count == 1) "1 song" else "$count songs"
    override fun deleteLocalPlaylistMessage(name: String) = "Delete the local playlist \"$name\"? Its audio files will remain on disk."

    override val collapsePlayer = "Collapse the player"
    override val lyrics = "Lyrics"
    override val comments = "Comments"
    override val similar = "Similar"
}
