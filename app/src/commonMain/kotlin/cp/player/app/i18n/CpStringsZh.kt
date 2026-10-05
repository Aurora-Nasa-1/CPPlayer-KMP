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
}
