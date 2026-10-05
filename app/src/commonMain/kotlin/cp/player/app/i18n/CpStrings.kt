package cp.player.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/**
 * 应用文案的**唯一取值入口**。
 *
 * ### 为什么是自己这套，而不是 `compose-resources` 的 `Res.string`
 *
 * 选过 compose-resources，**做不到**（方案被否定的依据见 `docs/dev/I18N.md` §1）：
 * 换语言走的 `LocalComposeEnvironment` 在 1.12.1 里是
 * `internal val`（源码：`ResourceEnvironment.kt`），连 `ResourceEnvironment` 的构造函数
 * 都是 `internal constructor` —— 应用侧**没有任何**合规路径改变资源使用的语言，
 * 唯一的 public API 是只能取系统值的 `getSystemResourceEnvironment()`。
 * （`javap` 会把它们显示成 public class —— 那是 JVM 字节码层面，Kotlin 元数据里仍是
 * internal，两者别混为一谈。）
 *
 * 与其为了「官方」而牺牲「应用内切换语言」，这里自己管一份。
 * 代价与我们放弃的东西相比很小，而且刚好补上了 compose-resources 对本仓库的第二个
 * 短板：**本仓库约一半文案不在组合上下文里**
 * （`UiEvents.notify(...)`、`ScreenModel`、塞进返回栈的 `StartupScreen("…")`），
 * `stringResource()` 在那里根本调不了，而这里的 [CpStrings] 是个普通对象，哪儿都能读。
 *
 * ### 两份实现的完整性由编译器保证
 *
 * [CpStringsZh] / [CpStringsEn] 都实现同一个接口，**漏哪一条 Kotlin 编译不过** ——
 * 这比 XML 里缺 key 静默回落到另一种语言（要靠脚本查）可靠得多。
 *
 * ### 加文案的步骤
 *
 * 1. 在下面对应的分组接口里加成员（找不到合适的分组就新建一个，参考 §area 划分）；
 * 2. `CpStringsZh` / `CpStringsEn` 各写一份；
 * 3. 调用点读 [cpStrings]。
 */
interface CpStrings {
    val language: LanguageStrings
    val settings: SettingsStrings

    /** 设置组件库（`SettingsKit`）里**跨页复用**的文案。 */
    val common: CommonStrings

    /** 私信新消息通知 + 桌面托盘常驻。 */
    val messageNotify: MessageNotifyStrings

    val appearance: AppearanceStrings
    val storage: StorageStrings
    val songCache: SongCacheStrings
    val playback: PlaybackStrings
    val shortcuts: ShortcutStrings
    val quality: QualityStrings

    val streamOutput: StreamOutputStrings
    val integration: IntegrationStrings
    val standby: StandbyStrings

    /**
     * 播放相关：播放页、播放控制、队列底栏、「更多」弹层。
     *
     * 合在一个分组而不是按文件拆：这些标签大量**互相引用**（「上一首」在
     * `PlaybackControls` 与 `PlayerScreen` 各出现一次，「分享」在更多面板与队列
     * 底栏各一次），拆开就会出现同一条文案两种译法。
     */
    val player: PlayerStrings

    /** 媒体库 / 歌单：列表页、详情页、上下文菜单、多选工具条。 */
    val library: LibraryStrings
    val album: AlbumStrings
    val playlistSheet: PlaylistSheetStrings

    /** 账号与登录、用户/歌手主页、云盘。 */
    val account: AccountStrings

    /** 下载管理。 */
    val downloads: DownloadStrings

    /** 音乐洞察、听歌诊断、关于、听歌日历。 */
    val insights: InsightStrings

    /** 一起听、私信、消息、评论。 */
    val social: SocialStrings

    companion object {
        /** 简体中文实例。 */
        val zh: CpStrings get() = CpStringsZh

        /** 英文实例。 */
        val en: CpStrings get() = CpStringsEn

        /**
         * 按 [AppLanguage] 解析出实例。
         *
         * [AppLanguage.SYSTEM] 时读 Compose 的 `Locale.current`（Android 跟 Configuration、
         * 桌面跟 JVM 默认 locale），**命中英文才用英文，其余一律中文** ——
         * 中文是兜底语言（理由见 [AppLanguage] 的 KDoc）。
         */
        fun of(language: AppLanguage): CpStrings = when (language) {
            AppLanguage.ZH_HANS -> zh
            AppLanguage.ENGLISH -> en
            AppLanguage.SYSTEM -> if (Locale.current.language.equals("en", ignoreCase = true)) en else zh
        }
    }
}

/** 「标题 + 副标题」成对出现的文案（设置入口几乎都是这个形状）。 */
data class CpTextPair(
    val title: String,
    val subtitle: String,
)

// ---------------------------------------------------------------------------
// 各区域分组。按「谁在显示」划分，不按「数据从哪来」划分 ——
// 目的是让迁移时能一个屏幕一个屏幕地做，而不是一次改遍全局。
// ---------------------------------------------------------------------------

interface LanguageStrings {
    val screenTitle: String
    val optionSystem: String
    val optionSystemNote: String
    val optionZhHans: String
    val optionEnglish: String
    val current: String
    val applyNote: String
}

interface SettingsStrings {
    val screenTitle: String
    val groupGeneral: String
    val groupAccountProvider: String
    val groupConnectivity: String
    val groupOther: String
    val itemLanguage: CpTextPair
    val itemAppearance: CpTextPair
    val itemPlayback: CpTextPair
    val itemStorage: CpTextPair
    val itemShortcuts: CpTextPair
    val itemAccount: CpTextPair
    val itemProviders: CpTextPair
    val itemStreamOutput: CpTextPair
    val itemIntegration: CpTextPair
    val itemStandby: CpTextPair
    val itemAbout: CpTextPair
    val itemDiagnostics: CpTextPair
    val itemRenderTuning: CpTextPair
    val itemOnboarding: CpTextPair
}

/**
 * 跨页复用的通用词。
 *
 * 单独成组而不是散落各页：确认框的「确认 / 取消」、输入行的「应用 / 未保存」
 * 出现在**每一页**的 `SettingsKit` 组件里。放这里才能保证同一个按钮在十个页面
 * 上是同一个词，且加语言时只改一处。
 */
interface CommonStrings {
    val confirm: String
    val dismiss: String
    val apply: String
    val unsavedBlocked: String
    val unsavedHint: String
}

// ---------------------------------------------------------------------------
// 私信通知 + 桌面托盘
// ---------------------------------------------------------------------------

/**
 * 私信新消息通知与桌面托盘常驻的文案。
 *
 * 单独成组是因为这批文案**跨越三个互不相邻的界面**：消息列表（右键菜单 / 引导弹层）、
 * 设置页（通知分组）、以及托盘菜单与关窗确认框。散在各处的话，
 * 「开启新消息通知」会在菜单和设置页出现两种说法。
 */
interface MessageNotifyStrings {
    /** 首次进入消息页的引导。 */
    val guideTitle: String
    val guideBody: String
    val guideConfirm: String

    /** 联系人行右键 / 长按。 */
    val menuEnable: String
    val menuDisable: String
    val sheetTitle: String
    val sheetBody: String

    /** 设置页分组。 */
    val settingsTitle: String
    val settingsSubtitle: String
    val masterLabel: String
    val masterHint: String
    val subscribedSection: String
    val subscribedEmpty: String
    val unsupportedPlatform: String
    val permissionMissing: String
    val grantPermission: String

    /** 桌面托盘菜单。 */
    val trayShowWindow: String
    val trayExit: String

    /** 关窗确认。 */
    val closeDialogTitle: String
    val closeDialogMessage: String
    val closeDialogMinimize: String
    val closeDialogExit: String
    val closeDialogDontAsk: String

    /** 设置页里的「关闭窗口时」选项（桌面）。 */
    val closeBehaviorLabel: String
    val closeBehaviorAsk: String
    val closeBehaviorHint: String
}

// ---------------------------------------------------------------------------
// 外观与主题
// ---------------------------------------------------------------------------

interface AppearanceStrings {
    val screenTitle: String
    val sectionLook: String
    val sectionFont: String

    val themeMode: String
    val themeModeSystem: String
    val themeModeLight: String
    val themeModeDark: String

    val colorSource: String
    val colorSourcePlatform: String
    val colorSourceCover: String
    val colorSourceFixed: String
    val colorSourcePlatformOn: String
    val colorSourcePlatformOff: String
    val colorSourceCoverNote: String
    val colorSourceFixedNote: String

    val pureBlack: String
    val pureBlackNote: String
    val autoHideBottomBar: String
    val autoHideBottomBarNote: String
    val coverFlight: String
    val coverFlightNote: String

    val fontRoundness: String

    /** @param defaultRoundness 当前平台的默认圆滑度（各平台不一样，所以是参数）。 */
    fun fontRoundnessNote(defaultRoundness: Int): String

    /** 滑条右侧的「· 默认」后缀。 */
    val roundnessDefaultTag: String
    val resetPlatformDefault: String
    val resetPlatformDefaultNote: String
    val roundnessNote: String
}

// ---------------------------------------------------------------------------
// 下载与存储
// ---------------------------------------------------------------------------

interface StorageStrings {
    val screenTitle: String

    val sectionDownload: String
    val downloadedMusic: String
    val downloadedMusicEmpty: String

    /** @param count 曲目数；@param bytes 已格式化的体积（如 `128 MB`）。 */
    fun downloadedMusicSummary(count: Int, bytes: String): String

    val downloadDir: String
    val downloadDirAndroid: String
    val downloadDirDefault: String
    val openDir: String
    val openDirNote: String
    val dirUnset: String
    fun openDirFailed(dir: String): String

    val sectionSongCache: String
    val cachedSongs: String
    val cachedSongsEmpty: String
    fun cachedSongsSummary(count: Int, bytes: String, capacity: String): String
    val cacheCapacity: String
    val cacheCapacityNote: String
    val clearStaleCache: String
    val clearStaleCacheNote: String
    val clearSongCache: String
    val clearSongCacheNote: String
    val clearSongCacheConfirmTitle: String
    fun clearSongCacheConfirmMessage(count: Int, bytes: String): String
    val openCacheDir: String
    val openCacheDirNote: String
    val openCacheDirFailed: String

    val sectionApiCache: String
    val apiCacheEntries: String
    val apiCacheEmpty: String
    fun apiCacheEntryCount(count: Int): String
    fun apiCacheHitRate(rate: Int): String
    val clearApiCache: String
    val clearApiCacheNote: String

    val sectionImageCache: String
    val imageCache: String
    val imageCacheMeasuring: String
    fun imageCacheUsage(bytes: String): String
    val clearImageCache: String
    val clearImageCacheNote: String

    val noteAndroid: String
    val noteDesktop: String
    val dirUpdated: String
}

// ---------------------------------------------------------------------------
// 歌曲缓存明细页
// ---------------------------------------------------------------------------

interface SongCacheStrings {
    val screenTitle: String
    val unsupported: String
    val measuring: String
    val empty: String
    val measuringShort: String
    val emptyShort: String
    fun summary(count: Int, bytes: String): String
    fun summaryCapped(count: Int, bytes: String, capacity: String): String
    val searchPlaceholder: String
    val clearSearch: String
    val unknownTrack: String
    fun noMatch(query: String): String
    fun deleteEntry(name: String): String
    fun deleted(name: String, freed: String): String
    val deleteFailed: String

    val timeUnknown: String
    val timeJustNow: String
    fun timeMinutesAgo(minutes: Long): String
    fun timeHoursAgo(hours: Long): String
    fun timeDaysAgo(days: Long): String
    fun timeMonthsAgo(months: Long): String
}

// ---------------------------------------------------------------------------
// 播放与音质
// ---------------------------------------------------------------------------

interface PlaybackStrings {
    val screenTitle: String

    val sectionQuality: String
    val defaultQuality: String
    val defaultQualityNote: String
    val meteredQuality: String
    val meteredQualityNote: String

    val sectionLyrics: String
    val lyricsSource: String
    val lyricsSourceNote: String
    val lyricsProviderOnly: String
    val lyricsAmllFirst: String
    val lyricsAmllOnly: String

    val sectionSleepTimer: String
    val sleepTimer: String
    val sleepAfterTrack: String
    fun sleepRemaining(minutes: Long): String
    val sleepOff: String

    val sectionBackground: String
    val batteryWhitelist: String
    val batteryWhitelistOn: String
    val batteryWhitelistOff: String
    val vendorNote: String
    val sharedTimerNote: String

    /**
     * 睡眠定时**弹窗**的文案。
     *
     * 与 [SettingsStrings] 里的「睡眠定时」分组是**两回事**：那几行是设置页的
     * 静态文案，这个弹窗是设置页与播放页**共用**的（`SleepTimerDialog` 单一事实源），
     * 所以它不能塞进 `playback` 以外的组，也不能只算「播放页的」。
     */
    val timerDialogTitle: String
    fun timerActiveAfterTrack(): String
    fun timerActiveInMinutes(minutes: Long): String
    val timerPrompt: String
    fun timerMinutesChip(minutes: Int): String
    val timerAfterTrackChip: String
    val timerCancel: String
    val timerClose: String
}

// ---------------------------------------------------------------------------
// 快捷键
// ---------------------------------------------------------------------------

interface ShortcutStrings {
    val screenTitle: String
    val note: String
    val unbound: String

    val categoryPlayback: String
    val categoryMode: String
    val categoryNavigation: String

    val keySpace: String
    val keyEnter: String
    val keyBackspace: String

    val actionPlayPause: String
    val actionPlayPauseHint: String
    val actionPrevTrack: String
    val actionPrevTrackHint: String
    val actionNextTrack: String
    val actionNextTrackHint: String
    val actionSeekBackward: String
    val actionSeekForward: String
    val seekHint: String
    val actionToggleFavorite: String
    val actionToggleFavoriteHint: String
    val actionToggleShuffle: String
    val actionToggleShuffleHint: String
    val actionCycleRepeat: String
    val actionCycleRepeatHint: String
    val actionBack: String
    val actionBackHint: String
    val actionOpenSettings: String
    val actionOpenSettingsHint: String

    val resetAll: String
    val resetAllNote: String
    val resetAllConfirmTitle: String
    val resetAllConfirmMessage: String
    val resetLabel: String

    val recorderTitle: String
    fun recorderTarget(label: String): String
    val recorderWaiting: String
    val recorderWaitingHint: String
    val recorderRecordedHint: String
    fun recorderConflict(others: String): String
    val recorderSave: String
    val recorderReset: String
    val recorderClear: String
}

// ---------------------------------------------------------------------------
// 本地流输出
// ---------------------------------------------------------------------------

interface StreamOutputStrings {
    val screenTitle: String

    val sectionMain: String
    val enabled: String
    val enabledNote: String
    val audioOutput: String

    /** 分段控件「本机播放 / 仅对外提供」的两档。 */
    val outputLocal: String
    val outputRemoteOnly: String
    fun outputNote(local: Boolean): String

    val bindScope: String
    val bindScopeNote: String
    val scopeLocalhost: String
    val scopeLan: String
    val warningLanNoToken: String

    val sectionPort: String
    val port: String
    val portNote: String
    val portRestartNote: String
    fun portEmpty(): String
    fun portNotNumber(): String
    fun portOutOfRange(from: Int, to: Int): String

    val sectionToken: String
    val regenerateToken: String
    val regenerateTokenNote: String
    val regenerateConfirmTitle: String
    val regenerateConfirmMessage: String
    val regenerateLabel: String
    val tokenRegenerated: String
    val tokenStorageNote: String
    val currentToken: String
    val tokenAutoGenerated: String

    val sectionEndpoint: String
    val endpointNote: String
    val statusDisabled: String
    fun statusStartFailed(error: String): String
    fun statusRunning(url: String): String
    val statusStarting: String
}

// ---------------------------------------------------------------------------
// 外部推送与集成
// ---------------------------------------------------------------------------

interface IntegrationStrings {
    val screenTitle: String

    val sectionPush: String
    val receiverAddress: String
    val receiverAddressNote: String
    val autoPush: String
    val autoPushNote: String
    val testConnection: String

    fun testProbing(): String
    fun testIdle(endpoint: String): String

    val pushCurrentTrack: String
    val pushCurrentTrackNote: String
    val pushCurrentQueue: String
    val pushCurrentQueueNote: String

    val sectionThirdParty: String
    val allowStream: String
    val allowStreamNote: String
    val allowApi: String
    val allowApiNote: String
    val apiSwitchNote: String

    val sectionEndpoint: String
    val configFileNote: String
    val streamDisabledNote: String

    fun addressEmpty(): String
    fun addressScheme(): String
    fun addressNoHost(example: String): String
    val pushNever: String
    val pushOk: String
    fun pushFailed(reason: String): String
}

// ---------------------------------------------------------------------------
// 局域网设备（无缝转移 + 自动同步）
// ---------------------------------------------------------------------------

interface StandbyStrings {
    val screenTitle: String

    val sectionSelf: String
    val deviceName: String
    val devicePlatform: String
    val deviceId: String
    val deviceVersion: String
    val discoveryStatus: String
    val discoveryFailed: String
    val listening: String
    val notStarted: String

    val sectionVisible: String
    val visible: String
    val visibleNote: String

    val beaconSent: String
    fun beaconSentRounds(count: Long): String
    val beaconReceived: String
    val beaconInvalid: String
    val lastReceived: String
    fun discoveryStartFailed(error: String): String

    val sectionAutoSync: String
    val autoSync: String
    val autoSyncNote: String
    val syncNow: String
    val neverSynced: String
    val syncService: String
    fun syncListeningPort(port: Int): String
    val lastSync: String
    val syncWarning: String
    val syncEnabledNote: String

    val sectionKeepAlive: String
    val aggressiveStandby: String
    val aggressiveStandbyNote: String
    val notEnabled: String
    val inEffect: String
    val notInEffect: String
    val keepAliveHint: String

    val sectionLanDevices: String
    val discovering: String
    val discoveryNotStarted: String
    val onlineDevices: String
    fun onlineCount(count: Int): String
    val sectionOnline: String
    val localNotPlaying: String
    val sectionOffline: String
    fun justOnline(address: String, port: Int): String
    val troubleshooting: String
    val handoffPrereq: String
    val handoffSecurityNote: String
    val keepAliveReality: String
    val keepAliveRecommend: String

    // —— 无缝转移：状态与文案必须分开（见 I18N.md §5.8）——
    val handoffNoTrack: String
    fun handoffStarting(device: String): String
    fun handoffDone(device: String): String
    fun handoffFailed(reason: String): String
    fun handoffTakenOver(device: String, track: String): String
    fun handoffTakeOverFailed(device: String, reason: String): String
    val peerFallbackName: String
    val noResponse: String
    val cannotPlayTrack: String
    val startTimedOut: String

    // —— 设备选择弹层（与设备页同一份语义，故同组）——
    val pickerTitle: String
    fun pickerNowPlaying(track: String): String
    val pickerNoTrack: String
    fun pickerDiscoveryFailed(error: String): String
    val pickerSearching: String
    val handoffToLabel: String
    val pickerHint: String
}

// ---------------------------------------------------------------------------
// 播放
// ---------------------------------------------------------------------------

interface PlayerStrings {
    // —— 通用动作 ——
    val share: String
    val addToPlaylist: String
    val downloaded: String
    val download: String
    val dislike: String
    val disliked: String
    val listenTogether: String
    val transferDevice: String
    val play: String
    val removeFromQueue: String
    val saveAsPlaylist: String
    val locateCurrent: String
    val clearQueue: String
    val more: String

    // —— 歌曲信息弹层 ——
    val songInfo: String
    val lyricInfo: String
    val audioFormat: String
    val infoSource: String
    val infoFormat: String
    val wordLevelLyrics: String
    val yes: String
    val no: String
    val infoTranslation: String
    val infoPhonetic: String
    val infoCodec: String
    val infoSampleRate: String
    val infoBitDepth: String
    val infoBitrate: String
    val infoChannels: String
    val unknownAlbum: String

    /** 分享文本：整段拼进剪贴板，**带换行**，所以逐项都要独立文案。 */
    fun shareTrack(name: String, artist: String, album: String, duration: String, id: String): String
    fun shareLyrics(source: String, format: String, wordLevel: String, extra: String): String
    fun shareAudio(codec: String?, sampleRate: String?, bitDepth: String?, bitrate: String?, channels: String?): String

    // —— 睡眠定时状态 ——
    val sleepTimer: String
    fun sleepTimerAfterTrack(): String
    fun sleepTimerRemaining(minutes: Long): String

    // —— 播放页控件 ——
    val collapse: String
    val translation: String
    val queue: String
    val repeat: String
    val shuffle: String
    val like: String
    val unlike: String
    fun sleepRemainingLabel(remaining: String): String
    val sleepEndsWithTrack: String

    // —— 队列底栏 ——
    fun queueCount(size: Int): String
    val queueUpNext: String
    val queueSaved: String
    val queueSaveFailed: String
    val queueClearTitle: String
    fun queueClearMessage(size: Int): String
    val queueEmptyLabel: String
    val queueReorderHint: String
    val coverContentDescription: String

    // —— 播放控制 ——
    val previousTrack: String
    val nextTrack: String
    fun playOrPause(playing: Boolean): String

    // —— 评论 ——
    val commentsLoadFailed: String
    val retry: String
    val noComments: String
}

/**
 * 音质档位名。
 *
 * 抽成独立分组而不是塞进 `playback`：`AppModel.qualityOptions`（设置页下拉）与
 * `SongCacheModel.qualityLabel`（缓存明细行）**用的是同一套档位词**，但后者还要
 * 覆盖 `jymaster` / `sky` 等不在下拉里的档位。放一起才能保证两处用词一致。
 *
 * @param level 音源侧的档位标识（**不是**文案，别当 key 改）
 */
interface QualityStrings {
    fun labelOf(level: String): String
}


// ---------------------------------------------------------------------------
// 媒体库与歌单
// ---------------------------------------------------------------------------

interface LibraryStrings {
    // —— 歌单动作 ——
    val playAll: String
    val addToQueue: String
    val downloadAll: String
    val sharePlaylist: String
    val favoritePlaylist: String
    val unfavoritePlaylist: String
    val deletePlaylist: String
    val removeFromPlaylist: String
    val removeFromCloud: String
    val removeSelected: String

    fun removeSelectedMessage(name: String, count: Int): String
    fun deletePlaylistMessage(name: String): String
    fun unfavoritePlaylistMessage(name: String): String

    val creatorOnlyHint: String
    val favorited: String
    val unfavorited: String
    val queuedToPlay: String
    val playNext: String
    val operationFailed: String
    val importFromPlaylist: String

    // —— 歌单详情 ——
    val songDetails: String
    val paidType: String
    val paidFree: String
    val paidPurchased: String
    val paidLowQualityFree: String
    val paidUnknown: String
    val publishDate: String
    val commentCount: String
    val maxBitrate: String
    val releaseDateFormat: String

    fun shareTrack(name: String, artist: String, album: String, duration: String, extra: String): String
    fun songShareLine(label: String, value: String): String

    val sourceNowPlaying: String
    val loadTrackInfoFailed: String

    // —— 多选 ——
    fun selectedCount(count: Int): String
    val exitSelection: String
    val selectAll: String

    // —— 排序 ——
    val sortDefault: String
    val sortByName: String
    val sortByArtist: String
    val sort: String
    val moreOptions: String
    fun trackCountLabel(count: Int, duration: String): String
    fun playlistCountLabel(count: Int, duration: String): String

    // —— 列表状态 ——
    val playlistEmpty: String
    val loadFailedRetry: String
    val retry: String

    // —— 媒体库页 ——
    val tabPlaylists: String
    val tabDownloads: String
    val myMusic: String
    fun greeting(name: String): String
    fun librarySubtitle(playlists: Int, liked: Int): String
    fun countPlaylist(n: String): Pair<String, String>
    fun countLiked(n: String): Pair<String, String>
    fun countDownload(n: String): Pair<String, String>
    val libraryTitle: String
    fun libraryPlaylistsCount(n: Int): String
    val libraryOffline: String
    val newPlaylist: String
    val insights: String
    val createPlaylist: String
    val createPlaylistNote: String
    val recentPlays: String
    val recentPlaysNote: String
    val offlineDownloads: String
    val offlineDownloadsNote: String
    val cloudDrive: String
    val cloudDriveNote: String
    val storageManage: String
    val storageManageNote: String
    val aboutCpPlayer: String
    val preferences: String
    val appearance: String
    val playback: String
    val providers: String
    val syncingLibrary: String
    val syncingLibraryNote: String
    val libraryLoadFailed: String
    val noPlaylists: String
    val noPlaylistsNote: String
    val myPlaylists: String
    fun myPlaylistsCount(n: Int): String
    val seeAll: String
    val downloading: String
    val completed: String
    val localMedia: String
    val noDownloads: String
    val openDownloads: String

    // —— 歌单卡片 / 歌曲行 ——
    val play: String
    val shuffle: String
    val add: String
}


// ---------------------------------------------------------------------------
// 专辑详情
// ---------------------------------------------------------------------------

interface AlbumStrings {
    val fallbackTitle: String
    val loadFailed: String
    val loading: String
    val loadingNote: String
    val notOpened: String
    val notOpenedNote: String
    val tracks: String
    val noPlayableTracks: String
    val emptyTracks: String
    val emptyTracksNote: String
    fun trackCount(n: Int): String
    fun releasedYear(year: String): String
    fun albumMeta(trackCount: Int, duration: String): String
    val retry: String
    val shufflePlay: String
    val liked: String
    val unliked: String
}

// ---------------------------------------------------------------------------
// 歌单选择 / 新建
// ---------------------------------------------------------------------------

interface PlaylistSheetStrings {
    val noPlaylists: String
    val addTracksTitle: String
    val importFrom: String
    val addFromQueue: String
    val selectTracks: String
    fun fromSource(name: String): String
    val deselectAll: String
    val selectAll: String
    val noTracksFound: String
    fun addCount(count: Int): String
    fun trackCount(n: Int): String
    val playlistFallbackTitle: String

    val addToPlaylistTitle: String
    fun addedTracks(count: Int): String
    val addToPlaylistFailed: String
    val createPlaylistFailed: String
    val newPlaylistNamePlaceholder: String
    val create: String
    val createShort: String
    val creating: String
    val noPlaylistsHint: String

    val createDialogTitle: String
    val playlistNamePlaceholder: String
    fun created(name: String): String
    val cancel: String
}

// ---------------------------------------------------------------------------
// 组合侧接线
// ---------------------------------------------------------------------------

/**
 * 当前文案。
 *
 * 默认值给中文是为了让**未接Provide的场景**（预览、单测直接调某个 composable）
 * 也能渲染，而不是抛「composition local 未赋值」—— 那种报错离真正的原因太远。
 */
val LocalCpStrings = staticCompositionLocalOf { CpStrings.zh }

/** 在组合里读文案（重组驱动：语言一变，读取点自动重组合）。 */
@Composable
@ReadOnlyComposable
fun cpStrings(): CpStrings = LocalCpStrings.current

/**
 * 把语言注入整棵树。**全树只调用一次**（在 `AppTheme` 里）。
 *
 * 语言变化 => `strings` 变 => CompositionLocal 读取点失效 => 所有读过的地方重组合，
 * **不需要重启**。做不到即时更新的只有那些在组合外就把文案固化进状态的调用点，
 * 那种地方要存 lambda / key，别存现成的字符串。
 */
@Composable
fun ProvideCpStrings(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val strings = remember(language) { CpStrings.of(language) }
    CompositionLocalProvider(
        LocalCpStrings provides strings,
        content = content,
    )
}
