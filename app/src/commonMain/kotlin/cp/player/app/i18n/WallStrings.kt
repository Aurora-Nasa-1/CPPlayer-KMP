package cp.player.app.i18n

/**
 * 专辑墙模式（`ui/wall`）的文案。
 *
 * 单独成组而不是并进 `LibraryStrings`：墙是一个**跨数据源的模式**（专辑 / 歌曲 / 本地文件
 * 都能上墙），它的文案不属于"媒体库"这一页；将来「歌手页 → 用墙看」「搜索结果 → 用墙看」
 * 也会读同一组，挂在某个页面的组下会让调用点看起来像跨页偷读。
 */
interface WallStrings {
    // —— 入口 ——
    val entryLabel: String
    val entryNote: String

    val screenTitle: String

    // —— 五个命名层 ——
    val levelDust: String
    val levelMosaic: String
    val levelCover: String
    val levelPoster: String
    val levelImmersive: String

    // —— 控件 ——
    val crownLabel: String
    val crownHint: String

    // —— 读数条 ——
    val hudZoom: String
    val hudUnit: String
    val hudCols: String
    val hudRows: String

    /** @param big `2:2` 数量 @param wide `2:1` 数量 @param tall `1:2` 数量 @param square `1:1` 数量 */
    fun hudMix(big: Int, wide: Int, tall: Int, square: Int): String

    // —— 排序（即构图） ——
    val sortRecent: String
    val sortTitle: String
    val sortType: String
    val sortLabel: String

    // —— 海报 / 沉浸 ——
    val posterPlay: String
    val posterClose: String
    val pause: String
    val moreOptions: String
    val skipPrevious: String
    val skipNext: String
    val nowPlaying: String
    val immersiveQueue: String

    // —— 状态 ——
    val loading: String
    val loadingNote: String
    val empty: String
    val emptyNote: String
    val failed: String
    val retry: String

    /** 点了播放但拿不到可播的曲目（专辑为空 / 未登录等）。 */
    val playFailed: String

    // —— 内容类型 ——
    val kindAlbum: String
    val kindSong: String
    val kindLocal: String

    /** 副标题：`歌手 · 年份 · 类型` 之类，缺项自动省略。 */
    fun subtitle(artist: String?, year: String?, kind: String): String

    // —— Halcyon PosterWall 海报墙 ——
    fun posterWallCount(count: Int): String
    val posterWallQueue: String
    val posterWallLibrary: String
    val posterWallLiked: String
    val posterWallLocal: String
    val posterWallSearch: String
    val posterWallInfinite: String
    val posterWallFullscreen: String
    val posterWallTheater: String
    val posterWallZoomIn: String
    val posterWallZoomOut: String
    val posterWallGestureHint: String
    val posterWallRemoteHint: String
    val posterWallEmptyQueue: String
    val posterWallEmptyLibrary: String
    val posterWallNoResults: String
    val posterWallLoading: String
    val posterWallBrowseLibrary: String
    val posterWallCurrent: String
    val posterWallSelected: String
    val posterWallRefresh: String
    val posterWallLocate: String
    val posterWallLyricsStyle: String
    val posterWallStacked: String
    val posterWallStackGestureHint: String
    val posterWallInsertAbove: String
    val posterWallInsertBelow: String
    val posterWallExpand: String
    val styleOrbit: String
    val styleClock: String
    val styleColorField: String
    val styleGlow: String
}

object WallStringsZh : WallStrings {
    override val entryLabel = "专辑墙"
    override val entryNote = "把音乐库铺成一面可无极缩放的封面墙"

    override val screenTitle = "专辑墙"

    override val levelDust = "尘埃"
    override val levelMosaic = "马赛克"
    override val levelCover = "封面"
    override val levelPoster = "海报"
    override val levelImmersive = "沉浸"

    override val crownLabel = "CROWN"
    override val crownHint = "上下拖动 = 无极缩放，双击回到封面层"

    override val hudZoom = "焦距"
    override val hudUnit = "单位格"
    override val hudCols = "列"
    override val hudRows = "行"
    override fun hudMix(big: Int, wide: Int, tall: Int, square: Int) =
        "大 $big · 横 $wide · 竖 $tall · 标准 $square"

    override val sortRecent = "最近"
    override val sortTitle = "标题"
    override val sortType = "类型"
    override val sortLabel = "构图"

    override val posterPlay = "播放"
    override val posterClose = "收起"
    override val pause = "暂停"
    override val moreOptions = "更多选项"
    override val skipPrevious = "上一首"
    override val skipNext = "下一首"
    override val nowPlaying = "正在播放"
    override val immersiveQueue = "接下来"

    override val loading = "正在铺开音乐库"
    override val loadingNote = "拉取专辑、歌曲与封面"
    override val empty = "还没有可上墙的内容"
    override val emptyNote = "收藏一些专辑、喜欢几首歌，或导入本地音乐"
    override val failed = "加载失败"
    override val retry = "重试"
    override val playFailed = "这一项没有可播放的曲目"

    override val kindAlbum = "专辑"
    override val kindSong = "歌曲"
    override val kindLocal = "本地"

    override fun subtitle(artist: String?, year: String?, kind: String): String = buildList {
        artist?.takeIf { it.isNotBlank() }?.let(::add)
        year?.takeIf { it.isNotBlank() }?.let(::add)
        add(kind)
    }.joinToString(" · ")

    override fun posterWallCount(count: Int) = "$count 张音乐海报"
    override val posterWallQueue = "播放队列"
    override val posterWallLibrary = "音乐库"
    override val posterWallLiked = "我喜欢"
    override val posterWallLocal = "本地歌曲"
    override val posterWallSearch = "搜索歌曲、艺术家或专辑"
    override val posterWallInfinite = "无限滚动"
    override val posterWallFullscreen = "全屏海报墙"
    override val posterWallTheater = "全屏歌词播放"
    override val posterWallZoomIn = "放大海报"
    override val posterWallZoomOut = "缩小海报"
    override val posterWallGestureHint = "拖动浏览 · 双指/滚轮缩放 · 点击展开 · 长按更多"
    override val posterWallRemoteHint = "方向键移动 · 确认展开 · 长按确认或菜单打开更多 · 返回关闭"
    override val posterWallEmptyQueue = "播放队列还是空的"
    override val posterWallEmptyLibrary = "音乐库里还没有歌曲"
    override val posterWallNoResults = "没有找到匹配的音乐"
    override val posterWallLoading = "正在展开你的音乐世界…"
    override val posterWallBrowseLibrary = "浏览音乐库"
    override val posterWallCurrent = "当前歌曲"
    override val posterWallSelected = "音乐海报"
    override val posterWallRefresh = "刷新排列"
    override val posterWallLocate = "定位当前播放歌曲"
    override val posterWallLyricsStyle = "歌词样式"
    override val posterWallStacked = "叠放模式"
    override val posterWallStackGestureHint = "长按拖拽整理叠放 · 展开左右滑动切换"
    override val posterWallInsertAbove = "置于上方"
    override val posterWallInsertBelow = "置于下方"
    override val posterWallExpand = "展开海报"
    override val styleOrbit = "回环"
    override val styleClock = "时计"
    override val styleColorField = "凝彩"
    override val styleGlow = "绘光"
}

object WallStringsEn : WallStrings {
    override val entryLabel = "Album wall"
    override val entryNote = "Lay out your library as a continuously zoomable wall of covers"

    override val screenTitle = "Album wall"

    override val levelDust = "Dust"
    override val levelMosaic = "Mosaic"
    override val levelCover = "Cover"
    override val levelPoster = "Poster"
    override val levelImmersive = "Immersive"

    override val crownLabel = "CROWN"
    override val crownHint = "Drag up or down to zoom, double-tap to reset"

    override val hudZoom = "Zoom"
    override val hudUnit = "unit"
    override val hudCols = "cols"
    override val hudRows = "rows"
    override fun hudMix(big: Int, wide: Int, tall: Int, square: Int) =
        "big $big · wide $wide · tall $tall · square $square"

    override val sortRecent = "Recent"
    override val sortTitle = "Title"
    override val sortType = "Type"
    override val sortLabel = "Compose"

    override val posterPlay = "Play"
    override val posterClose = "Collapse"
    override val pause = "Pause"
    override val moreOptions = "More options"
    override val skipPrevious = "Previous"
    override val skipNext = "Next"
    override val nowPlaying = "Now playing"
    override val immersiveQueue = "Up next"

    override val loading = "Unfolding your library"
    override val loadingNote = "Fetching albums, songs and covers"
    override val empty = "Nothing to put on the wall yet"
    override val emptyNote = "Favourite some albums, like a few songs, or import local music"
    override val failed = "Could not load"
    override val retry = "Retry"
    override val playFailed = "Nothing playable in this item"

    override val kindAlbum = "Album"
    override val kindSong = "Song"
    override val kindLocal = "Local"

    override fun subtitle(artist: String?, year: String?, kind: String): String = buildList {
        artist?.takeIf { it.isNotBlank() }?.let(::add)
        year?.takeIf { it.isNotBlank() }?.let(::add)
        add(kind)
    }.joinToString(" · ")

    override fun posterWallCount(count: Int) = "$count music posters"
    override val posterWallQueue = "Queue"
    override val posterWallLibrary = "Library"
    override val posterWallLiked = "Liked"
    override val posterWallLocal = "Local"
    override val posterWallSearch = "Search songs, artists or albums"
    override val posterWallInfinite = "Infinite scroll"
    override val posterWallFullscreen = "Fullscreen wall"
    override val posterWallTheater = "Theater player"
    override val posterWallZoomIn = "Zoom in"
    override val posterWallZoomOut = "Zoom out"
    override val posterWallGestureHint = "Drag pan · Pinch/Scroll zoom · Tap expand · Long press more"
    override val posterWallRemoteHint = "D-pad move · Confirm expand · Long-press or Menu for more · Back close"
    override val posterWallEmptyQueue = "Playback queue is empty"
    override val posterWallEmptyLibrary = "No songs in library yet"
    override val posterWallNoResults = "No matching music found"
    override val posterWallLoading = "Unfolding your music world…"
    override val posterWallBrowseLibrary = "Browse library"
    override val posterWallCurrent = "Current song"
    override val posterWallSelected = "Music poster"
    override val posterWallRefresh = "Shuffle posters"
    override val posterWallLocate = "Locate playing song"
    override val posterWallLyricsStyle = "Lyrics style"
    override val posterWallStacked = "Stacked"
    override val posterWallStackGestureHint = "Long press to organize stacks · Swipe to switch"
    override val posterWallInsertAbove = "Insert above"
    override val posterWallInsertBelow = "Insert below"
    override val posterWallExpand = "Expand poster"
    override val styleOrbit = "Orbit"
    override val styleClock = "Clock"
    override val styleColorField = "Color field"
    override val styleGlow = "Glow"
}
