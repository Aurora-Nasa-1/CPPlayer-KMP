package cp.player.app.i18n

/**
 * 听歌打卡与外部平台同步文案。
 */
interface ScrobbleStrings {
    val entryTitle: String
    val entrySubtitle: String
    val screenTitle: String
    val screenNote: String

    val sectionPlatforms: String
    val sectionFeatures: String
    val sectionQueue: String
    val sectionAdvanced: String

    // Last.fm
    val lastFmTitle: String
    val lastFmDesc: String
    val lastFmConnected: String
    val lastFmDisconnected: String
    val lastFmConnectBtn: String
    val lastFmDisconnectBtn: String
    val lastFmDisconnectConfirmTitle: String
    val lastFmDisconnectConfirmMessage: String
    val lastFmAuthDialogTitle: String
    val lastFmAuthDialogMessage: String
    val lastFmAuthDialogConfirm: String
    val lastFmAuthDialogCancel: String
    val lastFmAuthSuccessToast: String
    val lastFmAuthFailToast: String

    // 功能开关
    val enableScrobbleTitle: String
    val enableScrobbleDesc: String
    val nowPlayingTitle: String
    val nowPlayingDesc: String
    val loveSyncTitle: String
    val loveSyncDesc: String
    val includeLocalTitle: String
    val includeLocalDesc: String

    // 离线队列
    val todayScrobbles: (Int) -> String
    val pendingQueueCount: (Int) -> String
    val queueEmpty: String
    val flushQueueBtn: String
    val clearQueueBtn: String
    val clearQueueConfirmTitle: String
    val clearQueueConfirmMessage: String

    // 高级设置
    val customApiKeyTitle: String
    val customSecretTitle: String
    val customCredsDesc: String
    val customApiKeyPlaceholder: String
    val customSecretPlaceholder: String
    val resetCustomCreds: String

    // 听歌报告 / 最近播放联动
    val insightsSyncConnected: (username: String, todayCount: Int) -> String
    val insightsSyncDisconnected: String
    val insightsGoToSettings: String
    val recentScrobbleSyncing: String
}

object ScrobbleStringsZh : ScrobbleStrings {
    override val entryTitle = "听歌打卡与外部同步"
    override val entrySubtitle = "Last.fm 等音乐打卡服务、正在播放与红心同步"
    override val screenTitle = "听歌打卡 (Scrobble)"
    override val screenNote = "播放曲目时自动向 Last.fm 等外部平台打卡、同步「正在播放」状态与红心喜爱，支持本地音乐与离线重发。"

    override val sectionPlatforms = "打卡平台"
    override val sectionFeatures = "同步行为"
    override val sectionQueue = "离线待办队列"
    override val sectionAdvanced = "高级设置 (自定义 API Key)"

    override val lastFmTitle = "Last.fm"
    override val lastFmDesc = "全球最大的音乐记录与推荐社区"
    override val lastFmConnected = "已连接"
    override val lastFmDisconnected = "未连接"
    override val lastFmConnectBtn = "连接 Last.fm"
    override val lastFmDisconnectBtn = "断开连接"
    override val lastFmDisconnectConfirmTitle = "断开 Last.fm 连接？"
    override val lastFmDisconnectConfirmMessage = "断开后将暂停向 Last.fm 打卡和同步，本地播放历史不受影响。"
    override val lastFmAuthDialogTitle = "正在完成 Last.fm 授权"
    override val lastFmAuthDialogMessage = "已在浏览器中打开 Last.fm 授权页面。在网页中点击「YES, ALLOW ACCESS」允许授权后，返回本窗口点击完成。"
    override val lastFmAuthDialogConfirm = "已完成网页授权"
    override val lastFmAuthDialogCancel = "取消"
    override val lastFmAuthSuccessToast = "Last.fm 绑定成功"
    override val lastFmAuthFailToast = "Last.fm 授权失败，请重试"

    override val enableScrobbleTitle = "启用听歌打卡"
    override val enableScrobbleDesc = "当有效播放达到歌曲时长的 50% 或满 4 分钟时自动提交记录"
    override val nowPlayingTitle = "上报「正在播放」"
    override val nowPlayingDesc = "起播时向个人主页展示实时播放状态"
    override val loveSyncTitle = "同步红心喜爱"
    override val loveSyncDesc = "在播放器或曲目菜单收藏/取消收藏时，同步标记至 Last.fm"
    override val includeLocalTitle = "包含本地音乐"
    override val includeLocalDesc = "本地曲目拥有歌手与曲名时同样参与打卡"

    override val todayScrobbles: (Int) -> String = { "今日已打卡 $it 首" }
    override val pendingQueueCount: (Int) -> String = { "离线待提交 $it 条" }
    override val queueEmpty = "暂无离线待办条目"
    override val flushQueueBtn = "立即同步离线记录"
    override val clearQueueBtn = "清空离线队列"
    override val clearQueueConfirmTitle = "清空离线待办队列？"
    override val clearQueueConfirmMessage = "未提交成功的离线打卡记录将被丢弃且无法恢复。"

    override val customApiKeyTitle = "自定义 API Key"
    override val customSecretTitle = "自定义 Shared Secret"
    override val customCredsDesc = "默认已内置可用凭据。若有自己的开发者账号，可在此填写覆盖。"
    override val customApiKeyPlaceholder = "留空则使用内置 Key"
    override val customSecretPlaceholder = "留空则使用内置 Secret"
    override val resetCustomCreds = "恢复内置凭据"

    override val insightsSyncConnected: (String, Int) -> String = { user, count -> "Last.fm 已连接 (@$user) · 今日已打卡 $count 首" }
    override val insightsSyncDisconnected = "连接 Last.fm 同步听歌记录与正在播放"
    override val insightsGoToSettings = "管理打卡"
    override val recentScrobbleSyncing = "正在向外部平台同步…"
}

object ScrobbleStringsEn : ScrobbleStrings {
    override val entryTitle = "Scrobble & External Sync"
    override val entrySubtitle = "Sync play records, Now Playing, and favorites with Last.fm"
    override val screenTitle = "Scrobble & Integrations"
    override val screenNote = "Automatically scrobble plays, broadcast Now Playing status, and sync favorites with Last.fm and other services."

    override val sectionPlatforms = "Platforms"
    override val sectionFeatures = "Sync Preferences"
    override val sectionQueue = "Offline Pending Queue"
    override val sectionAdvanced = "Advanced (Custom API Key)"

    override val lastFmTitle = "Last.fm"
    override val lastFmDesc = "Music tracking and recommendation community"
    override val lastFmConnected = "Connected"
    override val lastFmDisconnected = "Disconnected"
    override val lastFmConnectBtn = "Connect Last.fm"
    override val lastFmDisconnectBtn = "Disconnect"
    override val lastFmDisconnectConfirmTitle = "Disconnect Last.fm?"
    override val lastFmDisconnectConfirmMessage = "Scrobbling and syncing will be paused. Local play history is kept."
    override val lastFmAuthDialogTitle = "Complete Last.fm Authorization"
    override val lastFmAuthDialogMessage = "The authorization page has been opened in your browser. Click 'YES, ALLOW ACCESS', then return here and confirm."
    override val lastFmAuthDialogConfirm = "I've authorized"
    override val lastFmAuthDialogCancel = "Cancel"
    override val lastFmAuthSuccessToast = "Last.fm connected successfully"
    override val lastFmAuthFailToast = "Failed to connect Last.fm. Please try again."

    override val enableScrobbleTitle = "Enable Scrobbling"
    override val enableScrobbleDesc = "Scrobble when 50% of track length or 4 minutes has played"
    override val nowPlayingTitle = "Broadcast Now Playing"
    override val nowPlayingDesc = "Show what you're currently listening to on your profile"
    override val loveSyncTitle = "Sync Favorites (Love)"
    override val loveSyncDesc = "Sync favorite and unfavorite actions to Last.fm"
    override val includeLocalTitle = "Include Local Files"
    override val includeLocalDesc = "Scrobble local tracks when title and artist are present"

    override val todayScrobbles: (Int) -> String = { "Today: $it tracks scrobbled" }
    override val pendingQueueCount: (Int) -> String = { "$it offline pending" }
    override val queueEmpty = "No pending offline scrobbles"
    override val flushQueueBtn = "Retry Offline Queue Now"
    override val clearQueueBtn = "Clear Offline Queue"
    override val clearQueueConfirmTitle = "Clear offline queue?"
    override val clearQueueConfirmMessage = "Pending offline scrobbles will be discarded."

    override val customApiKeyTitle = "Custom API Key"
    override val customSecretTitle = "Custom Shared Secret"
    override val customCredsDesc = "Built-in credentials are used by default. Enter your own if desired."
    override val customApiKeyPlaceholder = "Leave empty to use built-in key"
    override val customSecretPlaceholder = "Leave empty to use built-in secret"
    override val resetCustomCreds = "Reset to Built-in Credentials"

    override val insightsSyncConnected: (String, Int) -> String = { user, count -> "Last.fm connected (@$user) · $count scrobbled today" }
    override val insightsSyncDisconnected = "Connect Last.fm to scrobble and track listening history"
    override val insightsGoToSettings = "Manage"
    override val recentScrobbleSyncing = "Syncing with external services…"
}
