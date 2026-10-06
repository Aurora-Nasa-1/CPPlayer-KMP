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
        override val groupGeneral = "通用"
        override val groupAccountProvider = "账号与音源"
        override val groupConnectivity = "连接与集成"
        override val groupOther = "其他"

        override val itemLanguage = CpTextPair(
            title = "语言",
            subtitle = "跟随系统，或固定为简体中文 / English", // 混排 English 的理由见 optionEnglish
        )
        override val itemAppearance = CpTextPair(
            title = "外观与主题",
            subtitle = "主题模式、取色来源与纯黑背景",
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
        override val itemStreamOutput = CpTextPair(
            title = "本地流输出",
            subtitle = "把音频流通过 HTTP 对外提供",
        )
        override val itemIntegration = CpTextPair(
            title = "外部推送与集成",
            subtitle = "推送到接收端、开放第三方接口",
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
        override val apply = "应用"
        override val unsavedBlocked = "未保存 · 请先修正上面的问题"
        override val unsavedHint = "未保存 · 点「应用」生效"
    }

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
        override val settingsSubtitle = "哪些联系人发来的新消息要弹系统通知"
        override val masterLabel = "允许私信通知"
        override val masterHint = "关闭时不会做任何后台请求。仅在应用运行时有效"
        override val subscribedSection = "已开启的联系人"
        override val subscribedEmpty = "还没有开启任何联系人"
        override val unsupportedPlatform = "本平台不支持系统通知"
        override val permissionMissing = "系统通知权限未开启，收不到提醒"
        override val grantPermission = "去授权"

        override val trayShowWindow = "显示主界面"
        override val trayExit = "退出 CPPlayer"

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
        override val screenTitle = "外观与主题"
        override val sectionLook = "外观"
        override val sectionFont = "字体"

        override val themeMode = "主题模式"
        override val themeModeSystem = "跟随系统"
        override val themeModeLight = "浅色"
        override val themeModeDark = "深色"

        override val colorSource = "取色来源"
        override val colorSourcePlatform = "跟随系统"
        override val colorSourceCover = "跟随封面"
        override val colorSourceFixed = "固定配色"
        override val colorSourcePlatformOn = "取自系统强调色（安卓壁纸 / Windows 强调色）"
        override val colorSourcePlatformOff = "当前平台不支持，将回退到固定配色"
        override val colorSourceCoverNote = "取自当前曲目封面主色；未播放或无封面时改用系统壁纸配色"
        override val colorSourceFixedNote = "始终使用内置配色，不随内容与系统变化"

        override val pureBlack = "纯黑模式"
        override val pureBlackNote = "深色主题下使用纯黑背景，OLED 屏幕更省电"
        override val autoHideBottomBar = "自动隐藏底栏"
        override val autoHideBottomBarNote = "向上滑动内容时收起底部导航栏，向下滑动重新显示"
        override val coverFlight = "封面飞行动画"
        override val coverFlightNote = "点击歌曲 / 歌单封面时，播放封面飞向播放器或详情页的过渡动画；" +
            "关闭后点击更干脆利落"

        override val fontRoundness = "字体圆滑度"
        override fun fontRoundnessNote(defaultRoundness: Int) =
            "Google Sans Flex 的 ROND 可变轴：0 方正、100 最圆润。" +
                "Android 16 及以上默认 100，其余平台默认 $defaultRoundness"
        override val roundnessDefaultTag = " · 默认"
        override val resetPlatformDefault = "恢复平台默认"
        override val resetPlatformDefaultNote = "清除自定义值，回到当前平台的默认圆滑度"
        override val roundnessNote = "字体圆滑度改动即时生效，会应用到整个界面的拉丁字符；" +
            "中文字形来自系统回退字体，不受此设置影响。"
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
        override val cachedSongsEmpty = "暂无缓存；播放无损音质的歌曲时会自动缓存"
        override fun cachedSongsSummary(count: Int, bytes: String, capacity: String) =
            "$count 首 · 共 $bytes / 上限 $capacity"
        override val cacheCapacity = "容量上限"
        override val cacheCapacityNote = "上限调小后会立刻按最久未播放清理到位"
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

        override val sectionApiCache = "接口缓存"
        override val apiCacheEntries = "缓存条目"
        override val apiCacheEmpty = "暂无缓存"
        override fun apiCacheEntryCount(count: Int) = "$count 条"
        override fun apiCacheHitRate(rate: Int) = " · 本次会话命中率 $rate%"
        override val clearApiCache = "清理接口缓存"
        override val clearApiCacheNote = "歌曲、歌单等信息的读取缓存；清理后下次会重新向音源请求"

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
        override val unsupported = "当前平台不使用磁盘歌曲缓存。无损流落地只在桌面端启用：" +
            "桌面引擎无法定位网络 FLAC，必须先落盘才能拖动进度。"
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
        override val defaultQualityNote = "WiFi / 非计费网络下在线播放优先请求的音质；音源不提供时自动降级"
        override val meteredQuality = "移动数据音质"
        override val meteredQualityNote = "蜂窝数据 / 热点下生效；切换网络后对下一首播放的曲目生效"

        override val sectionLyrics = "歌词"
        override val lyricsSource = "歌词来源"
        override val lyricsSourceNote = "AMLL 为逐词歌词库（翻译/罗马音更全）；" +
            "AMLL 优先时无匹配自动回退音源歌词，对下次刷新生效"
        override val lyricsProviderOnly = "仅音源 API"
        override val lyricsAmllFirst = "AMLL 优先"
        override val lyricsAmllOnly = "仅 AMLL"

        override val sectionSleepTimer = "睡眠定时"
        override val sleepTimer = "定时关闭"
        override val sleepAfterTrack = "播完当前歌曲后暂停"
        override fun sleepRemaining(minutes: Long) = "剩余 $minutes 分钟"
        override val sleepOff = "未启用"

        override val sectionLastPlayback = "上次播放"
        override val keepLastPlayback = "保留上次播放"
        override val keepLastPlaybackNote =
            "启动时恢复上次的播放队列与进度，但不自动播放；点播放才从上次位置接着听"

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
    }

    override val streamOutput: StreamOutputStrings = object : StreamOutputStrings {
        override val screenTitle = "本地流输出"

        override val sectionMain = "本地流输出"
        override val enabled = "启用本地流输出"
        override val enabledNote = "把当前曲目以 HTTP 流对外提供，供接收端按曲目拉取"
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

        override val sectionPort = "端口"
        override val port = "流输出端口"
        override val portNote = "接收端按曲目拉流时访问的端口；改动即时生效"
        override val portRestartNote = "端口改动会重启监听，正在进行的拉流会短暂中断。"
        override fun portEmpty() = "端口不能为空"
        override fun portNotNumber() = "端口必须是数字"
        override fun portOutOfRange(from: Int, to: Int) = "端口需在 $from–$to 之间"

        override val sectionToken = "访问令牌"
        override val regenerateToken = "重新生成访问令牌"
        override val regenerateTokenNote = "所有已连接的设备将立即失效"
        override val regenerateConfirmTitle = "重新生成访问令牌？"
        override val regenerateConfirmMessage = "旧令牌会立即作废。所有已连接的第三方软件与接收端都需要改用新令牌，" +
            "否则会收到 401。"
        override val regenerateLabel = "重新生成"
        override val tokenRegenerated = "访问令牌已重新生成，已连接的设备需重新配置"
        override val tokenStorageNote = "令牌明文保存在 ~/.cpplayer/integration.json，请勿外传。"
        override val currentToken = "当前令牌"
        override val tokenAutoGenerated = "启用后自动生成"

        override val sectionEndpoint = "接收端拉流的地址"
        override val endpointNote = "按曲目拉流时接收端会带上 ?mediaId=…，CPPlayer 据此解析并转发字节。"
        override val statusDisabled = "服务未启用。"
        override fun statusStartFailed(error: String) = "启动失败：$error"
        override fun statusRunning(url: String) = "运行中 · $url"
        override val statusStarting = "启动中…"
    }

    override val integration: IntegrationStrings = object : IntegrationStrings {
        override val screenTitle = "外部推送与集成"

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

        override val sectionThirdParty = "允许第三方访问"
        override val allowStream = "允许第三方拉取音频流"
        override val allowStreamNote = "接收端按曲目拉流所需的 GET /stream"
        override val allowApi = "允许第三方读取音源数据"
        override val allowApiNote = "搜索、曲目与播放状态（/api/v1/…）；默认关闭，因为会扩大暴露面"
        override val apiSwitchNote = "关闭「读取音源数据」后数据接口整体返回 403，" +
            "但不影响接收端拉流。两个开关都即时生效。"

        override val sectionEndpoint = "接口地址"
        override val configFileNote = "第三方软件读 ~/.cpplayer/integration.json 即可拿到地址与令牌；" +
            "该文件含明文令牌，请勿外传。"
        override val streamDisabledNote = "本地流输出当前未启用，上面的地址暂时不可达。" +
            "请先到「本地流输出」页开启。"

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
        override val syncWarning = "⚠️ 开启后，同一局域网内的任何设备都能读取与写入本机的「听歌记录」。" +
            "能同步的仅此一项 —— 不含账号、凭据、歌单、收藏。" +
            "在办公室等非私人网络请关闭。设备配对鉴权是下一步的工作。"
        override val syncEnabledNote = "默认关闭。开启后无需任何手动操作：两台设备只要都在同一网络并打开 CPPlayer，" +
            "听歌记录就会自动双向合并 —— 不分谁新谁旧，也不在乎交替使用。"

        override val sectionKeepAlive = "后台在线"
        override val aggressiveStandby = "激进保活"
        override val aggressiveStandbyNote = "熄屏后维持 Wi-Fi 在线，让设备发现与换设备播放仍可能命中"
        override val notEnabled = "未启用"
        override val inEffect = "已生效"
        override val notInEffect = "未生效（可能被系统拒绝）"
        override val keepAliveHint = "。没有它，熄屏后系统可能丢弃组播包。"

        override val sectionLanDevices = "局域网设备"
        override val discovering = "正在监听，还没有发现其他设备。只要对方也在运行 CPPlayer" +
            "（不必停留在任何页面），最多半分钟就会出现在这里。"
        override val discoveryNotStarted = "设备发现未启动。"
        override val onlineDevices = "在线设备"
        override fun onlineCount(count: Int) = "$count 台"
        override val sectionOnline = "在线 —— 点击把当前播放转移过去"
        override val localNotPlaying = " · 本机未在播放"
        override val sectionOffline = "已离线"
        override fun justOnline(address: String, port: Int) = "$address:$port · 刚才还在"
        override val troubleshooting = "搜不到设备时按顺序检查：① 两台设备都要在运行较新版本的 CPPlayer" +
            "（旧版本没有设备发现，对方发了信标这边也认不出）；" +
            "② 「在局域网中可见」都开着，且上面的「已收到信标」在增长 —— 若一直是 0，" +
            "是本机收不到包：查防火墙入站规则（Windows 首次监听会弹授权，拒绝过就再也收不到）；" +
            "③ 同一路由器下的同一网段（访客网络 / AP 隔离会把设备互相隔离）；" +
            "④ 若「已收到信标」> 0 但列表仍为空且「无法识别的信标」在涨，说明对端不是同版本的应用；" +
            "⑤ 多网卡机器（VPN、虚拟机网卡）可能需要多试几次。"
        override val handoffPrereq = "无缝转移的前提：对方也开着「自动同步」（同步服务在监听），" +
            "并且已登录「同一音源」 —— 转移的只是「放哪首、从哪秒开始」，" +
            "两端各自从自己的音源取播放地址。任一条件不满足会得到明确的失败提示，" +
            "本机继续播放、不会静音。"
        override val handoffSecurityNote = "仍需配对鉴权：转移与同步目前都未认证，端口只应出现在私人网络。" +
            "配对（PIN / 二维码）是下一步的工作。"
        override val keepAliveReality = "它「不能」让本应用免于被系统回收。系统按进程优先级淘汰后台进程，" +
            "与进程大小、用什么语言实现无关。真正可靠的常驻只有前台服务，" +
            "所以本开关只是把「系统愿意留你多久」往有利方向推。"
        override val keepAliveRecommend = "建议同时打开「播放与音质 → 电池优化白名单」，两者配合才能挡住厂商 ROM 的后台清理。"

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

        override val collapse = "收起"
        override val translation = "翻译"
        override val queue = "队列"
        override val repeat = "循环"
        override val shuffle = "随机播放"
        override val like = "点赞"
        override val unlike = "取消点赞"
        override fun sleepRemainingLabel(remaining: String) = "剩余 $remaining"
        override val sleepEndsWithTrack = "本曲结束"

        override fun queueCount(size: Int) = "$size 首 · 接下来播放"
        override val queueUpNext = "接下来播放"
        override val queueSaved = "队列已保存为歌单"
        override val queueSaveFailed = "保存歌单失败"
        override val queueClearTitle = "清空播放队列"
        override fun queueClearMessage(size: Int) = "确定清空全部 $size 首队列歌曲吗？"
        override val queueEmptyLabel = "队列"
        override val queueReorderHint = "长按拖拽重排"
        override val coverContentDescription = "封面"

        override val previousTrack = "上一首"
        override val nextTrack = "下一首"
        override fun playOrPause(playing: Boolean) = if (playing) "暂停" else "播放"

        override val commentsLoadFailed = "加载评论失败"
        override val retry = "重试"
        override val noComments = "暂无评论"
    }

    override val library: LibraryStrings = object : LibraryStrings {
        override val playAll = "播放全部"
        override val addToQueue = "加入队列"
        override val downloadAll = "全部下载"
        override val sharePlaylist = "分享歌单"
        override val favoritePlaylist = "收藏歌单"
        override val unfavoritePlaylist = "取消收藏"
        override val deletePlaylist = "删除歌单"
        override val removeFromPlaylist = "从歌单移除"
        override val removeFromCloud = "从云盘删除"
        override val removeSelected = "移除"

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

        override val play = "播放"
        override val shuffle = "随机"
        override val add = "添加"
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
    }
    override val account = AccountStringsZh
    override val downloads = DownloadStringsZh
    override val insights = InsightStringsZh
    override val social = SocialStringsZh

}
