package cp.player.app.i18n

/**
 * 「歌曲更多 / 歌单更多」弹层（`ui/component/MoreOptionsSheet.kt`）的文案。
 *
 * 单独成组：这两个弹层被首页、搜索、曲库、歌单详情等**十几个入口**共用，文案必须
 * 一处一份；跟着某个页面走就会出现「同一个按钮三种说法」。
 *
 * ⚠️ 与 `library.playNext` / `player.addToPlaylist` 不共用：那些是**动作反馈**
 * （「将在下一首播放」），这里是**菜单项**（「下一首播放」），两句话不一样。
 */
interface ContextMenuStrings {
    // —— 歌曲 ——
    val playNext: String
    val addToQueue: String
    val playlist: String
    val playMv: String
    val addToLocalPlaylist: String
    val openInFileManager: String
    val removeFromLibrary: String
    val deleteLocalFile: String
    val favorite: String
    val unfavorite: String

    // —— 歌单 ——
    val ownedPlaylist: String
    val convertedPlaylist: String
    val dailyRecommendation: String
    val showDailyRecommendations: String
    val showDailyStyleRecommendations: String
    val favoritedPlaylist: String
    val publicPlaylist: String
    val unknownCreator: String
    fun trackCount(count: Int): String
    val moreActions: String
    val backOneLevel: String
    val sharePlaylist: String
    val convertToPlaylist: String
    val alreadyConverted: String
    val refreshPlaylist: String
    val sortBy: String
    val sortDefaultShort: String
}

object ContextMenuStringsZh : ContextMenuStrings {
    override val playNext = "下一首播放"
    override val addToQueue = "添加到队列"
    override val playlist = "歌单"
    override val playMv = "播放 MV"
    override val addToLocalPlaylist = "添加到本地歌单"
    override val openInFileManager = "在文件管理器中打开"
    override val removeFromLibrary = "从曲库移除"
    override val deleteLocalFile = "彻底删除本地文件"
    override val favorite = "收藏"
    override val unfavorite = "取消收藏"

    override val ownedPlaylist = "我创建的歌单"
    override val convertedPlaylist = "已转为歌单"
    override val dailyRecommendation = "每日推荐"
    override val showDailyRecommendations = "切换到每日推荐"
    override val showDailyStyleRecommendations = "切换到风格推荐"
    override val favoritedPlaylist = "已收藏歌单"
    override val publicPlaylist = "公开歌单"
    override val unknownCreator = "未知"
    override fun trackCount(count: Int) = "$count 首"
    override val moreActions = "更多操作"
    override val backOneLevel = "返回上一级"
    override val sharePlaylist = "分享歌单"
    override val convertToPlaylist = "一键转歌单"
    override val alreadyConverted = "已转歌单"
    override val refreshPlaylist = "刷新歌单"
    override val sortBy = "排序方式"
    override val sortDefaultShort = "默认"
}

object ContextMenuStringsEn : ContextMenuStrings {
    override val playNext = "Play next"
    override val addToQueue = "Add to queue"
    override val playlist = "Playlist"
    override val playMv = "Play MV"
    override val addToLocalPlaylist = "Add to a local playlist"
    override val openInFileManager = "Show in file manager"
    override val removeFromLibrary = "Remove from library"
    override val deleteLocalFile = "Delete the local file permanently"
    override val favorite = "Favorite"
    override val unfavorite = "Remove favorite"

    override val ownedPlaylist = "Your playlist"
    override val convertedPlaylist = "Converted to a playlist"
    override val dailyRecommendation = "Daily recommendation"
    override val showDailyRecommendations = "Switch to daily picks"
    override val showDailyStyleRecommendations = "Switch to style picks"
    override val favoritedPlaylist = "Favorite playlist"
    override val publicPlaylist = "Public playlist"
    override val unknownCreator = "Unknown"
    override fun trackCount(count: Int) = if (count == 1) "1 track" else "$count tracks"
    override val moreActions = "More actions"
    override val backOneLevel = "Go back"
    override val sharePlaylist = "Share playlist"
    override val convertToPlaylist = "Convert to a playlist"
    override val alreadyConverted = "Already converted"
    override val refreshPlaylist = "Refresh playlist"
    override val sortBy = "Sort by"
    override val sortDefaultShort = "Default"
}
