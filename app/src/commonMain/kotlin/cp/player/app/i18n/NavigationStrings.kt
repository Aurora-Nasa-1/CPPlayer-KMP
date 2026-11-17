package cp.player.app.i18n

/**
 * 主导航的文案：底部 tab、桌面侧栏分组 / 入口、窗口 chrome 上的全局入口。
 *
 * 单独成组而不是散进各页：这些字是**跨页面共享的导航骨架**（`首页` 既是 tab 名，
 * 也是桌面标题栏的页面标题；`消息` 同时出现在侧栏、手机顶栏与窗口标题栏）。
 * 散在各处就会出现同一个入口两种译法。
 */
interface NavigationStrings {
    // —— 四个主 tab ——
    val tabHome: String
    val tabSearch: String
    val tabDynamic: String
    val tabLibrary: String

    // —— 侧栏分组标题 ——
    val sectionDiscover: String
    val sectionMyMusic: String
    val sectionMyPlaylists: String

    // —— 侧栏 / 全局入口 ——
    val likedMusic: String
    val localMusic: String
    val recentPlays: String
    val downloads: String
    val messages: String
    val settings: String
    val account: String

    // —— 本地音乐内的子页标题 ——
    val localLiked: String
    val localPlaylists: String

    /** 侧栏「查看全部 N 个歌单」。 */
    fun allPlaylists(count: Int): String
}

object NavigationStringsZh : NavigationStrings {
    override val tabHome = "首页"
    override val tabSearch = "搜索"
    override val tabDynamic = "动态"
    override val tabLibrary = "我的"

    override val sectionDiscover = "发现音乐"
    override val sectionMyMusic = "我的音乐"
    override val sectionMyPlaylists = "我的歌单"

    override val likedMusic = "我喜欢的音乐"
    override val localMusic = "本地音乐"
    override val recentPlays = "最近播放"
    override val downloads = "下载管理"
    override val messages = "消息"
    override val settings = "设置"
    override val account = "账号"

    override val localLiked = "本地喜欢"
    override val localPlaylists = "本地歌单"

    override fun allPlaylists(count: Int) = "查看全部 $count 个歌单"
}

object NavigationStringsEn : NavigationStrings {
    override val tabHome = "Home"
    override val tabSearch = "Search"
    override val tabDynamic = "Feed"
    override val tabLibrary = "Library"

    override val sectionDiscover = "Discover"
    override val sectionMyMusic = "My Music"
    override val sectionMyPlaylists = "My Playlists"

    override val likedMusic = "Liked Songs"
    override val localMusic = "Local Music"
    override val recentPlays = "Recently Played"
    override val downloads = "Downloads"
    override val messages = "Messages"
    override val settings = "Settings"
    override val account = "Account"

    override val localLiked = "Local Likes"
    override val localPlaylists = "Local Playlists"

    override fun allPlaylists(count: Int) = if (count == 1) "View 1 playlist" else "View all $count playlists"
}
