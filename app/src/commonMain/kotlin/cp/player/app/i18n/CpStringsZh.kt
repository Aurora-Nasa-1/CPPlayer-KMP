package cp.player.app.i18n

/**
 * 简体中文文案 —— **兜底语言**。
 *
 * ⚠️ 加 / 改文案时这里的改动**必须同步到 [CpStringsEn]**：两边实现同一个接口，
 * 漏了会编译不过（这正是选接口方案而非 XML 的理由）。
 */
object CpStringsZh : CpStrings {
    override val language: LanguageStrings = object : LanguageStrings {
        override val screenTitle = "语言"
        override val optionSystem = "跟随系统"
        override val optionSystemNote = "按系统语言显示；系统语言不受支持时回落简体中文"
        override val optionZhHans = "简体中文"

        /**
         * 中文界面下**仍然写英文**—— 这是有意的，不要当漏译改掉。
         *
         * 语言列表按语言**自称**显示是国际惯例：用户找的是「English」这个他认识的名字，
         * 写成「英文」反而多一层翻译。Windows / macOS 的中文语言列表同样混排。
         * [itemLanguage] 的副标题里出现 `English` 同理（那里在列举可选项）。
         */
        override val optionEnglish = "English"
        override val current = "当前"
        override val applyNote = "切换后立即生效，无需重启应用。"
    }

    override val settings: SettingsStrings = object : SettingsStrings {
        override val screenTitle = "设置"
        override val isolationTitle = "音源隔离"
        override val isolationIntro = "每个音源都有自己的登录态、缓存和账号列表，互相不共享：换一个音源，" +
            "看到的账号、红心和歌单就是那个音源自己的。"
        override val isolationSectionAccounts = "登录态"
        override val isolationRefreshProfile = "切音源时同步刷新账号资料"
        override val isolationRefreshProfileNote = "关掉后仍会换登录态（Cookie 本来就按音源存），只是不立即重新拉取昵称与头像"
        override val isolationCurrentAccounts = "当前音源的账号"
        override val isolationUnknownProvider = "未知音源"
        override val isolationSaved = "登录态已保存"
        override val isolationNotSignedIn = "未登录"
        override fun isolationAccountCount(count: Int) = "$count 个账号"
        override val isolationSwitchProvider = "切换音乐源"
        override val isolationSwitchProviderNote = "不同音源之间不会互相污染登录态与缓存"
        override val isolationSectionCache = "缓存"
        override val isolationStorage = "存储与清理"
        override val isolationStorageNote = "封面缓存、下载目录等按音源隔离的数据都在这里管理"
        override val isolationSectionDanger = "危险操作"
        override val isolationClearConfirm = "再次点击确认清除"
        override val isolationClearSession = "清除当前音源的登录态"
        override val isolationClearNote = "只清这一个音源；已保存的账号仍在，可一键切回"
        override fun isolationProfileNote(name: String) = "资料展示：$name。清除登录态不会动本地下载的歌曲。"
        override val groupGeneral = "通用"
        override val groupAccountProvider = "账号与音源"
        override val groupConnectivity = "连接与集成"
        override val groupOther = "其他"

        override val itemLanguage = CpTextPair(
            title = "语言",
            subtitle = "跟随系统，或固定为简体中文 / English", // 混排 English 的理由见 optionEnglish
        )
        override val itemAppearance = CpTextPair(
            title = "界面设置",
            subtitle = "主题、语言、动效与导航",
        )
        override val itemPlayback = CpTextPair(
            title = "播放与音质",
            subtitle = "默认音质与睡眠定时",
        )
        override val itemStorage = CpTextPair(
            title = "下载与存储",
            subtitle = "下载目录、歌曲缓存与图片缓存",
        )
        override val itemShortcuts = CpTextPair(
            title = "快捷键",
            subtitle = "查看与自定义桌面快捷键",
        )
        override val itemAccount = CpTextPair(
            title = "账号与登录",
            subtitle = "登录音源账号、切换与管理已保存的账号",
        )
        override val itemProviders = CpTextPair(
            title = "音源管理",
            subtitle = "导入、切换或移除音源模块",
        )
        override val itemExternalAccess = CpTextPair(
            title = "外部访问",
            subtitle = "对外提供音频流与数据接口、推送到接收端",
        )
        override val itemStandby = CpTextPair(
            title = "局域网设备",
            subtitle = "同一网络里的其他 CPPlayer：互相发现与自动同步听歌记录",
        )
        override val itemAbout = CpTextPair(
            title = "关于与支持",
            subtitle = "版本、更新与项目支持",
        )
        override val itemDiagnostics = CpTextPair(
            title = "诊断",
            subtitle = "查看接口调用状态、日志与回退信息",
        )
        override val itemRenderTuning = CpTextPair(
            title = "渲染后端",
            subtitle = "显示后端与垂直同步；画面撕裂或卡顿时可调整",
        )
        override val itemOnboarding = CpTextPair(
            title = "重看新手引导",
            subtitle = "重新走一遍首次使用引导",
        )
    }

    override val common: CommonStrings = object : CommonStrings {
        override val confirm = "确认"
        override val dismiss = "取消"
        override val save = "保存"
        override val apply = "应用"
        override val refresh = "刷新"
        override val unsavedBlocked = "未保存 · 请先修正上面的问题"
        override val unsavedHint = "未保存 · 点「应用」生效"
        override val back = "返回"
        override val close = "关闭"
        override val search = "搜索"
        override val clearSearch = "清除搜索"
        override val initializingBackend = "正在初始化后端…"
    }

    override val navigation = NavigationStringsZh

    override val messageNotify: MessageNotifyStrings = object : MessageNotifyStrings {
        override val guideTitle = "私信默认不打扰"
        override val guideBody = "想收谁的新消息，就在会话上右键（电脑）/ 长按（手机）→ 开启新消息通知。" +
            "默认不监听任何人，也不会在后台轮询。"
        override val guideConfirm = "我知道了"

        override val menuEnable = "开启新消息通知"
        override val menuDisable = "关闭新消息通知"
        override val sheetTitle = "新消息通知"
        override val sheetBody = "开启后，只有这个人发来新消息时会提醒你。默认关闭。"

        override val settingsTitle = "消息通知"
        override val settingsSubtitle = "私信通知与关闭行为"
        override val masterLabel = "允许私信通知"
        override val masterHint = "关闭时不会做任何后台请求。仅在应用运行时有效"
        override val subscribedSection = "已开启的联系人"
        override val subscribedEmpty = "还没有开启任何联系人"
        override val unsupportedPlatform = "本平台不支持系统通知"
        override val permissionMissing = "系统通知权限未开启，收不到提醒"
        override val grantPermission = "去授权"

        override val trayShowWindow = "显示主界面"
        override val trayExit = "退出 CPPlayer"
        override val trayMiniMode = "迷你模式"
        override val trayNothingPlaying = "未在播放"
        override val trayPlay = "播放"
        override val trayPause = "暂停"
        override val trayPrevious = "上一首"
        override val trayNext = "下一首"

        override val closeDialogTitle = "关闭窗口"
        override val closeDialogMessage = "退出 CPPlayer，还是最小化到托盘继续接收消息通知？"
        override val closeDialogMinimize = "最小化到托盘"
        override val closeDialogExit = "退出"
        override val closeDialogDontAsk = "不再提示，记住我的选择"

        override val closeBehaviorLabel = "关闭窗口时"
        override val closeBehaviorAsk = "每次询问"
        override val closeBehaviorHint = "选「最小化到托盘」才能关窗后继续收到消息通知"
    }

    override val appearance: AppearanceStrings = object : AppearanceStrings {
        override val screenTitle = "界面设置"
        override val sectionLook = "外观"
        override val sectionMotion = "界面动效与视觉表现"
        override val sectionContent = "内容与列表呈现"
        override val sectionMobile = "手机端播放界面与布局"
        override val sectionFont = "字体"

        override val themeMode = "主题模式"
        override val themeModeSystem = "跟随系统"
        override val themeModeLight = "浅色"
        override val themeModeDark = "深色"
        override val themeModeSystemNote = "根据系统日间/夜间状态自动切换"
        override val themeModeLightNote = "始终保持明亮清爽的浅色视觉"
        override val themeModeDarkNote = "始终保持深色暗黑风格，夜间更护眼"
        override val sectionDarkEnhancements = "深色显示增强"
        override val pureBlackDetail = "开启纯黑后，在深色模式下应用底色将由深灰压至绝对纯黑 (#000000)，OLED 屏幕黑色像素不发光，不仅对比度更突出，还能有效节省电量。"

        override val colorSource = "取色来源"
        override val colorSourcePlatform = "跟随系统"
        override val colorSourceCover = "跟随封面"
        override val colorSourceFixed = "固定配色"
        override val colorSourcePlatformOn = "取自系统强调色（安卓壁纸 / Windows 强调色）"
        override val colorSourcePlatformOff = "当前平台不支持，将回退到固定配色"
        override val colorSourceCoverNote = "取自当前曲目封面主色；未播放或无封面时改用系统壁纸配色"
        override val colorSourceFixedNote = "始终使用内置配色，不随内容与系统变化"


        override val pureBlack = "纯黑模式"
        override val pureBlackNote = "深色下使用纯黑背景，OLED 更省电"
        override val autoHideBottomBar = "自动隐藏底栏"
        override val autoHideBottomBarNote = "上滑收起底栏，下滑恢复"
        override val predictiveBack = "预测性手势返回"
        override val predictiveBackNote = "从屏幕边缘侧滑返回时实时预览上一级界面"
        override val coverFlight = "封面飞行动画"
        override val coverFlightNote = "封面飞入播放页的转场动画"

        override val fluidBackground = "流体背景"
        override val fluidBackgroundNote = "播放页流动渐变背景，关闭可省电"
        override val fluidBackgroundDarkOverlay = "深色模式流体背景遮罩"
        override val fluidBackgroundDarkOverlayNote = "深色模式下压暗播放页的流体背景（Android 13 为 15%，其余平台和版本为 20%）"

        override val fluidBackgroundFps = "流体背景帧率"
        override val fluidBackgroundFpsNote = "流体动画最高帧率，越高越流畅"
        override val desktopPlayerControls = "桌面播放界面"
        override val desktopPlayerVolume = "显示音量控制条"
        override val desktopPlayerVolumeNote = "在桌面播放页的播放按钮下方显示音量控制条"

        override val showSongAlbum = "显示歌曲专辑名"
        override val showSongAlbumNote = "在歌曲列表显示所属专辑"

        override val fontRoundness = "字体圆滑度"
        override val fontRoundnessRounded = "圆滑"
        override val fontRoundnessSquare = "方正"

        override fun themeSummary(mode: String, color: String, pureBlack: Boolean) =
            "$mode${if (pureBlack) " · 纯黑已开启" else ""} · $color"

        override val playerStyle = "播放器样式"
        override val playerStyleNote = "切换播放页排版"
        override val playerStyleThreePane = "三段式播放器"
        override val playerStyleStandard = "标准播放器"
        override val playerStyleThreePaneNote = "歌词、播放与评论分页横滑"
        override val playerStyleStandardNote = "封面、歌词与播控收在同一页"
        override val mobilePreferences = "播放器设置"
        override val mobilePreferencesNote = "歌词呈现、沉浸与恢复默认"
        override val playerSettingsTitle = "播放器设置"
        override val playerSettingsHeroNote = "歌词与播放页外观"
        override val playerLyricsPresentation = "歌词呈现"
        override val playerLyricsTranslation = "显示歌词翻译"
        override val playerLyricsTranslationNote = "原文下方同步显示译文"
        override val playerLyricsImmersive = "沉浸歌词"
        override val playerLyricsImmersiveToggle = "自动隐藏控制栏"
        override val playerLyricsImmersiveNote = "无操作后隐藏控制栏"
        override val playerLyricsImmersiveTimeout = "等待时间"
        override val playerLyricsImmersiveTimeoutNote = "无操作多久后收起控制栏"
        override fun playerLyricsImmersiveSeconds(seconds: Int) = "$seconds 秒"
        override val playerResetSection = "重置"
        override val playerReset = "恢复播放器默认设置"
        override val playerResetNote = "重置歌词呈现与沉浸歌词选项"
        override val sectionPreview = "布局与视图预览"
        override val mobilePreview = "手机版界面预览 (412×892)"
        override val mobilePreviewOn = "当前为手机版紧凑视图，点击恢复桌面端大窗口"
        override val mobilePreviewOff = "切换为 412×892 紧凑手机版布局、底部导航栏与手机顶栏"
    }

    override val fontPacks: FontPackStrings = object : FontPackStrings {
        override val sectionTitle = "字库"
        override val intro = "每种字体均可单独开关。未开启或未下载时将使用系统字体渲染对应文字。" +
            "桌面端三款字体随安装包内置；安卓端中日文字体可按需下载与删除。"
        override val unsupported = "当前系统版本不支持字体的可变字重（需要 Android 8.0 及以上），" +
            "中文与日文将继续使用系统字体。"
        override val googleSansTitle = "Google Sans Flex（拉丁 / 数字）"
        override val googleSansNote = "拉丁字母、数字与符号的主字体，支持可变圆滑度"
        override val japaneseTitle = "Noto Sans JP（日文）"
        override val japaneseNote = "日文歌词、日文标题的假名与汉字写法"
        override val misansTitle = "MiSans（中文）"
        override val misansNote = "中文、全角标点与其余文字，含 300–700 五档字重"
        override val stateInstalled = "已安装"
        override val stateMissing = "未下载"
        override fun stateDownloading(percent: Int) = "下载中 $percent%"
        override fun stateFailed(reason: String) = "下载失败：$reason"
        override val actionDownload = "下载"
        override val actionRetry = "重试"
        override val actionDelete = "删除"
        override val deleteConfirm = "删除后中文 / 日文会回到系统字体，可以随时重新下载。"
        override val cancel = "取消"
        override val sourceNote = "字体文件从本项目的 GitHub Release 下载，保存在应用私有目录，" +
            "卸载应用时一并清除。"
    }

    override val storage: StorageStrings = object : StorageStrings {
        override val screenTitle = "下载与存储"

        override val sectionDownload = "下载"
        override val downloadedMusic = "已下载音乐"
        override val downloadedMusicEmpty = "还没有下载内容，点右上角新建或搜索页下载"
        override fun downloadedMusicSummary(count: Int, bytes: String) = "$count 首 · 共 $bytes"
        override val downloadDir = "下载目录"
        override val downloadDirAndroid = "Android 下载固定保存到应用私有目录"
        override val downloadDirDefault = "默认下载目录"
        override val openDir = "打开目录"
        override val openDirNote = "在文件管理器中查看已下载的文件"
        override val dirUnset = "尚未设置下载目录"
        override fun openDirFailed(dir: String) = "打开目录失败：$dir"

        override val sectionSongCache = "歌曲缓存"
        override val cachedSongs = "已缓存歌曲"
        override val cachedSongsEmpty = "暂无缓存；播放歌曲时会自动缓存"
        override fun cachedSongsSummary(count: Int, bytes: String, capacity: String) =
            "$count 首 · 共 $bytes / 上限 $capacity"
        override val cacheCapacity = "容量上限"
        override val cacheCapacityNote = "上限调小后会立刻按最久未播放清理到位"
        override val customCapacity = "自定义容量"
        override val customCapacityDialogTitle = "自定义歌曲缓存上限"
        override val customCapacityDialogNote = "请输入新的缓存容量上限。调小后将立刻按最久未播放清理到位。"
        override fun customCapacityPlaceholderGb() = "例如 10"
        override fun customCapacityPlaceholderMb() = "例如 2048"
        override fun customCapacityPreview(bytes: String) = "设定容量：$bytes"
        override val customCapacityInvalid = "请输入有效容量（128 MB ~ 1024 GB）"
        override val customCapacityUnitGb = "GB"
        override val customCapacityUnitMb = "MB"
        override fun customCapacitySubtitle(capacity: String) = "当前自定义上限: $capacity，点击可修改"
        override fun customCapacityCurrent(capacity: String) = "当前上限: $capacity，点击可自定义任意大小"
        override val cacheDir = "缓存目录"
        override val migrateCacheDir = "迁移缓存目录"
        override val migrateCacheDirNote = "更改歌曲缓存位置并搬移已有文件"
        override val migrateCacheDirConfirmTitle = "迁移歌曲缓存目录"
        override fun migrateCacheDirConfirmMessage(target: String) =
            "确定要将歌曲缓存迁移至以下目录吗？\n$target\n\n已有的缓存文件将被自动复制到新目录。"
        override val confirmMigrate = "确定迁移"
        override val cacheDirMigrating = "正在迁移缓存目录..."
        override val clearStaleCache = "清理 30 天未播放的缓存"
        override val clearStaleCacheNote = "只删长期不听的，最近在听的不受影响"
        override val clearSongCache = "清空歌曲缓存"
        override val clearSongCacheNote = "删除全部本地副本，已下载的音乐不受影响"
        override val clearSongCacheConfirmTitle = "清空歌曲缓存？"
        override fun clearSongCacheConfirmMessage(count: Int, bytes: String) =
            "将删除 $count 首缓存（约 $bytes）。已下载的音乐不受影响；" +
                "这些无损歌曲下次播放时会重新缓存。"
        override val openCacheDir = "打开缓存目录"
        override val openCacheDirNote = "在文件管理器中核对 / 备份缓存文件"
        override val openCacheDirFailed = "打开缓存目录失败"

        override val sectionImageCache = "图片缓存"
        override val imageCache = "图片缓存"
        override val imageCacheMeasuring = "统计中…"
        override fun imageCacheUsage(bytes: String) = "占用 $bytes"
        override val clearImageCache = "清理图片缓存"
        override val clearImageCacheNote = "释放封面等图片占用的空间；已下载的歌曲不受影响"

        override val noteAndroid = "下载目录的改动仅对后续下载生效，已下载的文件不会移动。" +
            "清理各类缓存都不会删除已下载的音乐。"
        override val noteDesktop = "下载目录的改动仅对后续下载生效，已下载的文件不会移动；" +
            "如需迁移，可在打开目录后手动移动文件。清理各类缓存都不会删除已下载的音乐。"
        override val dirUpdated = "下载目录已更新，仅对后续下载生效"
    }

    override val songCache: SongCacheStrings = object : SongCacheStrings {
        override val screenTitle = "歌曲缓存"
        override val unsupported = "当前平台不支持音频缓存。"
        override val measuring = "正在统计缓存…"
        override val empty = "还没有缓存内容。播放无损音质的歌曲时会自动缓存到本地。"
        override val measuringShort = "正在统计…"
        override val emptyShort = "暂无缓存"
        override fun summary(count: Int, bytes: String) = "$count 首 · 共 $bytes"
        override fun summaryCapped(count: Int, bytes: String, capacity: String) =
            "$count 首 · 共 $bytes / 上限 $capacity"
        override val searchPlaceholder = "搜索歌名 / 歌手 / 音质"
        override val clearSearch = "清除搜索"
        override val unknownTrack = "未知曲目"
        override fun noMatch(query: String) = "没有匹配「$query」的缓存。"
        override fun deleteEntry(name: String) = "删除「$name」的缓存"
        override fun deleted(name: String, freed: String) = "已删除「$name」的缓存，释放了 $freed"
        override val deleteFailed = "删除失败，文件可能正在播放"

        override val convertAll = "一键转为下载"
        override val converting = "正在转换…"
        override val convertAllNote = "把缓存里完整的歌曲直接转为正式下载，不再重新下载一遍；" +
            "只转换已记录音源与音质的缓存，同一首歌已有下载时跳过。"
        override fun convertEntry(name: String) = "把「$name」的缓存转为下载"
        override fun converted(count: Int, skipped: Int) =
            if (skipped > 0) "已把 $count 首缓存转为下载，跳过 $skipped 首" else "已把 $count 首缓存转为下载"
        override val nothingToConvert = "没有可转换的缓存（缺少音源或音质信息的旧缓存无法转换）"
        override val convertFailed = "转换失败，请稍后重试"

        override val timeUnknown = "时间未知"
        override val timeJustNow = "刚刚"
        override fun timeMinutesAgo(minutes: Long) = "$minutes 分钟前"
        override fun timeHoursAgo(hours: Long) = "$hours 小时前"
        override fun timeDaysAgo(days: Long) = "$days 天前"
        override fun timeMonthsAgo(months: Long) = "$months 个月前"
    }

    override val playback: PlaybackStrings = object : PlaybackStrings {
        override val screenTitle = "播放与音质"

        override val sectionQuality = "音质"
        override val defaultQuality = "默认音质"
        override val defaultQualityNote = "在线播放优先请求的音质"
        override val meteredQuality = "移动数据音质"
        override val meteredQualityNote = "蜂窝网络下使用的音质"

        override val sectionAudioEngineAndroid = "播放内核"
        override val sectionAudioOutputDesktop = "音频输出模式"
        override val engineMedia3 = "系统 Media3（默认）"
        override val engineFlick = "FlickEngine（VIP）"
        override val outputShared = "共享（默认）"
        override val outputExclusive = "独占（VIP）"
        override val activeKernel = "当前生效内核"
        override val wasapiMode = "WASAPI 输出模式"
        override val kernelVipInactive = "卡密未激活：已自动回落到系统内核"
        override val kernelFlickActive = "FlickEngine 低延迟直通 · 下次起播生效"
        override val kernelMedia3 = "系统 Media3：兼容与耗电更稳，发烧特性不可用"
        override val kernelExclusiveActive = "已接管声卡：播放期间其它应用静音"
        override val kernelShared = "共享声卡：可与其它应用同时出声"
        override val engineDetailAndroid = "Oboe 直通、USB DAC 与 DSD"
        override val engineDetailDesktop = "独占、Bit-Perfect 与 DSD"
        override val engineDetailAndroidNote = "Oboe 低延迟直通 · UAC 2.0 USB DAC 源码输出 · DSD64/128 软解"
        override val engineDetailDesktopNote = "WASAPI 独占 · Bit-Perfect 采样率自适应 · DSD64/128 软解"
        override val engineDetailDesktopUnsupportedNote = "当前平台没有 WASAPI 独占通道，仅提供共享输出"
        override val featureDetails = "特性说明"
        override val vipManagement = "发烧特权授权管理"
        override fun vipActivated(plan: String) = "已激活：$plan"
        override fun vipActivatedWithKey(plan: String, key: String) = "已激活：$plan ($key)"
        override val vipInactiveNote = "未激活，点击输入卡密解锁 FlickEngine 与独占直通"
        override val vipDialogTitle = "解锁 Hi-Res 发烧友专有特权"
        override val vipDialogNote = "FlickEngine 在 Windows 上提供 WASAPI 硬件独占（Bit-Perfect 源码直通），" +
            "在 Android 上提供 Oboe 低延迟与 USB DAC 直通；两者都内置 DSD64/128 软解与" +
            "64 位浮点 DSP。独占模式默认关闭，激活后仍需你在设置里手动开启。"
        override val vipKeyLabel = "请输入特权卡密"
        override val vipKeyPlaceholder = "粘贴离线卡密或云控卡密"
        override val vipActivateFailed = "激活失败，请检查激活码"
        override val vipActivateNow = "立即激活"
        override val vipActivateLater = "暂不激活"
        override val changeLicenseKey = "更换卡密"
        override val activatePrivilege = "激活特权"
        override val dspSection = "DSP 音效与调音台"
        override val dspNote = "5 段参数均衡器 (PEQ)、风格预设、声道平衡与立体声宽度"
        override val mixer = "调音台"
        override val edit = "修改"
        override val set = "设置"

        override val sectionLyrics = "歌词"
        override val lyricsSource = "歌词来源"
        override val lyricsSourceNote = "AMLL 优先，缺词时回退音源歌词"
        override val lyricsProviderOnly = "仅音源 API"
        override val lyricsAmllFirst = "AMLL 优先"
        override val lyricsAmllOnly = "仅 AMLL"
        override val showMiniPlayerLyrics = "迷你播放器歌词"
        override val showMiniPlayerLyricsSubtitle = "显示当前歌词，关闭后显示歌手"
        override val showRubyLyrics = "日文汉字注音"
        override val showRubyLyricsSubtitle = "日文歌词汉字上方显示假名"
        override val lyricsWordSegmentation = "歌词智能分词"
        override val lyricsWordSegmentationSubtitle = "日文等按词换行，关闭按整句"
        override val smartKaraokeLrc = "行级歌词智能逐字 (Beta)"
        override val smartKaraokeLrcSubtitle = "无逐字歌词时模拟逐字高亮"

        override val sectionSleepTimer = "睡眠定时"
        override val sleepTimer = "定时关闭"
        override val sleepAfterTrack = "播完当前歌曲后暂停"
        override fun sleepRemaining(minutes: Long) = "剩余 $minutes 分钟"
        override val sleepOff = "未启用"

        override val sectionAutoPlay = "启动与自动播放"
        override val autoPlayMode = "打开软件自动播放"
        override val autoPlayModeNote = "启动后自动开始播放"
        override val autoPlayModeOff = "关闭"
        override val autoPlayModeContinue = "继续上次播放"
        override val autoPlayModeDaily = "每日推荐"
        override val autoPlayModeCustom = "指定歌单/歌曲"
        override val autoPlayCustomTarget = "指定播放内容"
        override val autoPlayCustomTargetEmpty = "未设置，点击选择歌单或输入 ID"
        override val autoPlayCustomDialogTitle = "设置自动播放内容"
        override val autoPlayPickFromPlaylists = "从我的歌单选择"
        override val autoPlayUseCurrentSong = "设为当前播放歌曲"
        override val autoPlayUseCurrentPlaylist = "设为当前播放歌单"
        override val autoPlayManualInput = "手动输入 ID 或链接"
        override val autoPlayTypePlaylist = "指定歌单"
        override val autoPlayTypeTrack = "指定单曲"
        override val autoPlayInputIdPlaceholder = "输入歌单/歌曲 ID 或分享链接"
        override val autoPlayInputTitlePlaceholder = "自定义显示名称 (可选)"
        override fun autoPlayPlaylistLabel(id: String) = "歌单 $id"
        override val autoPlayClearTarget = "清除指定"

        override val sectionLastPlayback = "上次播放"
        override val keepLastPlayback = "保留上次播放"
        override val keepLastPlaybackNote = "启动时恢复上次队列与进度"

        override val sectionPreload = "音频预加载"
        override val preloadNext = "预加载下一首歌曲"
        override val preloadNextNote = "提前缓冲下一首，切歌更快"

        override val sectionFade = "淡入淡出"
        override val fadeToggle = "启用淡入淡出"
        override val fadeNote = "切歌时音量平滑过渡"
        override val fadeIn = "开始时淡入"
        override val fadeInNote = "播放新曲目时，音量从无声逐渐升到当前音量"
        override val fadeOut = "结束前淡出"
        override val fadeOutNote = "曲目结束前音量逐渐降低，避免下一首突然响起"
        override val fadeDuration = "过渡时长"
        override val fadeDurationNote = "淡入与淡出各用这段时长"
        override fun fadeDurationSeconds(seconds: String) = "$seconds 秒"

        override val sectionBackground = "后台播放"
        override val batteryWhitelist = "电池优化白名单"
        override val batteryWhitelistOn = "已加入白名单，熄屏后台播放受系统保护"
        override val batteryWhitelistOff = "未开启 —— 熄屏后系统可能很快杀掉后台播放，点击申请"
        override val vendorNote = "部分厂商系统（MIUI/HyperOS、HarmonyOS、ColorOS 等）" +
            "还需在「自启动管理」里允许 CPPlayer 自启动与后台运行。"
        override val sharedTimerNote = "这里与播放页的睡眠定时入口打开的是同一个对话框，状态始终一致。"

        override val timerDialogTitle = "睡眠定时"
        override fun timerActiveAfterTrack() = "当前：播完本曲后暂停"
        override fun timerActiveInMinutes(minutes: Long) = "当前：${minutes + 1} 分钟后暂停"
        override val timerPrompt = "多少分钟后暂停播放？"
        override fun timerMinutesChip(minutes: Int) = "$minutes 分钟"
        override val timerAfterTrackChip = "播完本曲"
        override val timerCancel = "取消定时"
        override val timerClose = "关闭"

        override val timerCustomChip = "自定义..."
        override val timerCustomDialogTitle = "自定义定时关闭"
        override val timerCustomModeDuration = "倒计时时长"
        override val timerCustomModeAtTime = "指定时刻"
        override fun timerCustomDurationHint(minutes: Int, endTimeText: String) =
            "将在 $minutes 分钟后停止播放（约 $endTimeText）"
        override fun timerCustomAtTimeHint(targetTimeText: String, diffMinutes: Long) =
            "将在 $targetTimeText 停止播放（约 $diffMinutes 分钟后）"
        override val timerCustomConfirm = "确定"
        override val timerCustomToggleDial = "切换至时钟表盘"
        override val timerCustomToggleInput = "切换至文本输入"
    }

    override val audioEffect: AudioEffectStrings = object : AudioEffectStrings {
        override val screenTitle = "音效"
        override val entrySubtitle = "参数均衡器、声道平衡与响度均衡"
        override val unsupportedNote = "当前平台的音频引擎只提供音量接口，没有可用的均衡与效果处理，" +
            "因此本页在桌面端不可调整。"
        override val kernelStatusSection = "音频内核状态"
        override val kernelNotReadyTitle = "FlickEngine 原生音频库未就绪"
        override val kernelNotReadyNote = "桌面端的解码、音效与输出全部由 FlickEngine 提供；库缺失时音效与播放都不可用。" +
            "请确认安装完整，或到音频输出页查看引擎状态。"
        override val viewStatus = "查看状态"
        override val equalizerEnabledSummary = "已启用 · 5 段参数均衡生效中"
        override val resetFlat = "重置平直"
        override val advancedFrequencyTuning = "高级：中心频点微调 (PEQ)"
        override val advancedFrequencyTuningNote = "逐段调整频段滤波截止频率"
        override fun bandCenterFrequency(index: Int) = "频段 ${index + 1} 中心频率"

        override val sectionEqualizer = "参数均衡器"
        override val equalizerEnabled = "启用均衡器"
        override val equalizerEnabledNote = "按下面的曲线调整各频段响度；关闭时音频原样输出"
        override val equalizerPreset = "预设"
        override val equalizerPresetNote = "选一个预设作为起点，之后可继续单独调整每一段"
        override val presetFlat = "平直"
        override val presetPop = "流行"
        override val presetRock = "摇滚"
        override val presetVocal = "人声"
        override val presetBassBoost = "低音增强"
        override val presetTrebleBoost = "高音增强"
        override val presetCustom = "自定义"

        override fun bandTitle(index: Int) = "第 ${index + 1} 段"
        override fun bandSubtitle(index: Int, hz: String, gainDb: String) = "$hz · $gainDb"
        override val bandFrequency = "频率"
        override val bandGain = "增益"
        override fun gainLabel(gainDb: String) = "$gainDb dB"

        override val sectionMixer = "混音"
        override val balance = "声道平衡"
        override val balanceNote = "左右声道音量配比，只在单侧衰减，不会把整体变响"
        override val balanceCenter = "居中"
        override val balanceLeft = "偏左"
        override val balanceRight = "偏右"

        override val sectionLeveling = "音量均衡"
        override val levelingEnabled = "响度均衡"
        override val levelingEnabledNote = "把过响的段落压下来、过轻的抬上去，避免音量忽大忽小"
        override val levelingTarget = "目标响度"
        override val levelingTargetNote = "数值越大整体越响；它作用于音量滑杆之前，不是替代音量"
        override val preventClipping = "防爆音"
        override val preventClippingNote = "限制峰值电平，避免削顶产生的失真"

        override val levelingNote = "响度均衡与防爆音都在音量调节之前生效，因此不会与音量滑杆重复。" +
            "均衡器与混音会改变音频信号本身，追求原始音质时请关闭音效。"
    }

    override val shortcuts: ShortcutStrings = object : ShortcutStrings {
        override val screenTitle = "快捷键"
        override val note = "快捷键在任意界面生效；输入框获得焦点时不会触发（字母 / 数字键照常输入）。" +
            "点击任意一行可以重新录入键位。"
        override val unbound = "未绑定"

        override val categoryPlayback = "播放控制"
        override val categoryMode = "播放模式与收藏"
        override val categoryNavigation = "导航与窗口"

        override val keySpace = "空格"
        override val keyEnter = "回车"
        override val keyBackspace = "退格"

        override val actionPlayPause = "播放 / 暂停"
        override val actionPlayPauseHint = "切换当前曲目的播放状态"
        override val actionPrevTrack = "上一首"
        override val actionPrevTrackHint = "跳到队列中的上一首"
        override val actionNextTrack = "下一首"
        override val actionNextTrackHint = "跳到队列中的下一首"
        override val actionSeekBackward = "快退 5 秒"
        override val actionSeekForward = "快进 5 秒"
        override val seekHint = "只在可拖动的曲目上生效（时长未知时自动忽略）"
        override val actionToggleFavorite = "收藏 / 取消收藏"
        override val actionToggleFavoriteHint = "收藏当前播放的曲目；未登录时不生效"
        override val actionToggleShuffle = "随机播放"
        override val actionToggleShuffleHint = "开 / 关当前队列的随机播放"
        override val actionCycleRepeat = "切换循环模式"
        override val actionCycleRepeatHint = "关 → 列表循环 → 单曲循环 → 关"
        override val actionBack = "返回上一级"
        override val actionBackHint = "等价于标题栏上的返回键：先退当前页面 / 面板，退无可退时不响应"
        override val actionOpenSettings = "打开设置"
        override val actionOpenSettingsHint = "从任意页面回到主界面并打开设置"

        override val resetAll = "全部恢复默认"
        override val resetAllNote = "丢弃所有自定义键位，回到出厂设置"
        override val resetAllConfirmTitle = "全部恢复默认？"
        override val resetAllConfirmMessage = "所有自定义的快捷键都会丢失，恢复为默认键位。"
        override val resetLabel = "恢复"

        override val recorderTitle = "设置快捷键"
        override fun recorderTarget(label: String) = "「$label」"
        override val recorderWaiting = "按下新的组合键…"
        override val recorderWaitingHint = "直接按下你想要的组合键，例如 Ctrl + Shift + K"
        override val recorderRecordedHint = "再按一下可以换成别的组合键"
        override fun recorderConflict(others: String) =
            "注意：这个组合已经给了「$others」，保存后会同时占用。"
        override val recorderSave = "保存"
        override val recorderReset = "恢复默认"
        override val recorderClear = "清除"
    }

    override val quality: QualityStrings = object : QualityStrings {
        override fun labelOf(level: String) = when (level) {
            "standard" -> "标准"
            "higher" -> "较高"
            "exhigh" -> "极高"
            "lossless" -> "无损"
            "hires" -> "Hi-Res"
            "jymaster" -> "母带"
            "sky" -> "沉浸声"
            "jyeffect" -> "音效"
            else -> level
        }
        override fun fullLabelOf(level: String) = when (level) {
            "standard" -> "标准音质"
            "higher" -> "较高音质"
            "exhigh" -> "极高音质"
            "lossless" -> "无损音质"
            "hires" -> "Hi-Res"
            "jymaster" -> "母带音质"
            "sky" -> "沉浸声"
            "jyeffect" -> "音效"
            else -> "${level}音质"
        }
    }

    override val externalAccess: ExternalAccessStrings = object : ExternalAccessStrings {
        override val screenTitle = "外部访问"

        // ---- 对外服务 ----
        override val sectionService = "对外服务"
        override val enabled = "启用外部访问"
        override val enabledNote = "在本机开一个 HTTP 服务：第三方按曲目拉流、读取音源数据，" +
            "也可以主动把曲目推给接收端"
        override val audioOutput = "音频输出"

        override val outputLocal = "本机播放"
        override val outputRemoteOnly = "仅对外提供"
        override fun outputNote(local: Boolean) =
            if (local) "本机正常出声" else "本机不出声，音频只从接收端播放"

        override val bindScope = "绑定范围"
        override val bindScopeNote = "决定哪些设备能访问这个服务"
        override val scopeLocalhost = "仅本机"
        override val scopeLan = "局域网"
        override val warningLanNoToken = "当前绑定局域网却没有访问令牌：媒体面与数据面都会拒绝所有请求，" +
            "请先在下面重新生成访问令牌。"

        override val statusDisabled = "服务未启用。"
        override fun statusStartFailed(error: String) = "启动失败：$error"
        override fun statusRunning(url: String) = "运行中 · $url"
        override val statusStarting = "启动中…"

        // ---- 端口 ----
        override val sectionPort = "端口"
        override val port = "服务端口"
        override val portNote = "第三方拉流与读数据时访问的端口；改动即时生效"
        override val portRestartNote = "端口改动会重启监听，正在进行的拉流会短暂中断。"
        override fun portEmpty() = "端口不能为空"
        override fun portNotNumber() = "端口必须是数字"
        override fun portOutOfRange(from: Int, to: Int) = "端口需在 $from–$to 之间"

        // ---- 访问令牌 ----
        override val sectionToken = "访问令牌"
        override val regenerateToken = "重新生成访问令牌"
        override val regenerateTokenNote = "所有已连接的设备将立即失效"
        override val regenerateConfirmTitle = "重新生成访问令牌？"
        override val regenerateConfirmMessage = "旧令牌会立即作废。所有已连接的第三方软件与接收端都需要改用新令牌，" +
            "否则会收到 401。"
        override val regenerateLabel = "重新生成"
        override val tokenRegenerated = "访问令牌已重新生成，已连接的设备需重新配置"
        override val tokenStorageNote = "拉流与数据接口共用同一个访问令牌，请妥善保管。"
        override val currentToken = "当前令牌"
        override val tokenAutoGenerated = "启用后自动生成"

        // ---- 允许第三方访问 ----
        override val sectionThirdParty = "允许第三方访问"
        override val allowStream = "允许第三方拉取音频流"
        override val allowStreamNote = "接收端按曲目拉流所需的 GET /stream"
        override val allowApi = "允许第三方读取音源数据"
        override val allowApiNote = "搜索、曲目与播放状态（/api/v1/…）；默认关闭，因为会扩大暴露面"
        override val apiSwitchNote = "关闭「读取音源数据」后数据接口整体返回 403，" +
            "但不影响接收端拉流。两个开关都即时生效，且都以本页顶部的总开关为前提。"

        // ---- 推送到接收端 ----
        override val sectionPush = "推送到接收端"
        override val receiverAddress = "接收端地址"
        override val receiverAddressNote = "接收推送的软件地址；推送方向是 CPPlayer 主动连它"
        override val autoPush = "曲目变化时自动推送"
        override val autoPushNote = "开始播放新曲目时自动替换接收端队列并播放"
        override val testConnection = "测试连接"
        override fun testProbing() = "正在请求…"
        override fun testIdle(endpoint: String) = "请求接收端 $endpoint"
        override val pushCurrentTrack = "推送当前曲目"
        override val pushCurrentTrackNote = "替换接收端队列为这一首并立即播放"
        override val pushCurrentQueue = "推送当前队列"
        override val pushCurrentQueueNote = "整队列交给接收端，由它负责顺序播放"

        // ---- 接口地址 ----
        override val sectionEndpoint = "接口地址"
        override val endpointNote = "拉流在 /stream、读数据在 /api/v1/…，都挂在这个地址下；" +
            "按曲目拉流时会带上 ?mediaId=…"
        override val configFileNote = "第三方软件可通过配置目录获取访问地址与令牌。"
        override val serviceDisabledNote = "外部访问当前未启用，上面的地址暂时不可达。" +
            "打开本页顶部的总开关即可。"

        override fun addressEmpty() = "接收端地址不能为空"
        override fun addressScheme() = "需要以 http:// 或 https:// 开头"
        override fun addressNoHost(example: String) = "缺少主机名，例如 $example"
        override val pushNever = "尚无推送记录"
        override val pushOk = "上次推送成功"
        override fun pushFailed(reason: String) = "上次推送失败：$reason"
    }

    override val standby: StandbyStrings = object : StandbyStrings {
        override val screenTitle = "局域网设备"

        override val sectionSelf = "本机"
        override val deviceName = "名称"
        override val devicePlatform = "平台"
        override val deviceId = "设备 ID"
        override val deviceVersion = "版本"
        override val discoveryStatus = "发现状态"
        override val discoveryFailed = "启动失败"
        override val listening = "监听中"
        override val notStarted = "未启动"

        override val sectionVisible = "在局域网中可见"
        override val visible = "在局域网中可见"
        override val visibleNote = "应用运行期间持续广播本机信标并监听其他设备 —— 无感同步与转移的前提；" +
            "关闭后本机在局域网里隐身"

        override val beaconSent = "信标已发送"
        override fun beaconSentRounds(count: Long) = "$count 轮"
        override val beaconReceived = "已收到信标"
        override val beaconInvalid = "无法识别的信标"
        override val lastReceived = "最近收到"
        override fun discoveryStartFailed(error: String) = "设备发现启动失败：$error"

        override val sectionAutoSync = "自动同步（局域网）"
        override val autoSync = "自动同步听歌记录"
        override val autoSyncNote = "两台设备互相交换听歌历史；开启后自动进行，无需任何手动操作"
        override val syncNow = "立即同步"
        override val neverSynced = "尚未同步"
        override val syncService = "同步服务"
        override fun syncListeningPort(port: Int) = "监听中（端口 $port）"
        override val lastSync = "上次同步"
        override val syncWarning = "⚠️ 开启后将自动同步局域网内运行 CPPlayer 设备的听歌历史（不包含账号与隐私数据）。建议仅在受信任的网络中开启。"
        override val syncEnabledNote = "默认关闭。开启后两台设备只要连接同一 Wi-Fi 网络并打开 CPPlayer，听歌记录即可自动双向合并。"

        override val sectionKeepAlive = "后台在线"
        override val aggressiveStandby = "激进保活"
        override val aggressiveStandbyNote = "熄屏后维持 Wi-Fi 在线，让设备发现与换设备播放仍可能命中"
        override val notEnabled = "未启用"
        override val inEffect = "已生效"
        override val notInEffect = "未生效（可能被系统拒绝）"
        override val keepAliveHint = "。没有它，熄屏后系统可能丢弃组播包。"

        override val sectionLanDevices = "局域网设备"
        override val discovering = "正在监听局域网设备，只要对端也在运行 CPPlayer 即可自动发现。"
        override val discoveryNotStarted = "设备发现未启动。"
        override val onlineDevices = "在线设备"
        override fun onlineCount(count: Int) = "$count 台"
        override val sectionOnline = "在线 —— 点击把当前播放转移过去"
        override val localNotPlaying = " · 本机未在播放"
        override val sectionOffline = "已离线"
        override fun justOnline(address: String, port: Int) = "$address:$port · 刚才还在"
        override val troubleshooting = "未发现设备时请排查：① 确保两台设备均运行最新版 CPPlayer；" +
            "② 确认开启「在局域网中可见」且防火墙已放行；" +
            "③ 确认处于同一 Wi-Fi 或局域网子网；" +
            "④ 关闭路由器 AP 隔离或访客网络隔离。"
        override val handoffPrereq = "设备转移要求接收端已开启「自动同步」，且双方已登录同一音源模块。"
        override val handoffSecurityNote = "建议仅在受信任的局域网环境中使用设备间同步与转移功能。"
        override val keepAliveReality = "在熄屏后维持 Wi-Fi 活跃状态，可降低被系统后台休眠清理的概率。"
        override val keepAliveRecommend = "建议配合开启电池优化白名单，提升后台运行稳定性。"

        override val handoffNoTrack = "当前没有正在播放的曲目，无法转移"
        override fun handoffStarting(device: String) = "正在转移到 $device …"
        override fun handoffDone(device: String) = "已转移到 $device（本机已暂停，进度保留）"
        override fun handoffFailed(reason: String) = "转移失败：$reason —— 本机继续播放"
        override fun handoffTakenOver(device: String, track: String) = "已接管 $device 的播放：$track"
        override fun handoffTakeOverFailed(device: String, reason: String) =
            "$device 想转移播放，但接管失败：$reason"
        override val peerFallbackName = "对端"
        override val noResponse = "设备无响应"
        override val cannotPlayTrack = "本机无法播放该曲目（音源未登录或曲目不存在）"
        override val startTimedOut = "本机播放未能在时限内启动"

        override val pickerTitle = "转移到其他设备"
        override fun pickerNowPlaying(track: String) = "正在播放：$track"
        override val pickerNoTrack = "当前没有正在播放的曲目"
        override fun pickerDiscoveryFailed(error: String) = "设备发现启动失败：$error"
        override val pickerSearching = "正在搜索局域网内的设备…\n对方在运行较新版本的 CPPlayer 就会出现；" +
            "但转移要求对方开着「自动同步」（设置 → 局域网设备），否则接不住。"
        override val handoffToLabel = "转移到这里"
        override val pickerHint = "点击即把当前播放（含整条队列）转移到该设备；" +
            "本机自动暂停、进度保留。转移前提：对方已登录同一音源。"
    }

    override val player: PlayerStrings = object : PlayerStrings {
        override val share = "分享"
        override val shareUnavailableLocal = "本地歌曲无在线分享链接"
        override val addToPlaylist = "加入歌单"
        override val downloaded = "已下载"
        override val download = "下载"
        override val disliked = "已标记不感兴趣"
        override val dislike = "不感兴趣"
        override val listenTogether = "一起听"
        override val transferDevice = "转移到设备"
        override val play = "播放"
        override val removeFromQueue = "从队列移除"
        override val saveAsPlaylist = "保存为歌单"
        override val locateCurrent = "定位当前"
        override val clearQueue = "清空队列"
        override val more = "更多"

        override val lyricsLoading = "歌词加载中…"
        override val lyricsEmpty = "暂无歌词"
        override val lyricsIdle = "等待曲目开始播放后展示歌词"
        override fun lyricsError(message: String) = "歌词获取失败：$message"

        override fun capabilityLabel(key: String): String? = when (key) {
            "qrLogin" -> "扫码登录"
            "wordSyncedLyrics" -> "逐字歌词"
            "comments" -> "评论"
            "cloudDrive" -> "云盘"
            "recommend" -> "个性推荐"
            "lyricsSearch" -> "搜索歌词"
            // 未识别的能力返回 null（调用方跳过）——**不要回落到 key 本身**，
            // 那会把 `wordSyncedLyrics` 这种内部标识直接甩给用户看。
            else -> null
        }

        override val songInfo = "歌曲信息"
        override val lyricInfo = "歌词信息"
        override val audioFormat = "音频格式"
        override val infoSource = "来源"
        override val infoFormat = "格式"
        override val wordLevelLyrics = "逐字歌词"
        override val yes = "有"
        override val no = "无"
        override val infoTranslation = "翻译"
        override val infoPhonetic = "音译"
        override val infoCodec = "编码"
        override val infoSampleRate = "采样率"
        override val infoBitDepth = "位深"
        override val infoBitrate = "码率"
        override val infoChannels = "声道"
        override val infoSize = "文件大小"
        override val infoPath = "文件路径"
        override val unknownAlbum = "未知专辑"

        override fun shareTrack(name: String, artist: String, album: String, duration: String, id: String) =
            "歌曲：$name\n歌手：$artist\n专辑：$album\n时长：$duration\n歌曲 ID：$id"
        override fun shareLyrics(source: String, format: String, wordLevel: String, extra: String) =
            "歌词信息\n来源：$source\n格式：$format\n逐字歌词：$wordLevel" + extra
        override fun shareAudio(
            codec: String?,
            sampleRate: String?,
            bitDepth: String?,
            bitrate: String?,
            channels: String?,
        ) = buildString {
            append("音频格式")
            codec?.let { append("\n编码：$it") }
            sampleRate?.let { append("\n采样率：$it Hz") }
            bitDepth?.let { append("\n位深：$it bit") }
            bitrate?.let { append("\n码率：$it kbps") }
            channels?.let { append("\n声道：$it") }
        }

        override val sleepTimer = "睡眠定时"
        override fun sleepTimerAfterTrack() = "睡眠定时 · 播完本曲"
        override fun sleepTimerRemaining(minutes: Long) = "睡眠定时 · $minutes 分钟"

        override val nowPlaying = "正在播放"
        override val collapse = "收起"
        override val lyricsCollapse = "收起歌词"
        override val lyricsExpand = "展开完整歌词"
        override val immersive = "沉浸歌词"
        override val exitImmersive = "退出沉浸"
        override val songComments = "歌曲评论"
        override val audioOutputDevice = "播放设备"
        override val translation = "翻译"
        override val queue = "队列"
        override val repeat = "循环"
        override val shuffle = "随机播放"
        override val like = "点赞"
        override val unlike = "取消点赞"
        override val volume = "音量"
        override val mute = "静音"
        override val unmute = "取消静音"
        override val quality = "音质"
        override val qualityGlobalNote = "全局设置：对之后加载的曲目生效，正在播放的这首不打断"
        override fun sleepRemainingLabel(remaining: String) = "剩余 $remaining"
        override val sleepEndsWithTrack = "本曲结束"

        override fun queueCount(size: Int) = "$size 首 · 接下来播放"
        override val queueUpNext = "接下来播放"
        override val queueSaved = "队列已保存为歌单"
        override val queueSaveFailed = "保存歌单失败"
        override val queueClearTitle = "清空播放队列"
        override fun queueClearMessage(size: Int) = "确定清空全部 $size 首队列歌曲吗？"
        override val queueEmptyLabel = "队列"
        override val queueEmptyHint = "播放队列是空的"
        override val queueNoMatchHint = "队列里没有匹配的歌曲"
        override val queueReorderHint = "拖拽重排"
        override val coverContentDescription = "封面"

        override val previousTrack = "上一首"
        override val nextTrack = "下一首"
        override fun playOrPause(playing: Boolean) = if (playing) "暂停" else "播放"

        override val commentsLoadFailed = "加载评论失败"
        override val retry = "重试"
        override val noComments = "暂无评论"

        override val lyricOffset = "歌词微调"
        override val lyricOffsetTitle = "歌词时间轴微调"
        override fun lyricOffsetSubtitle(ms: Long) = when {
            ms > 0 -> "歌词提前 ${ms / 1000f} 秒"
            ms < 0 -> "歌词延后 ${-ms / 1000f} 秒"
            else -> "未微调 (标准对齐)"
        }
        override fun lyricOffsetBadge(ms: Long) = "偏移 " + (if (ms > 0) "+" else "") + "${ms / 1000f}s"
        override val lyricOffsetAdvance = "提前 (+)"
        override val lyricOffsetDelay = "延后 (-)"
        override val lyricOffsetReset = "重置为 0"
        override val lyricOffsetHint = "当歌词慢了（滞后）点击「提前」；歌词快了（抢跑）点击「延后」。设置将按歌曲自动记忆保存。"

        override val miniMode = "迷你模式"
        override val miniModeRestore = "还原窗口"
        override val miniModePin = "始终置顶"
        override val miniModeUnpin = "取消置顶"

        override val exploreSimilar = "探索相似歌曲"
        override val exploreSimilarNote = "基于在播曲目发现更多同风格好音乐"
        override val immersiveLyricsOn = "沉浸歌词 (已开启)"
        override val immersiveLyricsOff = "沉浸歌词 (已关闭)"
        override val playerSettings = "播放界面设置"
        override fun lyricOffsetChipValue(label: String, seconds: String) = "$label ($seconds)"

        override val similarSongs = "相似歌曲"
        override fun similarCount(count: Int) = "$count 首相似好歌 · 点击即播"
        override val similarEmptyNote = "基于在播歌曲探索推荐"
        override val similarLoadFailed = "获取相似歌曲失败"
        override val similarUnsupported = "当前音源暂不支持相似歌曲"
        override val similarUnsupportedNote = "切换到支持该功能的音源后再试试"
        override val similarNothingPlaying = "没有正在播放的歌曲"
        override val similarNothingPlayingNote = "播放一首歌后再来看相似推荐"
        override val similarNoMatch = "没有找到相似歌曲"
        override val similarNoMatchNote = "换一首歌再试试"

        override val searchQueue = "搜索播放队列…"

        override val favoriteTrack = "收藏歌曲"
        override val unfavoriteTrack = "取消收藏"
        override val repeatOne = "单曲循环"
        override val repeatAll = "列表循环"
        override val repeatOff = "顺序播放"
        override val showLyrics = "显示歌词"
        override val showQueue = "显示队列"
        override val showComments = "显示评论"
        override val playMainView = "播放主界面"
        override val backToPlayer = "返回播放页"
        override val showLyricsTranslation = "显示歌词翻译"
        override val hideLyricsTranslation = "隐藏歌词翻译"
        override val lyricsPluginSettings = "歌词插件设置"
        override val playMv = "播放 MV"
        override val addToPlaylistMenu = "添加到歌单..."
        override val songDetailsMenu = "歌曲详情..."
        override val transferDeviceMenu = "设备接力 / 投送..."
        override val sleepTimerMenu = "定时关闭..."
        override val copyTrackInfo = "复制歌曲信息"
        override val switchToStandardPlayer = "切换到标准播放器"
        override val switchToThreePanePlayer = "切换到三段式播放器"
        override val collapsePlayer = "收起播放页"

        override val tabLyrics = "歌词"
        override val tabSong = "歌曲"
        override val tabQueue = "队列"
        override val tabComments = "评论"

        override fun switchToTab(tab: String) = "切换到$tab"
        override val lyricsTranslation = "歌词翻译"
        override val closeLyricsTranslation = "关闭歌词翻译"
        override val miniPlayer = "迷你播放器"
        override val restoreVolume = "恢复音量"
        override val maxVolume = "最大音量"

        override val commentsUnsupported = "当前音源暂不支持评论功能"
        override val exclusiveBadge = "WASAPI 独占 · Bit-Perfect"
    }

    override val library: LibraryStrings = object : LibraryStrings {
        override val playAll = "播放全部"
        override val addToQueue = "加入队列"
        override val downloadAll = "全部下载"
        override fun downloadAllMessage(name: String, count: Int) =
            "将把《$name》里的 $count 首歌全部加入下载队列。下载可以取消，文件也可以删除，确定继续吗？"
        override val sharePlaylist = "分享歌单"
        override val favoritePlaylist = "收藏歌单"
        override val unfavoritePlaylist = "取消收藏"
        override val deletePlaylist = "删除歌单"
        override val removeFromPlaylist = "从歌单移除"
        override val removeFromCloud = "从云盘删除"
        override val removeSelected = "移除"

        override fun removeTrackMessage(playlistName: String, trackName: String) =
            "确定从「$playlistName」移除歌曲「$trackName」吗？"
        override fun removeSelectedMessage(name: String, count: Int) =
            "确定从「$name」移除选中的 $count 首歌曲吗？"
        override fun deletePlaylistMessage(name: String) = "确定删除「$name」吗？删除后无法恢复。"
        override fun unfavoritePlaylistMessage(name: String) =
            "确定取消收藏「$name」吗？之后仍可重新收藏。"

        override val creatorOnlyHint = "仅歌单创建者可添加歌曲"
        override val favorited = "已收藏歌单"
        override val unfavorited = "已取消收藏"
        override val queuedToPlay = "已加入播放队列"
        override val playNext = "将在下一首播放"
        override val operationFailed = "操作失败"
        override val importFromPlaylist = "从歌单导入"

        override fun playlistRefreshed() = "已刷新"
        override val playlistDetailRefreshFailed = "歌单详情刷新失败"
        override val playlistDetailLoadFailed = "歌单详情加载失败"
        override val loadMoreFailed = "加载更多失败"
        override fun playlistDeleted(name: String) = "已删除「$name」"
        override fun playlistUnsubscribed(name: String) = "已取消收藏「$name」"
        override val ownerOnlyAddTracks = "只能向自己创建的歌单添加歌曲"
        override val tracksAlreadyInPlaylist = "所选歌曲均已在歌单中"
        override fun tracksAdded(count: Int) = "已添加 $count 首歌曲"
        override val addTracksFailed = "加入歌单失败"
        override fun tracksRemoved(count: Int) = "已移除 $count 首歌曲"
        override val playlistFavorited = "已收藏歌单"
        override val playlistUnfavorited = "已取消收藏歌单"
        override fun emptyPlaylist(name: String) = "「$name」里还没有歌"
        override fun playlistQueued(name: String) = "已把「$name」加入播放队列"
        override val localLibraryEmpty = "本地曲库暂无音乐，请先扫描或导入"
        override val personalFmUnavailable = "私人FM暂不可用，请稍后再试"
        override fun playlistSourceLabel(key: String) = when (key) {
            "Recommended" -> "推荐"
            "Hot" -> "热门"
            "Premium" -> "精品"
            else -> key
        }
        override fun newSongRegionLabel(key: String) = when (key) {
            "All" -> "全部"
            "Chinese" -> "华语"
            "Western" -> "欧美"
            "Japanese" -> "日本"
            "Korean" -> "韩国"
            else -> key
        }

        override val songDetails = "歌曲详情"
        override val paidType = "付费类型"
        override val paidFree = "免费"
        override val paidPurchased = "购买"
        override val paidLowQualityFree = "低音质免费"
        override val paidUnknown = "未知"
        override val publishDate = "发行时间"
        override val commentCount = "评论数"
        override val maxBitrate = "最高码率"
        override val releaseDateFormat = "yyyy-MM-dd"

        override fun shareTrack(name: String, artist: String, album: String, duration: String, extra: String) =
            "歌曲：$name\n歌手：$artist\n专辑：$album\n时长：$duration" + extra
        override fun songShareLine(label: String, value: String) = "\n$label：$value"

        override val sourceNowPlaying = "正在播放"
        override val loadTrackInfoFailed = "获取歌曲信息失败"

        override fun selectedCount(count: Int) = "已选 $count 首"
        override val exitSelection = "退出多选"
        override val selectAll = "全选"
        override val deselectAll = "取消全选"

        override val sortDefault = "默认顺序"
        override val sortByName = "按名称"
        override val sortByArtist = "按歌手"
        override val sort = "排序"
        override val moreOptions = "更多选项"
        override fun trackCountLabel(count: Int, duration: String) = "$count 首歌曲 • $duration"
        override fun playlistCountLabel(count: Int, duration: String) = "$count 首 • $duration"

        override val playlistEmpty = "歌单暂无歌曲"
        override val loadFailedRetry = "加载失败，点击重试"
        override val retry = "重试"

        override val tabPlaylists = "歌单"
        override val tabDownloads = "下载"
        override val myMusic = "我的音乐"
        override val gridView = "网格视图"
        override val listView = "列表视图"
        override fun greeting(name: String) = "你好，$name"
        override fun librarySubtitle(playlists: Int, liked: Int) = "共 $playlists 个歌单 · 收藏 $liked 首"
        override fun countPlaylist(n: String) = n to "歌单"
        override fun countLiked(n: String) = n to "收藏"
        override fun countDownload(n: String) = n to "下载"
        override val libraryTitle = "曲库"
        override fun libraryPlaylistsCount(n: Int) = "$n 个歌单"
        override val libraryOffline = "离线与本地内容"
        override val newPlaylist = "新建歌单"
        override val insights = "聆听统计"
        override val createPlaylist = "创建歌单"
        override val createPlaylistNote = "把喜欢的音乐整理成册"
        override val recentPlays = "最近播放"
        override val recentPlaysNote = "接着上次的节奏"
        override val offlineDownloads = "离线下载"
        override val offlineDownloadsNote = "管理离线内容"
        override val cloudDrive = "云盘"
        override val cloudDriveNote = "在线曲库"
        override val storageManage = "存储管理"
        override val storageManageNote = "缓存与日志"
        override val aboutCpPlayer = "关于 CPPlayer"
        override val preferences = "偏好设置"
        override val appearance = "外观"
        override val playback = "播放"
        override val providers = "音源"
        override val syncingLibrary = "正在同步媒体库"
        override val syncingLibraryNote = "正在加载你的歌单"
        override val libraryLoadFailed = "媒体库加载失败"
        override val noPlaylists = "这里还没有歌单"
        override val noPlaylistsNote = "登录账号后即可同步收藏与创建的歌单"
        override val myPlaylists = "我的歌单"
        override fun myPlaylistsCount(n: Int) = "$n 个歌单"
        override val seeAll = "查看全部"
        override val downloading = "下载中"
        override val completed = "已完成"
        override val localMedia = "本地媒体"
        override val noDownloads = "还没有下载任务。在歌曲菜单或歌单页点「下载」，任务会显示在这里。"
        override val openDownloads = "打开下载管理"

        override val searchLibrary = "搜索曲库"
        override val searchLibraryHint = "搜索我的歌单、歌单歌曲、本地歌曲"
        override val searchLibraryEmpty = "输入关键词开始搜索"
        override val searchLibraryEmptyNote = "可以搜索我的歌单、歌单内歌曲，以及已下载 / 导入的本地歌曲"
        override val searchPlaylistSection = "歌单"
        override val searchPlaylistTracksSection = "歌单内歌曲"
        override val searchPlaylistTracksLoading = "正在加载歌单歌曲"
        override val searchLocalSection = "本地音乐"
        override fun searchOnlineHint(query: String) = "在在线音乐中搜索「$query」"
        override val noSearchResults = "没有找到匹配内容"
        override val importPlaylist = "导入外部歌单"
        override val importShort = "导入"

        override val renamePlaylist = "重命名歌单"
        override val renamePlaylistPlaceholder = "输入新的歌单名称"
        override fun renamedPlaylist(name: String) = "已重命名为「$name」"
        override val renamePlaylistFailed = "重命名失败"

        override val play = "播放"
        override val shuffle = "随机"
        override val add = "添加"

        override val searchFieldHint = "搜索歌曲、歌手或专辑"
        override val trackSearchHint = "搜索歌曲、歌手、专辑..."
        override val trackSearchClear = "清除"
        override val trackSearchClose = "关闭搜索"
        override fun trackSearchResultCount(count: Int) = "$count 首"
        override val trackAdd = "添加"
    }

    override val album: AlbumStrings = object : AlbumStrings {
        override val fallbackTitle = "专辑"
        override val loadFailed = "加载专辑失败"
        override val loading = "正在载入专辑"
        override val loadingNote = "正在从当前音源读取专辑信息"
        override val notOpened = "没有打开这张专辑"
        override val notOpenedNote = "先在「专辑」页打开一张专辑"
        override val tracks = "曲目"
        override val noPlayableTracks = "这张专辑没有可播放的曲目"
        override val emptyTracks = "暂无曲目"
        override val emptyTracksNote = "换一张专辑，或在音源设置里换一个音源试试"
        override fun trackCount(n: Int) = "$n 首"
        override fun releasedYear(year: String) = "$year 年发行"
        override fun albumMeta(trackCount: Int, duration: String) = "$trackCount 首 · $duration"
        override val retry = "重试"
        override val shufflePlay = "打乱"
        override val liked = "已收藏"
        override val unliked = "已取消收藏"
        override val searchAlbum = "搜索专辑曲目…"

        override val favoriteAlbum = "收藏专辑"
        override val favoriteAlbumActive = "已收藏专辑"
        override fun favoriteAlbumDone(favorited: Boolean) =
            if (favorited) "已收藏专辑" else "已取消收藏专辑"
        override fun unfavoriteAlbumMessage(name: String) =
            "确定取消收藏「$name」吗？之后仍可重新收藏。"
        override val unfavoriteAlbumAction = "取消收藏"
        override val favoriteAlbumNeedLogin = "登录后才能收藏专辑"
        override val favoriteAlbumFailed = "收藏操作失败，请稍后再试"
    }

    override val playlistSheet: PlaylistSheetStrings = object : PlaylistSheetStrings {
        override val noPlaylists = "暂无可用歌单"
        override val addTracksTitle = "添加歌曲"
        override val importFrom = "从歌单导入"
        override val addFromQueue = "从播放队列添加"
        override val selectTracks = "选择歌曲"
        override fun fromSource(name: String) = "来自 $name"
        override val deselectAll = "取消全选"
        override val selectAll = "全选"
        override val noTracksFound = "未找到歌曲"
        override fun addCount(count: Int) = "添加 $count 首歌曲"
        override fun trackCount(n: Int) = "$n 首"
        override val playlistFallbackTitle = "歌单"

        override val addToPlaylistTitle = "添加到歌单"
        override fun addedTracks(count: Int) = "已添加 $count 首歌曲"
        override val addToPlaylistFailed = "加入歌单失败"
        override val createPlaylistFailed = "新建歌单失败"
        override val newPlaylistNamePlaceholder = "新建歌单名称"
        override val create = "创建歌单"
        override val createShort = "新建"
        override val creating = "创建中…"
        override val noPlaylistsHint = "还没有歌单，先在上方新建一个吧"

        override val createDialogTitle = "新建歌单"
        override val playlistNamePlaceholder = "歌单名称"
        override fun created(name: String) = "已创建「$name」"
        override val cancel = "取消"

        override val addToLocalPlaylistTitle = "添加到本地歌单"
        override val newLocalPlaylistPlaceholder = "新建本地歌单…"
        override val noCustomLocalPlaylists = "暂无自定义本地歌单，输入名称即可新建"
        override fun createdAndAddedTo(name: String) = "已创建并添加到「$name」"
        override fun addedToPlaylist(count: Int, name: String) =
            if (count > 0) "已将 $count 首歌曲添加到「$name」" else "歌曲已在「$name」中"
        override fun alreadyInPlaylist(name: String) = "歌曲已在「$name」中"

        override val saveDailyToPlaylist = "一键转歌单"
        override val saveDailyToPlaylistTitle = "一键转歌单"
        override val saveDailyToPlaylistConfirm = "转为歌单"
        override val saveDailyToPlaylistConverted = "已转歌单"
        override fun saveDailyToPlaylistNote(count: Int) = "将今日推荐的 $count 首歌曲转存为新歌单"
        override fun saveDailyToPlaylistSuccess(name: String, count: Int) = "已成功转为歌单「$name」（共 $count 首）"
        override val saveDailyToPlaylistAlreadySaved = "提示：今日推荐已转存过歌单，确认后将再次创建新歌单"
        override val saveDailyToPlaylistFailed = "转存歌单失败，请检查网络或登录状态"
        override fun saveDailyToPlaylistPartial(name: String) = "歌单「$name」已创建，但部分歌曲添加失败"
        override fun dailyRecommendationPlaylistName(date: String) = "每日推荐 · $date"
        override val artistFallback = "歌手"
    }
    override val playlistImport = PlaylistImportStringsZh
    override val account = AccountStringsZh
    override val downloads = DownloadStringsZh
    override val insights = InsightStringsZh
    override val wall = WallStringsZh
    override val social = SocialStringsZh
    override val lyricsPlugin = LyricsPluginStringsZh
    override val pluginRepository = PluginRepositoryStringsZh
    override val ai = AiStringsZh
    override val onboarding = OnboardingStringsZh
    override val providerManagement = ProviderManagementStringsZh
    override val contextMenu = ContextMenuStringsZh
    override val lyricPush = LyricPushStringsZh
    override val scrobble = ScrobbleStringsZh

    override val about: AboutStrings = object : AboutStrings {
        override val screenTitle = "关于与支持"
        override val appSubtitle = "Compose Multiplatform 音乐播放器"
        override val changelog = "更新日志"
        override val versionSection = "版本信息"
        override val commitSha = "提交哈希"
        override val checkUpdate = "检查更新"
        override val checkingUpdate = "正在检查..."
        override fun foundUpdate(v: String) = "发现新版本: v$v"
        override val alreadyLatest = "已是最新版本"
        override val projectSection = "项目信息"
        override val githubProject = "GitHub 项目"
        override val licensesAndThanks = "开源许可与致谢"
        override val licensesAndThanksSubtitle = "Apache-2.0 · 核心致谢 moss-apps/Flick 等开源项目"
        override val maintainerSection = "维护者"
        override val maintainerRole = "创建者 & 主要维护者"
        override val supportSection = "支持项目"
        override val supportProject = "支持本项目"
        override val supportProjectSubtitle = "在项目主页查看说明与支持方式"
        override val sponsorDeveloper = "赞助开发者"
        override val sponsorDeveloperSubtitle = "使用微信或支付宝赞助支持作者"
        override val sponsorDialogTitle = "赞助与支持"
        override val sponsorDialogSubtitle = "如果 CPPlayer 对你有帮助，欢迎赞助支持本项目的持续开发与维护！"
        override val wechatPay = "微信支付"
        override val alipay = "支付宝"
        override val sponsorAccount = "收款人: Aurora星空(*森)"
        override val sponsorThanks = "感谢每一位支持者的认可与鼓励 ❤️"
        override val saveQrCode = "保存二维码"
        override val savingQrCode = "保存中…"
        override val qrSavedToGallery = "已保存到相册"
        override fun qrSavedTo(path: String) = "已保存到 $path"
        override val qrSaveFailed = "保存失败，请检查存储权限或磁盘空间"
        override val close = "关闭"
        override val footerNote = "CPPlayer · Compose Multiplatform"
        override val legacyCleanupNone = "未发现符合条件的旧版 Provider V1/V3 模块目录，不会删除 SDK Provider 或账号数据。"
        override val legacyCleanupHint = "以下旧模块目录将在确认后排队，并仅在目标更新版本成功启动后删除；" +
            "如果安装被取消或失败，旧版本会保留这些目录："
        override val updateConfirmDownload = "确认并下载"
    }

    override val update: AppUpdateStrings = object : AppUpdateStrings {
        override val title = "发现新版本"
        override fun titleWithVersion(version: String) = "发现新版本 v$version"
        override val changelogTitle = "更新日志"
        override val defaultChangelog = "此版本包含常规体验优化、性能改进与问题修复。\n建议保持更新以获得最佳使用体验。"
        override val viewDiffOnGithub = "在 GitHub 上查看完整代码差异对比"
        override val downloadNow = "立即更新"
        override val releasePage = "查看详情"
        override val later = "稍后提醒"
        override val disableAutoUpdate = "关闭自动更新"
        override val installPackage = "选择安装类型:"
        override fun publishedAt(date: String) = "发布时间: $date"
        override val packageAndroid = "Android 安装包 (.apk)"
        override val packageWindows = "Windows 安装程序 (.exe)"
        override val packageWindowsPortable = "Windows 便携版 (ZIP)"
        override val packageMacos = "macOS DMG 镜像"
        override val packageLinuxDeb = "Linux Debian/Ubuntu (.deb)"
        override val packageLinuxTar = "Linux 通用包 (.tar.gz)"
        override val packageLinux = "Linux 软件包"
        override val packageUniversal = "跨平台软件包"
    }

    override val video: VideoStrings = object : VideoStrings {
        override val playbackFailed = "播放失败"
        override val retry = "重试"
        override val videoTitle = "视频播放"
        override val nowPlaying = "正在播放"
        override val resolvedQuality = "已解析画质"
        override val standardQuality = "标准"
        override val desktopPlayerHint = "建议使用系统播放器以启用完整硬件解码"
        override val openInSystemPlayer = "系统播放器打开 (PotPlayer/推荐)"
        override val copiedDirectLink = "已复制直链"
        override val copyDirectLink = "复制播放直链"
        override val playPause = "播放/暂停"
        override val quality = "清晰度"
        override val fullscreen = "全屏切换"
        override val scalingMode = "画幅模式"
        override val openExternally = "在系统播放器中打开"
    }

    override val home: HomeStrings = object : HomeStrings {
        override val title = "发现音乐"
        override val subtitle = "你的音乐，常听常新"
        override val posterWall = "唱片墙"

        override val dailyTitle = "每日推荐"
        override fun dailySubtitle(count: Int) = "为你精选 $count 首"
        override val intelligenceTitle = "心动模式"

        override val discoveryTitle = "发现歌单"
        override val rankingTitle = "排行榜"
        override val rankingSubtitle = "点击进入榜单曲目"
        override val newAlbumsTitle = "新碟上架"
        override val newAlbumsSubtitle = "点击查看专辑曲目"
        override val hotArtistsTitle = "热门歌手"
        override val hotArtistsSubtitle = "点击查看歌手曲目"
        override val newSongsTitle = "新歌速递"
        override val newSongsSubtitle = "按地区查看最新发布"

        override val recentTitle = "最近播放"
        override val recentSubtitle = "熟悉的旋律，一点继续"
        override val recentEmptySubtitle = "听过的歌会出现在这里"
        override val continueListening = "继续收听"
        override val continueListeningSubtitle = "接着上次继续听"

        override val playlistSourceRecommended = "推荐"
        override val playlistSourceRecommendedHint = "根据近期收听持续更新"
        override val playlistSourceHot = "热门"
        override val playlistSourceHotHint = "大家正在收藏的歌单"
        override val playlistSourcePremium = "精选"
        override val playlistSourcePremiumHint = "编辑精选的高质量歌单"

        override val quickEntry = "快捷入口"
        override val quickRadio = "快捷电台"
        override val localShuffle = "本地随机"
        override val localShuffleNote = "随机畅听本地音乐"
        override val personalFm = "私人 FM"
        override val personalFmNote = "连续播放，不用挑歌"
        override val fmRecommended = "推荐"
        override val fmExplore = "探索"
        override val fmFamiliar = "熟悉"
        override val fmPuzzle = "拼图"
        override val fmGuideTitle = "随播放自动更新列表"
        override val fmGuideBody = "点击任意歌曲即可开启当前频道的私人 FM 连续播放，播至队尾前会自动拉取并追加新曲目；也可以随时切换上方频道或手动刷新。"
        override fun fmLiveStatus(count: Int) = "同步中 · $count 首"
        override fun fmReadyStatus(count: Int) = "当前 $count 首"
        override val localLibrary = "本地曲库"
        override val localLibraryNote = "浏览设备音乐文件"
        override val intelligenceNote = "围绕今日推荐延展"
        override val intelligenceNeedsDaily = "需要先有每日推荐"
        override val recentNote = "接着上次继续听"

        override val nowPlaying = "正在播放"
        override val paused = "已暂停"

        override val loadIncompleteTitle = "推荐内容未完全加载"
        override val loadFailed = "加载失败"
        override val localModeTitle = "本地音乐模式"
        override val localModeNoteDesktop =
            "当前未导入在线音源插件。点击上方「本地随机」或「本地曲库」可直接播放或扫描本地设备中的音乐。"
        override val localModeNoteMobile =
            "当前未导入在线音源插件。点击上方「本地随机」或「本地曲库」可直接播放或管理本地设备中的音乐。"
        override val openLocalLibrary = "打开本地曲库"
        override val noPersonalizedTitle = "还没有个性化推荐"
        override val noPersonalizedNote = "登录账号后即可同步每日歌曲与歌单"

        override fun trackCount(count: Int) = "$count 首"
        override val rankingFallback = "榜单"
        override val viewTracks = "查看曲目"
        override val unknownArtist = "未知歌手"

        override val recommendedPlaylistFallback = "推荐歌单"
        override val rankingCreator = "榜单"
    }

    override val engine: EngineStrings = object : EngineStrings {
        override val titleAndroid = "播放内核与发烧特性"
        override val titleDesktop = "音频输出与硬件独占"

        override val heroExclusive = "WASAPI Exclusive · Bit-Perfect 源码直通 · 独占声卡"
        override val heroSharedWasapi = "WASAPI Shared · 经系统混音器输出 · 与其他应用共存"
        override val heroSharedSystem = "系统共享输出 · 经系统混音器混音 · 与其他应用共存"
        override val heroAndroidFlick = "Rust Oboe · 低延迟直通 · USB DAC · DSD 软解"
        override val heroAndroidStandard = "系统默认内核 · 网络流兼容性最好 · 功耗最省"

        override val badgeExclusive = "独占中"
        override val badgeSharedDefault = "共享 · 默认"
        override val badgeVip = "VIP"
        override val badgeDefault = "默认"

        override val sectionKernel = "播放内核"
        override val kernelCurrent = "当前生效内核"
        override val kernelNeedsVip = "卡密未激活：已自动回落到系统内核（FlickEngine 需 VIP）"
        override val kernelEnabled = "已启用 FlickEngine，当前歌曲在下次起播时切过去"
        override val kernelSystemNote = "系统 Media3 内核：兼容性与耗电表现更稳，发烧特性不可用"
        override val kernelSystemOption = "系统 Media3（默认）"
        override val kernelFlickOption = "FlickEngine（VIP）"
        override val kernelSwitchNote = "切换内核不会打断正在播放的音频：下一次起播（切歌或重新播放）时生效。"

        override val sectionOutputMode = "输出模式"
        override val wasapiMode = "WASAPI 输出模式"
        override val wasapiExclusiveNote = "已接管声卡：播放期间系统提示音、浏览器、会议软件都会静音"
        override val wasapiSharedNote = "未接管声卡：可与其它应用同时出声，代价是经过系统混音器"
        override val wasapiSharedOption = "共享（默认）"
        override val wasapiExclusiveOption = "独占（VIP）"
        override val sharedOutput = "共享输出"
        override val sharedOutputNote = "当前平台没有 WASAPI 独占通道，音频始终经系统混音器输出"
        override val ready = "已就绪"
        override val exclusiveWarning =
            "独占模式默认关闭，需要 VIP 授权且由你手动打开。开启后 CPPlayer 会接管声卡终端，" +
                "直到切回共享模式或退出应用为止 —— 这是 Bit-Perfect 的必要代价，不是故障。" +
                "切换会重新打开音频设备，当前歌曲会停止，重新点播放即可。"

        override val sectionCapabilities = "FlickEngine 发烧特性"
        override val oboeTitle = "Oboe / AAudio 低延迟直通"
        override val oboeNote = "绕过 AudioFlinger 重采样，直接与底层音频 HAL 或 USB DAC 握手"
        override val usbDacTitle = "USB DAC 直通 (UAC 2.0)"
        override val usbDacNote = "外接解码器按自身支持的采样率与位深直接输出，不经过系统重采样"
        override val dsdTitle = "DSD 母带软解"
        override val dsdNoteAndroid = "引擎内置 DSF / DFF 解码，DSD64 / DSD128 直接播放（软解为 PCM）"
        override val dsdNoteDesktop =
            "引擎内置 DSF / DFF 解码，DSD64 / DSD128 直接播放（软解为 PCM），两种模式均可用"
        override val wasapiExclusiveTitle = "WASAPI 独占直通"
        override val wasapiExclusiveNoteWindows = "绕过 Windows 会话混音器与系统音效增强，由引擎直接接管声卡终端"
        override val wasapiExclusiveNoteOther = "仅 Windows 提供 WASAPI 独占通道，当前平台始终为共享输出"
        override val bitPerfectTitle = "Bit-Perfect 源码输出"
        override val bitPerfectNoteActive =
            "采样率按音轨物理规格重协商（44.1k / 48k / 96k / 192k），不经系统重采样"
        override val bitPerfectNoteOther =
            "需要能接管声卡的独占通道，仅 Windows 提供；当前平台始终经系统混音器重采样"
        override val bitPerfectNoteInactive =
            "仅在独占模式下成立；共享模式会被系统混音器重采样到设备默认采样率"
        override val dspTitle = "64 位浮点 DSP 管线"
        override val dspNote = "5 段参数均衡器 (PEQ)、左右声道平衡与立体声宽度，跑在引擎内而非系统音效链"

        override val sectionToggles = "硬件输出与解码选项"
        override val androidUsbExclusiveTitle = "USB DAC 硬件独占输出 (UAC 2.0)"
        override val androidUsbExclusiveNote = "默认使用系统 AAudio 共享输出；开启后仅在实际播放期间接管 USB 声卡，暂停、停止或退出时立即释放。"
        override val androidUsbAutoResumeTitle = "USB 断开 3 秒内重连自动恢复播放"
        override val androidUsbAutoResumeNote = "USB 耳机或解码器意外断开后若在 3 秒内重新连接，自动继续播放；对 Media3 与 FlickEngine 均生效。"
        override val dsdModeTitle = "DSD 输出模式"
        override val dsdModePcm = "PCM 抽取"
        override val dsdModeDop = "DoP"
        override val dsdModeNative = "Native DSD"
        override val dsdModeAuto = "自动"
        override val togglesNote = "DSD 输出模式仅对 DSD 音源生效（PCM 软解适合所有设备，DoP / Native 需外接解码器支持）。"

        override val badgeActive = "生效中"
        override val badgeNeedsFlick = "需 FlickEngine"
        override val badgeAvailable = "可用"
        override val badgeUnsupported = "当前平台不支持"
        override val badgeNeedsVip = "需 VIP"
        override val badgeNeedsExclusive = "需独占"
        override val badgeOff = "未开启"

        override val sectionEffects = "调音台与音效"
        override val openEffects = "前往音效设置"
        override val effectsNoteAndroid = "5 段参数均衡器、风格预设、声道平衡与响度均衡"
        override val effectsNoteDesktop = "5 段参数均衡器、风格预设、声道平衡与立体声宽度"

        override val sectionCredits = "开源内核致谢与归属"
        override val creditsNote = "FlickEngine 核心技术源自开源项目 moss-apps/Flick (MIT)，特此致敬原作者"
    }

    override val health: HealthStrings = object : HealthStrings {
        override val screenTitle = "诊断与健康"
        override val clearLogAction = "清空日志"
        override val clearTitle = "清空诊断记录"
        override fun clearMessage(count: Int) = "确定清空全部 $count 条调用记录吗？"
        override val clearConfirm = "清空"

        override val tabOverview = "概览与自检"
        override val tabProbe = "接口测试"
        override val tabMethods = "操作统计"
        override val tabLogs = "调用日志"
        override val tabStatus = "Provider 状态"

        override val diagnosticTitle = "一键系统诊断与体检"
        override val diagnosticSubtitle = "主动测试网络连通性、音源取数、播放引擎、缓存及系统环境"
        override val runDiagnostics = "开始全面体检"
        override val reRunDiagnostics = "重新全面体检"
        override val runningDiagnostics = "正在进行系统体检..."
        override val diagnosticHealthy = "系统运行健康，各项指标良好"
        override val diagnosticWarning = "系统存在部分警告，部分功能可能受限"
        override val diagnosticError = "系统发现异常故障，核心功能受影响"
        override val diagnosticScoreSummary = "体检综合评分"
        override val diagnosticItemPassed = "正常"
        override val diagnosticItemWarning = "警告"
        override val diagnosticItemError = "异常"
        override val diagnosticItemSkipped = "跳过"
        override val diagnosticNotRun = "尚未执行主动诊断"
        override val diagnosticNotRunHint = "点击「开始全面体检」即可主动验证网络连通性、音源接口、播放引擎与缓存状态"
        override val diagnosticDetailsLabel = "详细信息"
        override val diagnosticSuggestionLabel = "排查建议"

        override val checkNetwork = "公网与 API 连通性"
        override val checkProvider = "生效音源模块状态"
        override val checkSearch = "歌曲搜索接口探测"
        override val checkStreamUrl = "播放流直链解析探测"
        override val checkLyric = "歌词提取解析探测"
        override val checkDiscovery = "排行榜与推荐探测"
        override val checkAudioEngine = "音频输出与播放引擎"
        override val checkStorageCache = "本地存储与流缓存"
        override val checkSystemMemory = "运行时内存与环境"

        override val toolkitTitle = "排障工具箱"
        override val copyReport = "复制完整诊断报告"
        override val copyReportDone = "诊断报告已复制到剪贴板"
        override val reloadActiveProvider = "重新初始化音源"
        override val reloadProviderDone = "音源已重新初始化"
        override val clearStreamCacheAction = "清理流缓存"
        override val clearStreamCacheTitle = "清理音频流缓存"
        override val clearStreamCacheMessage = "确定清空所有本地临时音频缓存吗？已下载的歌曲不会受到影响。"
        override val clearStreamCacheDone = "已清空流缓存"
        override val clearImageCacheAction = "清理图片缓存"
        override val clearImageCacheTitle = "清理封面与图片缓存"
        override val clearImageCacheMessage = "确定清空所有本地缓存的专辑封面与图片吗？已下载歌曲不受影响。"
        override val clearImageCacheDone = "已清空图片缓存"
        override val resetAudioEngineAction = "重置播放引擎"
        override val resetAudioEngineDone = "播放引擎已重置"

        override val probeTitle = "音源接口主动测试"
        override val probeSubtitle = "无需切歌或跳转，即时验证各取数接口的连通性、响应耗时与数据结构"
        override val probeTargetProvider = "目标音源"
        override val probeSwitchProvider = "切换生效音源"
        override val probeSwitchDone = "已切换当前测试音源"
        override val probeOperation = "测试接口"
        override val probeOpSearch = "搜索歌曲"
        override val probeOpDetail = "单曲详情"
        override val probeOpStream = "播放直链"
        override val probeOpLyric = "歌词解析"
        override val probeOpRankings = "热门排行榜"
        override val probeOpPlaylists = "推荐歌单"
        override val probeInputKeywordLabel = "搜索关键词"
        override val probeInputMediaIdLabel = "歌曲 MediaId 或纯数字 ID"
        override val probeQualityLabel = "音质档位"
        override val probeExecute = "执行测试"
        override val probeExecuting = "请求中..."
        override val probeResultLabel = "测试响应结果"
        override val probeDurationLabel = "请求耗时"
        override val probeSuccess = "接口调用成功"
        override val probeUnsupported = "音源不支持该接口"
        override val probeFailed = "接口调用失败"
        override val probeEmptyPrompt = "选择接口并点击「执行测试」查看即时响应与返回数据"
        override val probeSearchQuickStream = "测试直链"
        override val probeSearchQuickLyric = "测试歌词"
        override val probeCopyResult = "复制结果"
        override val probeResultCopied = "测试结果已复制到剪贴板"

        override val filterAll = "全部"
        override val filterErrors = "仅错误"
        override val filterWarnings = "仅警告"
        override val filterSuccess = "仅正常"


        override val providerHealthSection = "音源健康评分与指标"
        override val noLoadedProviders = "暂无已加载音源"
        override val systemStatus = "系统综合运行状态"
        override fun callsSummary(totalCalls: Int, avgResponseMs: Long) =
            "共 $totalCalls 次调用 · 平均响应 ${avgResponseMs}ms"
        override val levelOk = "健康"
        override val levelWarning = "警告"
        override val levelError = "异常"
        override val success = "成功"
        override val failure = "失败"
        override val unsupported = "不支持"
        override val activeNow = "当前生效"
        override fun providerMeta(id: String, type: String, version: String) =
            "ID: $id · 类型: $type · 版本: $version"
        override fun score(value: Int) = "$value 分"
        override fun loadError(error: String) = "加载异常: $error"
        override val noCalls = "暂无调用记录"
        override val successRate = "成功率"
        override val metricCalls = "调用"
        override val metricAverage = "平均"
        override val metricWarning = "警告"
        override fun recentError(error: String) = "最近错误: $error"
        override val diagnostics = "底层诊断参数"

        override val noOperationStats = "暂无操作统计数据"
        override val operationStatsHeader = "取数操作调用性能（按 UnifiedMusicSource 操作名）"
        override fun operationSummary(totalCalls: Int, successRate: Int, avgResponseMs: Long) =
            "$totalCalls 次 · 成功率 $successRate% · 平均 ${avgResponseMs}ms"
        override fun failCount(count: Int) = "失败 $count"
        override fun warningCount(count: Int) = "警告 $count"
        override val recentSamples = "最近调用样本"

        override fun history(count: Int) = "调用历史（最近 $count 条）"
        override val resultPrefix = "结果: "
        override val resultSuccess = "成功"
        override val resultUnsupported = "音源不支持该操作"
        override val resultFailed = "调用失败"
        override fun errorMessage(message: String) = "错误信息: $message"

        override val providerListSection = "已注册与已加载音源清单"
        override val noRegisteredProviders = "暂无已注册音源"
        override val switchToProvider = "切换至此音源"
        override fun providerTypeVersion(type: String, version: String, apiVersion: Int) =
            "类型: $type  ·  版本: $version  ·  API 版本: v$apiVersion"
        override fun apiGeneration(tag: String) = "接口代际: $tag"
        override fun startupFailed(error: String) = "启动/加载失败: $error"
        override val runtimeDiagnostics = "运行时底层诊断"
    }

}
