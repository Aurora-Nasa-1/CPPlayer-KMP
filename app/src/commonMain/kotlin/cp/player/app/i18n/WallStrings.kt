package cp.player.app.i18n

/**
 * 专辑墙模式（`ui/wall`）的文案。
 *
 * 单独成组而不是并进 `LibraryStrings`：墙是一个**跨数据源的模式**（专辑 / 歌单 / 艺人
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
    val sortColor: String
    val sortArtist: String
    val sortPlays: String
    val sortYear: String
    /** 曲目数排序。 */
    val sortTracks: String
    val sortLabel: String

    // —— 海报 / 沉浸 ——
    val posterPlay: String
    val posterClose: String
    val nowPlaying: String
    val immersiveQueue: String

    // —— 状态 ——
    val loading: String
    val loadingNote: String
    val empty: String
    val emptyNote: String
    val failed: String
    val retry: String

    // —— 内容类型 ——
    val kindAlbum: String
    val kindPlaylist: String
    val kindSingle: String
    val kindArtist: String

    /** 副标题：`歌手 · 年份` 之类，缺项自动省略。 */
    fun subtitle(artist: String?, year: String?, kind: String): String
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

    override val sortRecent = "最近添加"
    override val sortColor = "色彩"
    override val sortArtist = "歌手"
    override val sortPlays = "热度"
    override val sortYear = "年份"
    override val sortTracks = "曲目数"
    override val sortLabel = "构图"

    override val posterPlay = "播放"
    override val posterClose = "收起"
    override val nowPlaying = "正在播放"
    override val immersiveQueue = "接下来"

    override val loading = "正在铺开音乐库"
    override val loadingNote = "拉取专辑与封面"
    override val empty = "还没有可上墙的内容"
    override val emptyNote = "收藏一些专辑，或把本地音乐导入后再来"
    override val failed = "加载失败"
    override val retry = "重试"

    override val kindAlbum = "专辑"
    override val kindPlaylist = "歌单"
    override val kindSingle = "单曲"
    override val kindArtist = "艺人"

    override fun subtitle(artist: String?, year: String?, kind: String): String = buildList {
        artist?.takeIf { it.isNotBlank() }?.let(::add)
        year?.takeIf { it.isNotBlank() }?.let(::add)
        add(kind)
    }.joinToString(" · ")
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

    override val sortRecent = "Recently added"
    override val sortColor = "Colour"
    override val sortArtist = "Artist"
    override val sortPlays = "Plays"
    override val sortYear = "Year"
    override val sortTracks = "Tracks"
    override val sortLabel = "Compose"

    override val posterPlay = "Play"
    override val posterClose = "Collapse"
    override val nowPlaying = "Now playing"
    override val immersiveQueue = "Up next"

    override val loading = "Unfolding your library"
    override val loadingNote = "Fetching albums and covers"
    override val empty = "Nothing to put on the wall yet"
    override val emptyNote = "Favourite some albums or import local music first"
    override val failed = "Could not load"
    override val retry = "Retry"

    override val kindAlbum = "Album"
    override val kindPlaylist = "Playlist"
    override val kindSingle = "Single"
    override val kindArtist = "Artist"

    override fun subtitle(artist: String?, year: String?, kind: String): String = buildList {
        artist?.takeIf { it.isNotBlank() }?.let(::add)
        year?.takeIf { it.isNotBlank() }?.let(::add)
        add(kind)
    }.joinToString(" · ")
}
