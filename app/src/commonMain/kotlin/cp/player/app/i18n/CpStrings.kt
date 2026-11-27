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

    /** 主导航：底部 tab、桌面侧栏、窗口 chrome 上的全局入口。 */
    val navigation: NavigationStrings

    /** 私信新消息通知 + 桌面托盘常驻。 */
    val messageNotify: MessageNotifyStrings

    val appearance: AppearanceStrings

    /** 字库（中日文字体包：桌面端内置，Android 下载）。 */
    val fontPacks: FontPackStrings

    val storage: StorageStrings
    val songCache: SongCacheStrings
    val playback: PlaybackStrings

    /**
     * 音效（PEQ / 混音 / 音量均衡）。
     *
     * 与 [playback] 分开而不是并进「播放与音质」：音效是独立的一页、
     * 独立的功能块，将来播放页的快捷入口也读同一组（理由见 [AudioEffectStrings]）。
     */
    val audioEffect: AudioEffectStrings

    /** 音频输出内核与硬件独占（`AudioEngineSettingsScreen`）。 */
    val engine: EngineStrings

    val shortcuts: ShortcutStrings
    val quality: QualityStrings

    val externalAccess: ExternalAccessStrings
    val standby: StandbyStrings

    /**
     * 歌词投放（词幕 / SuperLyric / 超级岛 / 锁屏岛 / 状态栏歌词 …）。
     *
     * 与 [lyricsPlugin] 分开：那一组管「**从哪里取**歌词」，这一组管「取到的歌词**投到哪里**」。
     * 两者唯一的交集是「歌词文本」本身，其余（渠道、开关、外观）毫无关系。
     */
    val lyricPush: LyricPushStrings

    /** 听歌打卡与外部平台同步（Last.fm 等）。 */
    val scrobble: ScrobbleStrings

    /**
     * 播放相关：播放页、播放控制、队列底栏、「更多」弹层。
     *
     * 合在一个分组而不是按文件拆：这些标签大量**互相引用**（「上一首」在
     * `PlaybackControls` 与 `PlayerScreen` 各出现一次，「分享」在更多面板与队列
     * 底栏各一次），拆开就会出现同一条文案两种译法。
     */
    val player: PlayerStrings

    /** 视频播放页（`VideoPlayerView`）。 */
    val video: VideoStrings

    /** 媒体库 / 歌单：列表页、详情页、上下文菜单、多选工具条。 */
    val library: LibraryStrings
    val album: AlbumStrings
    val playlistSheet: PlaylistSheetStrings

    /** 外部歌单导入（链接 / 文本解析 → 新建或追加歌单）。 */
    val playlistImport: PlaylistImportStrings

    /** 账号与登录、用户/歌手主页、云盘。 */
    val account: AccountStrings

    /** 关于与支持。 */
    val about: AboutStrings

    /** 软件更新弹窗。 */
    val update: AppUpdateStrings

    /** 下载管理。 */
    val downloads: DownloadStrings

    /** 首页（`HomeScreen` / `HomeScreenModel`）。 */
    val home: HomeStrings

    /** 音乐洞察、听歌诊断、关于、听歌日历。 */
    val insights: InsightStrings

    /** 诊断与健康页（`HealthScreen`）。 */
    val health: HealthStrings

    /** 一起听、私信、消息、评论。 */
    val social: SocialStrings

    /**
     * 歌词源插件（Lyrico Plugin API 兼容）。
     *
     * 单独成组：它由独立的插件管理页消费，且将来播放页的「无歌词 → 换源」也读同一组。
     */
    val lyricsPlugin: LyricsPluginStrings
    val pluginRepository: PluginRepositoryStrings

    /** AI 接入与 A2UI 生成式界面（设置页 + 播放器私人 DJ 台）。 */
    val ai: AiStrings

    /** 首次使用引导（欢迎 / 音源 / 登录 / 完成）。 */
    val onboarding: OnboardingStrings

    /** 音源模块管理（导入 / 切换 / 更新 / 导出 / 删除）。 */
    val providerManagement: ProviderManagementStrings

    /** 歌曲 / 歌单「更多」弹层（跨十几个入口复用）。 */
    val contextMenu: ContextMenuStrings

    /**
     * 专辑墙模式（`ui/wall`）。
     *
     * 单独成组：墙是**跨数据源的模式**（专辑 / 歌单 / 艺人 都能上墙），
     * 不属于"媒体库"这一页；将来歌手页、搜索结果的「用墙看」也读同一组。
     */
    val wall: WallStrings

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
    val isolationTitle: String
    val isolationIntro: String
    val isolationSectionAccounts: String
    val isolationRefreshProfile: String
    val isolationRefreshProfileNote: String
    val isolationCurrentAccounts: String
    val isolationUnknownProvider: String
    val isolationSaved: String
    val isolationNotSignedIn: String
    fun isolationAccountCount(count: Int): String
    val isolationSwitchProvider: String
    val isolationSwitchProviderNote: String
    val isolationSectionCache: String
    val isolationStorage: String
    val isolationStorageNote: String
    val isolationSectionDanger: String
    val isolationClearConfirm: String
    val isolationClearSession: String
    val isolationClearNote: String
    fun isolationProfileNote(name: String): String
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
    val itemExternalAccess: CpTextPair
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
    val save: String
    val apply: String
    val refresh: String
    val unsavedBlocked: String
    val unsavedHint: String
    val back: String
    val close: String
    val search: String
    val clearSearch: String
    val initializingBackend: String
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

    /** 桌面托盘菜单：切换迷你播放器（悬浮小窗）。 */
    val trayMiniMode: String

    /** 桌面托盘菜单：当前没有正在播放的内容（信息区的占位文案）。 */
    val trayNothingPlaying: String

    /** 桌面托盘菜单：播放控制的无障碍描述。 */
    val trayPlay: String
    val trayPause: String
    val trayPrevious: String
    val trayNext: String

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
// 界面设置
// ---------------------------------------------------------------------------

interface AppearanceStrings {
    val screenTitle: String
    val sectionLook: String
    val sectionMotion: String
    val sectionContent: String
    val sectionMobile: String
    val sectionFont: String

    val themeMode: String
    val themeModeSystem: String
    val themeModeLight: String
    val themeModeDark: String
    val themeModeSystemNote: String
    val themeModeLightNote: String
    val themeModeDarkNote: String
    val sectionDarkEnhancements: String
    val pureBlackDetail: String

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
    val predictiveBack: String
    val predictiveBackNote: String
    val predictiveBackUnsupportedNote: String
    val coverFlight: String
    val coverFlightNote: String

    val fluidBackground: String
    val fluidBackgroundNote: String
    val fluidBackgroundDarkOverlay: String
    val fluidBackgroundDarkOverlayNote: String
    val fluidBackgroundFps: String
    val fluidBackgroundFpsNote: String
    val desktopPlayerControls: String
    val desktopPlayerVolume: String
    val desktopPlayerVolumeNote: String

    val showSongAlbum: String
    val showSongAlbumNote: String

    val songSwipeActions: String
    val songSwipeActionsNote: String

    val fontRoundness: String
    val fontRoundnessRounded: String
    val fontRoundnessSquare: String

    /** 主题摘要：模式 + 纯黑标记 + 取色方式。 */
    fun themeSummary(mode: String, color: String, pureBlack: Boolean): String

    val playerStyle: String
    val playerStyleNote: String
    val playerStyleThreePane: String
    val playerStyleStandard: String
    val playerStyleThreePaneNote: String
    val playerStyleStandardNote: String
    val mobilePreferences: String
    val mobilePreferencesNote: String
    val playerSettingsTitle: String
    val playerSettingsHeroNote: String
    val playerLyricsPresentation: String
    val playerLyricsTranslation: String
    val playerLyricsTranslationNote: String
    val playerLyricsImmersive: String
    val playerLyricsImmersiveToggle: String
    val playerLyricsImmersiveNote: String
    val playerLyricsImmersiveTimeout: String
    val playerLyricsImmersiveTimeoutNote: String
    fun playerLyricsImmersiveSeconds(seconds: Int): String
    val playerResetSection: String
    val playerReset: String
    val playerResetNote: String
    val sectionPreview: String
    val mobilePreview: String
    val mobilePreviewOn: String
    val mobilePreviewOff: String
}

// ---------------------------------------------------------------------------
// 字库（中日文字体包）
// ---------------------------------------------------------------------------

/**
 * 「字库」设置区的文案。
 *
 * 三款字体的**身份**不在这里（那是 `AppFontFace` 的 `label` / `covers`，
 * 属于技术声明，不做翻译）。这里只放用户可见的动作与状态。
 */
interface FontPackStrings {
    val sectionTitle: String

    /** 整段说明：桌面端内置 / Android 下载 / 单独开关。 */
    val intro: String

    /** 平台不支持时的说明（Android 7 及以下没有字体可变轴）。 */
    val unsupported: String

    val googleSansTitle: String
    val googleSansNote: String
    val japaneseTitle: String
    val japaneseNote: String
    val misansTitle: String
    val misansNote: String

    val stateInstalled: String
    val stateMissing: String

    /** @param percent 0–100 */
    fun stateDownloading(percent: Int): String

    /** @param reason 原始错误信息（诊断用，不翻译） */
    fun stateFailed(reason: String): String

    val actionDownload: String
    val actionRetry: String
    val actionDelete: String

    /** 删除前的二次确认。 */
    val deleteConfirm: String
    val cancel: String

    /** 下载来源说明（GitHub Release）。 */
    val sourceNote: String
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
    val customCapacity: String
    val customCapacityDialogTitle: String
    val customCapacityDialogNote: String
    fun customCapacityPlaceholderGb(): String
    fun customCapacityPlaceholderMb(): String
    fun customCapacityPreview(bytes: String): String
    val customCapacityInvalid: String
    val customCapacityUnitGb: String
    val customCapacityUnitMb: String
    fun customCapacitySubtitle(capacity: String): String
    fun customCapacityCurrent(capacity: String): String
    val cacheDir: String
    val migrateCacheDir: String
    val migrateCacheDirNote: String
    val migrateCacheDirConfirmTitle: String
    fun migrateCacheDirConfirmMessage(target: String): String
    val confirmMigrate: String
    val cacheDirMigrating: String
    val clearStaleCache: String
    val clearStaleCacheNote: String
    val clearSongCache: String
    val clearSongCacheNote: String
    val clearSongCacheConfirmTitle: String
    fun clearSongCacheConfirmMessage(count: Int, bytes: String): String
    val openCacheDir: String
    val openCacheDirNote: String
    val openCacheDirFailed: String

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

    // ---- 缓存 → 下载（不联网，直接搬运已落盘的完整副本）----

    /** 一键把全部可转条目转为下载。 */
    val convertAll: String

    /** 转换进行中（按钮禁用时的文案）。 */
    val converting: String

    /** 一键转换按钮下方的说明：讲清「不重新下载」与「哪些会被跳过」。 */
    val convertAllNote: String

    /** 单条转换按钮的无障碍描述（读屏念的是「把 X 的缓存转为下载」）。 */
    fun convertEntry(name: String): String

    /** 转换结果：成功 [count] 条，跳过 [skipped] 条（跳过为 0 时不要拼出「跳过 0 首」）。 */
    fun converted(count: Int, skipped: Int): String

    /** 当前一个能转的都没有（全是缺音源/音质信息的旧缓存，或列表已空）。 */
    val nothingToConvert: String

    /** 转换整体失败（本轮一条都没成功且不是「跳过」导致）。 */
    val convertFailed: String

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

    // —— 音频输出内核 / 输出模式 ——
    val sectionAudioEngineAndroid: String
    val sectionAudioOutputDesktop: String
    val engineMedia3: String
    val engineFlick: String
    val outputShared: String
    val outputExclusive: String
    val activeKernel: String
    val wasapiMode: String
    val kernelVipInactive: String
    val kernelFlickActive: String
    val kernelMedia3: String
    val kernelExclusiveActive: String
    val kernelShared: String
    val engineDetailAndroid: String
    val engineDetailDesktop: String
    val engineDetailAndroidNote: String
    val engineDetailDesktopNote: String
    val engineDetailDesktopUnsupportedNote: String
    val featureDetails: String
    val vipManagement: String
    fun vipActivated(plan: String): String
    fun vipActivatedWithKey(plan: String, key: String): String
    val vipInactiveNote: String
    val vipDialogTitle: String
    val vipDialogNote: String
    val vipKeyLabel: String
    val vipKeyPlaceholder: String
    val vipActivateFailed: String
    val vipActivateNow: String
    val vipActivateLater: String
    val changeLicenseKey: String
    val activatePrivilege: String
    val dspSection: String
    val dspNote: String
    val mixer: String
    val edit: String
    val set: String

    val sectionLyrics: String
    val lyricsSource: String
    val lyricsSourceNote: String
    val lyricsProviderOnly: String
    val lyricsAmllFirst: String
    val lyricsAmllOnly: String
    val showMiniPlayerLyrics: String get() = "Lyrics in mini player"
    val showMiniPlayerLyricsSubtitle: String get() = "Show the current lyric in the bottom mini player; turn off to show the artist"
    val showRubyLyrics: String get() = "Japanese Furigana"
    val showRubyLyricsSubtitle: String get() = "Show furigana above kanji in Japanese lyrics"
    val lyricsWordSegmentation: String get() = "Lyric Word Segmentation"
    val lyricsWordSegmentationSubtitle: String get() = "Wrap and segment lyrics by language word boundaries (e.g. Japanese)"
    val smartKaraokeLrc: String get() = "Smart Karaoke for LRC (Beta)"
    val smartKaraokeLrcSubtitle: String get() = "Synthesize syllable-level highlight using beat grid and language weights when karaoke data is unavailable"

    val sectionSleepTimer: String
    val sleepTimer: String
    val sleepAfterTrack: String
    fun sleepRemaining(minutes: Long): String
    val sleepOff: String

    val sectionAutoPlay: String
    val autoPlayMode: String
    val autoPlayModeNote: String
    val autoPlayModeOff: String
    val autoPlayModeContinue: String
    val autoPlayModeDaily: String
    val autoPlayModeCustom: String
    val autoPlayCustomTarget: String
    val autoPlayCustomTargetEmpty: String
    val autoPlayCustomDialogTitle: String
    val autoPlayPickFromPlaylists: String
    val autoPlayUseCurrentSong: String
    val autoPlayUseCurrentPlaylist: String
    val autoPlayManualInput: String
    val autoPlayTypePlaylist: String
    val autoPlayTypeTrack: String
    val autoPlayInputIdPlaceholder: String
    val autoPlayInputTitlePlaceholder: String
    fun autoPlayPlaylistLabel(id: String): String
    val autoPlayClearTarget: String

    val sectionLastPlayback: String
    val keepLastPlayback: String
    val keepLastPlaybackNote: String

    val sectionPreload: String
    val preloadNext: String
    val preloadNextNote: String

    // ---- 淡入淡出 ----
    //
    // 注意这一组属于**播放设置页**（不是音效页）：淡入淡出只改音量，
    // 桌面端同样可用，所以不能和「仅 Android」的 PEQ 混在一页。
    val sectionFade: String
    /** 总开关的标题：分组标题已经叫「淡入淡出」，行内不再重复。 */
    val fadeToggle: String
    /** 单一开关的说明：同时控制淡入与淡出。 */
    val fadeNote: String
    val fadeIn: String
    val fadeInNote: String
    val fadeOut: String
    val fadeOutNote: String
    val fadeDuration: String
    val fadeDurationNote: String
    /** 时长滑杆的当前值标签（如「3.0 秒」）。含数字 ⇒ 必须是函数（语序不同）。 */
    fun fadeDurationSeconds(seconds: String): String

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

    val timerCustomChip: String
    val timerCustomDialogTitle: String
    val timerCustomModeDuration: String
    val timerCustomModeAtTime: String
    fun timerCustomDurationHint(minutes: Int, endTimeText: String): String
    fun timerCustomAtTimeHint(targetTimeText: String, diffMinutes: Long): String
    val timerCustomConfirm: String
    val timerCustomToggleDial: String
    val timerCustomToggleInput: String
}

// ---------------------------------------------------------------------------
// 音效
// ---------------------------------------------------------------------------

/**
 * 音效设置页（PEQ / 混音 / 音量均衡）。
 *
 * ### 为什么独立成组而不是并进 `playback`
 *
 * `playback` 已经承载了音质 / 歌词 / 睡眠定时 / 上次播放/ 后台保活五组，
 * 而音效自己就是一个完整的功能块（三种效果 × 各自的开关 / 参数 / 说明）。
 * 并进去会让那个接口继续膨胀，且「音效」的文案将来还会被播放页的快捷入口读到
 * （与 `player` 组的关系，和 `timerDialogTitle` 被两个页面共用是同一情形）。
 *
 * ### 关于「不支持」的文案
 *
 * 桌面端底层没有音效能力（见 `PlatformPlayer.audioEffectCapabilities` 的 KDoc），
 * 因此需要 [unsupportedNote] 说明原因 —— 这比给一排无效滑杆诚实得多。
 * ⚠️ 该条**不得**描述成「暂时不支持」：那是承诺一个没有排期的事。
 * 措辞就事论事地讲清「这个平台的音频引擎没有提供效果接口」即可。
 */
interface AudioEffectStrings {
    val screenTitle: String

    /**
     * 设置根列表里这一项的副标题。
     *
     * 与 [unsupportedNote] 分开：那一条是页内说明（讲清为什么不能用），
     * 这一条只是一句话概括这页干什么。根列表不该出现「本平台不支持」这种
     * 让用户先踩空的提示 —— 副标题的职责是「值不值得点进去」。
     */
    val entrySubtitle: String
    val unsupportedNote: String
    val kernelStatusSection: String
    val kernelNotReadyTitle: String
    val kernelNotReadyNote: String
    val viewStatus: String
    val equalizerEnabledSummary: String
    val resetFlat: String
    val advancedFrequencyTuning: String
    val advancedFrequencyTuningNote: String
    fun bandCenterFrequency(index: Int): String

    // ---- PEQ ----
    val sectionEqualizer: String
    val equalizerEnabled: String
    val equalizerEnabledNote: String
    val equalizerPreset: String
    val equalizerPresetNote: String
    val presetFlat: String
    val presetPop: String
    val presetRock: String
    val presetVocal: String
    val presetBassBoost: String
    val presetTrebleBoost: String
    val presetCustom: String

    /** 第 [index] 段的标题（1-based，如「第 1 段」）。 */
    fun bandTitle(index: Int): String

    /** 第 [index] 段的副标题：当前频率与增益，如「60 Hz · +3.0 dB」。 */
    fun bandSubtitle(index: Int, hz: String, gainDb: String): String

    /**
     * 某一段的**频率**标签，如「频率」。
     *
     * 单独一条而不是复用：频点滑杆与增益滑杆在不同段上标题相同、
     * 只有这一段不同，抽出来才能保证两处措辞一致。
     */
    val bandFrequency: String

    /** 某一段的**增益**标签。 */
    val bandGain: String

    /** 增益读数，如「+3.0 dB」。 */
    fun gainLabel(gainDb: String): String

    // ⚠️ 刻意**没有**「频率读数」这一条：`Hz` / `kHz` 是国际单位符号，
    // 中英文写法完全一致（`60 Hz` / `3.6 kHz`），翻译它反而不专业。
    // 频率读数在 `AudioEffectSettingsScreen.formatHz` 里生成，
    // 单位随数量级自动选择 —— 与增益不同（增益的 `dB` 也一样通用，
    // 但增益有「正负号」这个需要按语言习惯决定要不要省略的点，故保留文案函数）。

    // ---- 混音 ----
    val sectionMixer: String
    val balance: String
    val balanceNote: String
    val balanceCenter: String
    val balanceLeft: String
    val balanceRight: String

    // ---- 音量均衡 ----
    val sectionLeveling: String
    val levelingEnabled: String
    val levelingEnabledNote: String
    val levelingTarget: String
    val levelingTargetNote: String
    val preventClipping: String
    val preventClippingNote: String

    /** 页尾说明：讲清这一层与用户音量的关系（避免误认为两个滑杆重复）。 */
    val levelingNote: String
}

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
// 外部访问
// ---------------------------------------------------------------------------

/**
 * 「外部访问」—— CPPlayer 与本机之外的软件打交道的两面，合成**一页**：
 *
 * 1. **服务方（inbound）** —— 本机开 HTTP 服务，第三方来拉流 / 读数据（[sectionService]）；
 * 2. **推送方（outbound）** —— CPPlayer 主动把曲目与队列推给接收端（[sectionPush]）。
 *
 * ### 为什么两个方向会放在同一页
 * 它们共用**同一份** `LocalServerConfig`：端口、绑定范围、令牌既是「别人怎么连我」
 * 的参数，也是「我推给别人的地址里写着什么」的参数。拆成两页时出现了跨页依赖 ——
 * 「允许第三方读取音源数据」在推送页，而它赖以为生的服务总开关在另一页，
 * 只能在那儿写一句「请先到『本地流输出』页开启」。合成一页后由**一个总开关**管整件事，
 * 页内不再有跨页指引。
 */
interface ExternalAccessStrings {
    val screenTitle: String

    // ---- 对外服务 ----
    val sectionService: String
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

    val statusDisabled: String
    fun statusStartFailed(error: String): String
    fun statusRunning(url: String): String
    val statusStarting: String

    // ---- 端口 ----
    val sectionPort: String
    val port: String
    val portNote: String
    val portRestartNote: String
    fun portEmpty(): String
    fun portNotNumber(): String
    fun portOutOfRange(from: Int, to: Int): String

    // ---- 访问令牌（媒体面与数据面共用同一个令牌） ----
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

    // ---- 允许第三方访问 ----
    val sectionThirdParty: String
    val allowStream: String
    val allowStreamNote: String
    val allowApi: String
    val allowApiNote: String
    val apiSwitchNote: String

    // ---- 推送到接收端 ----
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

    // ---- 接口地址 ----
    val sectionEndpoint: String
    val endpointNote: String
    val configFileNote: String

    /** 总开关关闭时的提示。**本页内**就能解决（顶部开关），不再指向另一页。 */
    val serviceDisabledNote: String

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
    val shareUnavailableLocal: String
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

    // —— 歌词状态（播放页歌词区的空/加载/错误态） ——
    val lyricsLoading: String
    val lyricsEmpty: String
    val lyricsIdle: String
    /** @param message 失败原因（来自异常，可能本身就是英文/技术串） */
    fun lyricsError(message: String): String

    // —— 音源能力标签（音源管理页）——
    // 映射 `ProviderCapability` 里的字符串常量。**必须按 key 查而不是按顺序取**：
    // 能力列表来自外部 manifest，宿主不得假设顺序；用错位下标会让标签张冠李戴。
    fun capabilityLabel(key: String): String?

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
    /** 本地曲目的「歌曲信息」：文件体积。 */
    val infoSize: String
    /** 本地曲目的「歌曲信息」：文件路径。 */
    val infoPath: String
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
    val nowPlaying: String
    val collapse: String
    val lyricsCollapse: String
    val lyricsExpand: String
    val immersive: String
    val exitImmersive: String
    val songComments: String
    val audioOutputDevice: String
    val translation: String
    val queue: String
    val repeat: String
    val shuffle: String
    val like: String
    val unlike: String

    /**
     * 音量控件（桌面迷你播放器的滑杆 / 静音按钮）。
     *
     * 与 [cp.player.app.i18n.PlayerStrings.quality] 一样属于「控件自身的名字」，
     * 不是某首歌的属性 —— 所以放在 `player` 而不是 `playback`（后者是设置页的分组）。
     */
    val volume: String

    /** 音量弹层里那颗静音键（点一下把音量归零）。 */
    val mute: String

    /** 已静音时那颗键的语义：点一下恢复静音前的音量。 */
    val unmute: String

    /** 音质档位选择器（迷你播放器的音质 chip、「更多」弹层的音质卡片）。 */
    val quality: String

    /** 音质卡片的说明：改的是全局设置、对后续加载的曲目生效。 */
    val qualityGlobalNote: String
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
    /** 播放队列「一首歌都没有」时的占位文案（区别于搜索无结果）。 */
    val queueEmptyHint: String
    /** 队列弹层内搜索无命中时的占位文案。 */
    val queueNoMatchHint: String
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

    // —— 歌词时间轴微调 ——
    val lyricOffset: String
    val lyricOffsetTitle: String
    fun lyricOffsetSubtitle(ms: Long): String
    fun lyricOffsetBadge(ms: Long): String = "偏移 " + (if (ms > 0) "+" else "") + "${ms / 1000f}s"
    val lyricOffsetAdvance: String
    val lyricOffsetDelay: String
    val lyricOffsetReset: String
    val lyricOffsetHint: String

    // —— 「更多」弹层（播放页） ——
    val exploreSimilar: String
    val exploreSimilarNote: String
    val immersiveLyricsOn: String
    val immersiveLyricsOff: String
    val playerSettings: String
    fun lyricOffsetChipValue(label: String, seconds: String): String

    // —— 相似歌曲 ——
    val similarSongs: String
    fun similarCount(count: Int): String
    val similarEmptyNote: String
    val similarLoadFailed: String
    val similarUnsupported: String
    val similarUnsupportedNote: String
    val similarNothingPlaying: String
    val similarNothingPlayingNote: String
    val similarNoMatch: String
    val similarNoMatchNote: String

    // —— 桌面迷你模式 ——
    val miniMode: String
    val miniModeRestore: String
    val miniModePin: String
    val miniModeUnpin: String

    // —— 队列搜索 ——
    val searchQueue: String

    // —— 播放页右键菜单 ——
    //
    // 与 `contextMenu` 那组分开：那一组是「歌曲/歌单更多弹层」的菜单项，
    // 这一组是**播放页整页右键**（走带控制 + 视图切换 + 收起），语义与作用域都不同。
    val favoriteTrack: String
    val unfavoriteTrack: String
    val repeatOne: String
    val repeatAll: String
    val repeatOff: String
    val showLyrics: String
    val showQueue: String
    val showComments: String
    val playMainView: String
    val backToPlayer: String
    val showLyricsTranslation: String
    val hideLyricsTranslation: String
    val lyricsPluginSettings: String
    val playMv: String
    val addToPlaylistMenu: String
    val songDetailsMenu: String
    val transferDeviceMenu: String
    val sleepTimerMenu: String
    val copyTrackInfo: String
    val switchToStandardPlayer: String
    val switchToThreePanePlayer: String
    val collapsePlayer: String

    // —— 播放页页签与顶栏控件 ——
    val tabLyrics: String
    val tabSong: String
    val tabQueue: String
    val tabComments: String

    /** 页签的无障碍点击说明（`onClickLabel`），如「切换到歌词」。 */
    fun switchToTab(tab: String): String
    val lyricsTranslation: String
    val closeLyricsTranslation: String
    val miniPlayer: String
    val restoreVolume: String
    val maxVolume: String

    /** 音源不支持评论时，评论页签里的占位说明。 */
    val commentsUnsupported: String

    /** 桌面播放页的引擎徽标：WASAPI 独占已生效。 */
    val exclusiveBadge: String
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
    fun fullLabelOf(level: String): String = labelOf(level)
}


// ---------------------------------------------------------------------------
// 媒体库与歌单
// ---------------------------------------------------------------------------

interface LibraryStrings {
    // —— 歌单动作 ——
    val playAll: String
    val addToQueue: String
    val downloadAll: String

    /**
     * 「下载全部」的二次确认正文。
     *
     * 一次点击会给整张歌单排队几百个下载任务，误触的代价是流量与磁盘；
     * 但它是可恢复的（任务可取消、歌可重下）⇒ 确认键不走 error 色。
     */
    fun downloadAllMessage(name: String, count: Int): String
    val sharePlaylist: String
    val favoritePlaylist: String
    val unfavoritePlaylist: String
    val deletePlaylist: String
    val removeFromPlaylist: String
    val removeFromCloud: String
    val removeSelected: String

    fun removeTrackMessage(playlistName: String, trackName: String): String
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
    val deselectAll: String

    // —— 排序 ——
    val sortDefault: String
    val sortByName: String
    val sortByArtist: String
    val sort: String
    val moreOptions: String
    fun trackCountLabel(count: Int, duration: String): String
    fun playlistCountLabel(count: Int, duration: String): String

    // —— 歌单详情动作反馈（ScreenModel）——
    fun playlistRefreshed(): String
    val playlistDetailRefreshFailed: String
    val playlistDetailLoadFailed: String
    val loadMoreFailed: String
    fun playlistDeleted(name: String): String
    fun playlistUnsubscribed(name: String): String
    val ownerOnlyAddTracks: String
    val tracksAlreadyInPlaylist: String
    fun tracksAdded(count: Int): String
    val addTracksFailed: String
    fun tracksRemoved(count: Int): String
    val playlistFavorited: String
    val playlistUnfavorited: String
    fun emptyPlaylist(name: String): String
    fun playlistQueued(name: String): String
    val localLibraryEmpty: String
    val personalFmUnavailable: String
    fun playlistSourceLabel(key: String): String
    fun newSongRegionLabel(key: String): String

    // —— 列表状态 ——
    val playlistEmpty: String
    val loadFailedRetry: String
    val retry: String

    // —— 媒体库页 ——
    val tabPlaylists: String
    val tabDownloads: String
    val myMusic: String
    val gridView: String
    val listView: String
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

    // —— 曲库检索 ——
    val searchLibrary: String
    val searchLibraryHint: String
    val searchLibraryEmpty: String
    val searchLibraryEmptyNote: String
    val searchPlaylistSection: String
    val searchPlaylistTracksSection: String
    val searchPlaylistTracksLoading: String
    val searchLocalSection: String
    fun searchOnlineHint(query: String): String
    val noSearchResults: String
    val importPlaylist: String

    /** 标题行按钮上的短标签（「导入外部歌单」在窄屏放不下）。 */
    val importShort: String

    // —— 歌单管理 ——
    val renamePlaylist: String
    val renamePlaylistPlaceholder: String
    fun renamedPlaylist(name: String): String
    val renamePlaylistFailed: String

    // —— 歌单卡片 / 歌曲行 ——
    val play: String
    val shuffle: String
    val add: String

    // —— 曲目列表壳（TrackListShell）——
    val searchFieldHint: String
    val trackSearchHint: String
    val trackSearchClear: String
    val trackSearchClose: String
    fun trackSearchResultCount(count: Int): String

    /** 曲目壳的「添加」次级动作（不是新建歌单）。 */
    val trackAdd: String
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
    val searchAlbum: String

    /** 专辑详情头部的收藏开关（未收藏态）。 */
    val favoriteAlbum: String

    /** 专辑详情头部的收藏开关（已收藏态）。 */
    val favoriteAlbumActive: String

    /** 收藏 / 取消收藏成功提示。 */
    fun favoriteAlbumDone(favorited: Boolean): String

    /** 取消收藏的二次确认正文。**可恢复**操作，不是不可逆删除。 */
    fun unfavoriteAlbumMessage(name: String): String

    /**
     * 取消收藏的确认框标题 / 确认键文案。
     *
     * **不能复用 [favoriteAlbumActive]**（那是「已收藏专辑」状态标签）：确认框问的是
     * 「要不要取消收藏」，用状态文案当动词会让用户以为点下去是「收藏」。
     */
    val unfavoriteAlbumAction: String

    /**
     * 「未登录无法收藏」提示。
     *
     * 上游 `album/sub` 是账号动作，未登录时只会返回失败 —— 先拦住并说清原因，
     * 比让用户点了等一个「操作失败」更有用。
     */
    val favoriteAlbumNeedLogin: String

    /** 收藏 / 取消收藏失败的提示。 */
    val favoriteAlbumFailed: String
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

    val addToLocalPlaylistTitle: String
    val newLocalPlaylistPlaceholder: String
    val noCustomLocalPlaylists: String
    fun createdAndAddedTo(name: String): String
    fun addedToPlaylist(count: Int, name: String): String
    fun alreadyInPlaylist(name: String): String

    val saveDailyToPlaylist: String
    val saveDailyToPlaylistTitle: String
    val saveDailyToPlaylistConfirm: String
    val saveDailyToPlaylistConverted: String
    fun saveDailyToPlaylistNote(count: Int): String
    fun saveDailyToPlaylistSuccess(name: String, count: Int): String
    val saveDailyToPlaylistAlreadySaved: String
    val saveDailyToPlaylistFailed: String
    fun saveDailyToPlaylistPartial(name: String): String
    fun dailyRecommendationPlaylistName(date: String): String
    val artistFallback: String
}

interface AboutStrings {
    val screenTitle: String
    val appSubtitle: String
    val changelog: String
    val versionSection: String
    val commitSha: String
    val checkUpdate: String
    val checkingUpdate: String
    fun foundUpdate(v: String): String
    val alreadyLatest: String
    val projectSection: String
    val githubProject: String
    val licensesAndThanks: String
    val licensesAndThanksSubtitle: String
    val maintainerSection: String
    val maintainerRole: String
    val supportSection: String
    val supportProject: String
    val supportProjectSubtitle: String
    val sponsorDeveloper: String get() = "赞助开发者"
    val sponsorDeveloperSubtitle: String get() = "使用微信或支付宝赞助支持作者"
    val sponsorDialogTitle: String get() = "赞助与支持"
    val sponsorDialogSubtitle: String get() = "如果 CPPlayer 对你有帮助，欢迎赞助支持本项目的持续开发与维护！"
    val wechatPay: String get() = "微信支付"
    val alipay: String get() = "支付宝"
    val sponsorAccount: String get() = "收款人: Aurora星空(*森)"
    val sponsorThanks: String get() = "感谢每一位支持者的认可与鼓励 ❤️"
    val saveQrCode: String get() = "保存二维码"
    val savingQrCode: String get() = "保存中…"
    val qrSavedToGallery: String get() = "已保存到相册"
    fun qrSavedTo(path: String): String = "已保存到 $path"
    val qrSaveFailed: String get() = "保存失败，请检查存储权限或磁盘空间"
    val close: String get() = "关闭"
    val footerNote: String
    val legacyCleanupNone: String
    val legacyCleanupHint: String
    val updateConfirmDownload: String
}

interface AppUpdateStrings {
    val title: String
    fun titleWithVersion(version: String): String
    val changelogTitle: String
    val defaultChangelog: String
    val viewDiffOnGithub: String
    val downloadNow: String
    val releasePage: String
    val later: String
    val disableAutoUpdate: String
    val installPackage: String
    fun publishedAt(date: String): String
    val packageAndroid: String
    val packageWindows: String
    val packageWindowsPortable: String
    val packageMacos: String
    val packageLinuxDeb: String
    val packageLinuxTar: String
    val packageLinux: String
    val packageUniversal: String
}

/**
 * 视频播放页的文案。
 *
 * 单独成组：这页是**播放器内部**的沉浸式浮层（不是设置 / 媒体库那种页面），
 * 它的错误态、桌面系统播放器引导与质量控制条只在 `VideoPlayerView` 里出现；
 * 塞进 `player` 会让「上一首 / 下一首」所在的组继续膨胀，且两者生命周期不同 ——
 * 音频播放页在后台也常驻，视频页只在打开视频时组合。
 */
interface VideoStrings {
    val playbackFailed: String
    val retry: String
    val videoTitle: String
    val nowPlaying: String
    val resolvedQuality: String
    val standardQuality: String
    val desktopPlayerHint: String
    val openInSystemPlayer: String
    val copiedDirectLink: String
    val copyDirectLink: String
    val playPause: String
    val quality: String
    val fullscreen: String
    val scalingMode: String
    val openExternally: String
}

/**
 * 首页文案。
 *
 * 单独成组：桌面与移动两套布局共用同一批区块标题 / 空态 / 快捷入口，
 * 而 [HomeScreenModel] 的歌单动作提示也归这里 —— 它与页面是同一个生命周期，
 * 放进 `library` 会让「首页每日推荐」与「歌单详情」的同名文案各说各话。
 */
interface HomeStrings {
    val title: String
    val subtitle: String
    val posterWall: String

    val dailyTitle: String
    fun dailySubtitle(count: Int): String
    val intelligenceTitle: String

    val discoveryTitle: String
    val rankingTitle: String
    val rankingSubtitle: String
    val newAlbumsTitle: String
    val newAlbumsSubtitle: String
    val hotArtistsTitle: String
    val hotArtistsSubtitle: String
    val newSongsTitle: String
    val newSongsSubtitle: String

    val recentTitle: String
    val recentSubtitle: String
    val recentEmptySubtitle: String
    val continueListening: String
    val continueListeningSubtitle: String

    val playlistSourceRecommended: String
    val playlistSourceRecommendedHint: String
    val playlistSourceHot: String
    val playlistSourceHotHint: String
    val playlistSourcePremium: String
    val playlistSourcePremiumHint: String

    val quickEntry: String
    val quickRadio: String
    val localShuffle: String
    val localShuffleNote: String
    val personalFm: String
    val personalFmNote: String
    val fmRecommended: String
    val fmExplore: String
    val fmFamiliar: String
    val fmPuzzle: String
    val fmGuideTitle: String
    val fmGuideBody: String
    fun fmLiveStatus(count: Int): String
    fun fmReadyStatus(count: Int): String
    val localLibrary: String
    val localLibraryNote: String
    val intelligenceNote: String
    val intelligenceNeedsDaily: String
    val recentNote: String

    val nowPlaying: String
    val paused: String

    val loadIncompleteTitle: String
    val loadFailed: String
    val localModeTitle: String
    val localModeNoteDesktop: String
    val localModeNoteMobile: String
    val openLocalLibrary: String
    val noPersonalizedTitle: String
    val noPersonalizedNote: String

    fun trackCount(count: Int): String
    val rankingFallback: String
    val viewTracks: String
    val unknownArtist: String

    val recommendedPlaylistFallback: String
    val rankingCreator: String
}

/**
 * 音频输出内核与硬件独占页。
 *
 * ⚠️ 这一页的核心难点是**平台差异**（Windows / 其它桌面 / Android 各有一套真实能力），
 * 所以文案按「平台分支」成组而不是按控件拆：同一条「独占」在 Windows 是 WASAPI、
 * 在 Android 是 Oboe 直通，混用会写出系统里根本不存在的开关。
 */
interface EngineStrings {
    val titleAndroid: String
    val titleDesktop: String

    val heroExclusive: String
    val heroSharedWasapi: String
    val heroSharedSystem: String
    val heroAndroidFlick: String
    val heroAndroidStandard: String

    val badgeExclusive: String
    val badgeSharedDefault: String
    val badgeVip: String
    val badgeDefault: String

    val sectionKernel: String
    val kernelCurrent: String
    val kernelNeedsVip: String
    val kernelEnabled: String
    val kernelSystemNote: String
    val kernelSystemOption: String
    val kernelFlickOption: String
    val kernelSwitchNote: String

    val sectionOutputMode: String
    val wasapiMode: String
    val wasapiExclusiveNote: String
    val wasapiSharedNote: String
    val wasapiSharedOption: String
    val wasapiExclusiveOption: String
    val sharedOutput: String
    val sharedOutputNote: String
    val ready: String
    val exclusiveWarning: String

    val sectionCapabilities: String
    val oboeTitle: String
    val oboeNote: String
    val usbDacTitle: String
    val usbDacNote: String
    val dsdTitle: String
    val dsdNoteAndroid: String
    val dsdNoteDesktop: String
    val wasapiExclusiveTitle: String
    val wasapiExclusiveNoteWindows: String
    val wasapiExclusiveNoteOther: String
    val bitPerfectTitle: String
    val bitPerfectNoteActive: String
    val bitPerfectNoteOther: String
    val bitPerfectNoteInactive: String
    val dspTitle: String
    val dspNote: String

    // ===== Android USB 独占 & 引擎硬件选项 =====
    val sectionToggles: String
    val androidUsbExclusiveTitle: String
    val androidUsbExclusiveNote: String
    val androidUsbAutoResumeTitle: String
    val androidUsbAutoResumeNote: String
    val dsdModeTitle: String
    val dsdModePcm: String
    val dsdModeDop: String
    val dsdModeNative: String
    val dsdModeAuto: String
    val togglesNote: String

    val badgeActive: String
    val badgeNeedsFlick: String
    val badgeAvailable: String
    val badgeUnsupported: String
    val badgeNeedsVip: String
    val badgeNeedsExclusive: String
    val badgeOff: String

    val sectionEffects: String
    val openEffects: String
    val effectsNoteAndroid: String
    val effectsNoteDesktop: String

    val sectionCredits: String
    val creditsNote: String
}

/**
 * 诊断与健康页。
 *
 * ⚠️ 这一页的原始错误串 / 操作名 / Provider ID 刻意**不译**：它们是排查输入，
 * 翻译后就没法拿去搜日志或对上游了。这里只译「标签」与「状态词」。
 */
interface HealthStrings {
    val screenTitle: String
    val clearLogAction: String
    val clearTitle: String
    fun clearMessage(count: Int): String
    val clearConfirm: String

    val tabOverview: String
    val tabProbe: String
    val tabMethods: String
    val tabLogs: String
    val tabStatus: String

    val diagnosticTitle: String
    val diagnosticSubtitle: String
    val runDiagnostics: String
    val reRunDiagnostics: String
    val runningDiagnostics: String
    val diagnosticHealthy: String
    val diagnosticWarning: String
    val diagnosticError: String
    val diagnosticScoreSummary: String
    val diagnosticItemPassed: String
    val diagnosticItemWarning: String
    val diagnosticItemError: String
    val diagnosticItemSkipped: String
    val diagnosticNotRun: String
    val diagnosticNotRunHint: String
    val diagnosticDetailsLabel: String
    val diagnosticSuggestionLabel: String

    val checkNetwork: String
    val checkProvider: String
    val checkSearch: String
    val checkStreamUrl: String
    val checkLyric: String
    val checkDiscovery: String
    val checkAudioEngine: String
    val checkStorageCache: String
    val checkSystemMemory: String

    val toolkitTitle: String
    val copyReport: String
    val copyReportDone: String
    val reloadActiveProvider: String
    val reloadProviderDone: String
    val clearStreamCacheAction: String
    val clearStreamCacheTitle: String
    val clearStreamCacheMessage: String
    val clearStreamCacheDone: String
    val clearImageCacheAction: String
    val clearImageCacheTitle: String
    val clearImageCacheMessage: String
    val clearImageCacheDone: String
    val resetAudioEngineAction: String
    val resetAudioEngineDone: String

    val probeTitle: String
    val probeSubtitle: String
    val probeTargetProvider: String
    val probeSwitchProvider: String
    val probeSwitchDone: String
    val probeOperation: String
    val probeOpSearch: String
    val probeOpDetail: String
    val probeOpStream: String
    val probeOpLyric: String
    val probeOpRankings: String
    val probeOpPlaylists: String
    val probeInputKeywordLabel: String
    val probeInputMediaIdLabel: String
    val probeQualityLabel: String
    val probeExecute: String
    val probeExecuting: String
    val probeResultLabel: String
    val probeDurationLabel: String
    val probeSuccess: String
    val probeUnsupported: String
    val probeFailed: String
    val probeEmptyPrompt: String
    val probeSearchQuickStream: String
    val probeSearchQuickLyric: String
    val probeCopyResult: String
    val probeResultCopied: String

    val filterAll: String
    val filterErrors: String
    val filterWarnings: String
    val filterSuccess: String


    val providerHealthSection: String
    val noLoadedProviders: String
    val systemStatus: String
    fun callsSummary(totalCalls: Int, avgResponseMs: Long): String
    val levelOk: String
    val levelWarning: String
    val levelError: String
    val success: String
    val failure: String
    val unsupported: String
    val activeNow: String
    fun providerMeta(id: String, type: String, version: String): String
    fun score(value: Int): String
    fun loadError(error: String): String
    val noCalls: String
    val successRate: String
    val metricCalls: String
    val metricAverage: String
    val metricWarning: String
    fun recentError(error: String): String
    val diagnostics: String

    val noOperationStats: String
    val operationStatsHeader: String
    fun operationSummary(totalCalls: Int, successRate: Int, avgResponseMs: Long): String
    fun failCount(count: Int): String
    fun warningCount(count: Int): String
    val recentSamples: String

    fun history(count: Int): String
    val resultPrefix: String
    val resultSuccess: String
    val resultUnsupported: String
    val resultFailed: String
    fun errorMessage(message: String): String

    val providerListSection: String
    val noRegisteredProviders: String
    val switchToProvider: String
    fun providerTypeVersion(type: String, version: String, apiVersion: Int): String
    fun apiGeneration(tag: String): String
    fun startupFailed(error: String): String
    val runtimeDiagnostics: String
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
