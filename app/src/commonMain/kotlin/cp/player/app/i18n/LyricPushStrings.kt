package cp.player.app.i18n

/**
 * 「歌词投放」设置页的文案。
 *
 * 单独成组而不是并进 `SettingsStrings`：这组文案里**渠道名（词幕 / SuperLyric / 超级岛）
 * 是专有名词**，中英文都保持原文，与设置组里那批「一看就该翻译」的词不是一类。
 * 混在一起后，改文案的人会下意识把「词幕」翻成 "Lyricon"，而那是两个不同的东西。
 */
interface LyricPushStrings {
    // —— 设置入口 ——
    val entryTitle: String
    val entrySubtitle: String

    // —— 页面 ——
    val screenTitle: String
    val note: String
    val androidOnlyNote: String

    // —— 分组标题 ——
    val sectionChannels: String
    val sectionSecondary: String
    val sectionColorOs: String
    val sectionSuperIsland: String
    val sectionLiveUpdate: String

    // —— 渠道：词幕 Lyricon ——
    val lyricon: String
    val lyriconNote: String

    // —— 渠道：SuperLyric ——
    val superLyric: String
    val superLyricNote: String

    // —— 渠道：Lyric Getter ——
    val lyricGetter: String
    val lyricGetterNote: String

    // —— 渠道：ColorOS 锁屏岛 ——
    val colorOs: String
    val colorOsNote: String
    val colorOsMode: String
    val colorOsModeSystem: String
    val colorOsModeModule: String
    val colorOsModeSystemNote: String
    val colorOsModeModuleNote: String

    // —— 渠道：HyperOS 超级岛 ——
    val superIsland: String
    val superIslandNote: String
    val superIslandUnsupported: String

    // —— 渠道：状态栏歌词 / 浮动通知 ——
    val statusBar: String
    val statusBarNote: String
    val headsUp: String
    val headsUpNote: String

    // —— 渠道：实时活动 ——
    val liveUpdate: String
    val liveUpdateNote: String
    val liveUpdateContent: String
    val liveUpdateDisplay: String
    val liveUpdateDisplayWord: String
    val liveUpdateDisplayFull: String
    val liveUpdateSecondary: String
    val liveUpdateSecondarySong: String

    // —— 渠道：媒体通知歌词 ——
    val mediaNotification: String
    val mediaNotificationNote: String

    // —— 副行内容 ——
    val secondaryOff: String
    val secondaryTranslation: String
    val secondaryPronunciation: String

    // —— 内容选择（超级岛） ——
    val contentOriginal: String
    val contentTranslation: String
    val contentPronunciation: String

    // —— 超级岛外观 ——
    val islandContent: String
    val islandLayout: String
    val islandLayoutStandard: String
    val islandLayoutFull: String
    val islandShowCover: String
    val islandShowCoverNote: String
    val islandScrolling: String
    val islandScrollingNote: String
    val islandRightChars: String
    val islandLeftCoverChars: String
    val islandLeftChars: String

    /** 例：「7 个字」。 */
    fun islandCharsValue(chars: Int): String

    val islandTextColor: String
    val islandTextColorNote: String
    val islandColorSource: String
    val islandColorSourceAlbum: String
    val islandColorSourceCustom: String
    val islandCustomColor: String
    val islandProgressColor: String
    val islandProgressColorNote: String
    val islandDismissDelay: String
    val islandDismissImmediately: String

    /** 例：「3 秒后收起」。 */
    fun islandDismissAfter(seconds: Int): String
}

/**
 * 简体中文。
 *
 * 渠道名（词幕 / SuperLyric / ColorOS / HyperOS）**保持厂商原文**：它们是产品名，
 * 翻译之后用户在设置里对着「词幕」找不到东西（他在手机上看到的就是这两个字）。
 */
object LyricPushStringsZh : LyricPushStrings {

    override val entryTitle = "歌词投放"
    override val entrySubtitle = "把歌词推送到词幕、超级岛、状态栏、锁屏与车机"

    override val screenTitle = "歌词投放"
    override val note = "允许 CPPlayer 把「正在听什么、听到哪一句」交给系统或第三方歌词应用显示。" +
        "全部默认关闭 —— 打开哪个，才往哪个方向写数据。"
    override val androidOnlyNote = "这些渠道都需要通知权限；词幕 / SuperLyric / Lyric Getter 还需要" +
        "对应应用已安装并启用，否则开关打开也不会有效果。"

    override val sectionChannels = "歌词渠道"
    override val sectionSecondary = "副行内容"
    override val sectionColorOs = "ColorOS 锁屏岛"
    override val sectionSuperIsland = "超级岛外观"
    override val sectionLiveUpdate = "实时活动"

    override val lyricon = "词幕（Lyricon）"
    override val lyriconNote = "作为词幕的歌词来源注册，由词幕决定显示成桌面歌词还是状态栏歌词"

    override val superLyric = "SuperLyric"
    override val superLyricNote = "向 SuperLyric 推送当前句与逐字时间轴（需已安装并启用 SuperLyric）"

    override val lyricGetter = "Lyric Getter"
    override val lyricGetterNote = "交给 Lyric Getter 模块处理（需已安装并启用该系统模块）"

    override val colorOs = "ColorOS 锁屏岛歌词"
    override val colorOsNote = "把整首歌词写进锁屏岛；ColorOS / realme / 一加 上生效"

    override val colorOsMode = "歌词格式"
    override val colorOsModeSystem = "系统格式"
    override val colorOsModeModule = "模块格式"
    override val colorOsModeSystemNote = "只发整首行级歌词，兼容性最好"
    override val colorOsModeModuleNote = "额外发逐字时间轴与翻译，需要系统侧装了 Bridge 4.0 模块"

    override val superIsland = "HyperOS 超级岛"
    override val superIslandNote = "在小米超级岛里显示当前歌词与进度；通过专用前台服务发送"

    override val superIslandUnsupported = "当前系统不是 HyperOS，超级岛不会被渲染"

    override val statusBar = "状态栏歌词"
    override val statusBarNote = "魅族 Flyme 走 ticker 广播；其它系统用一条常驻通知承载文本"

    override val headsUp = "浮动歌词通知"
    override val headsUpNote = "用横幅弹出当前歌词（非魅族设备上的状态栏歌词替代方案）"

    override val liveUpdate = "实时活动歌词"
    override val liveUpdateNote = "Android 16 的实时活动 / 灵动区域，低版本系统会退化成普通通知"

    override val liveUpdateContent = "显示内容"
    override val liveUpdateDisplay = "显示范围"
    override val liveUpdateDisplayWord = "跟随逐字"
    override val liveUpdateDisplayFull = "整句"
    override val liveUpdateSecondary = "副标题"
    override val liveUpdateSecondarySong = "曲目信息"

    override val mediaNotification = "媒体通知歌词"
    override val mediaNotificationNote = "把当前歌词写进媒体通知的标题，蓝牙设备、车机与媒体中心读到的就是它"

    override val secondaryOff = "不显示"
    override val secondaryTranslation = "翻译"
    override val secondaryPronunciation = "注音"

    override val contentOriginal = "原文"
    override val contentTranslation = "翻译"
    override val contentPronunciation = "注音"

    override val islandContent = "岛内歌词"
    override val islandLayout = "布局"
    override val islandLayoutStandard = "标准"
    override val islandLayoutFull = "完整"
    override val islandShowCover = "完整布局保留封面"
    override val islandShowCoverNote = "左侧留出封面位，歌词列会相应变窄"
    override val islandScrolling = "逐字滚动"
    override val islandScrollingNote = "关闭后固定显示整句，避免短句频繁重排"
    override val islandRightChars = "右侧字数"
    override val islandLeftCoverChars = "左侧字数（有封面）"
    override val islandLeftChars = "左侧字数（无封面）"
    override fun islandCharsValue(chars: Int) = "$chars 个字"

    override val islandTextColor = "文字跟随封面取色"
    override val islandTextColorNote = "用当前封面推出强调色，关闭时用系统默认色"
    override val islandColorSource = "强调色来源"
    override val islandColorSourceAlbum = "封面取色"
    override val islandColorSourceCustom = "自定义"
    override val islandCustomColor = "自定义颜色"
    override val islandProgressColor = "进度条同色"
    override val islandProgressColorNote = "关闭时进度条用中性灰"
    override val islandDismissDelay = "暂停后收起"
    override val islandDismissImmediately = "立即收起"
    override fun islandDismissAfter(seconds: Int) = "$seconds 秒后收起"
}

/** English. */
object LyricPushStringsEn : LyricPushStrings {

    override val entryTitle = "Lyric delivery"
    override val entrySubtitle = "Send lyrics to Lyricon, Super Island, status bar, lock screen and car units"

    override val screenTitle = "Lyric delivery"
    override val note = "Lets CPPlayer hand \"what you're playing and which line\" to the system or " +
        "third-party lyric apps. Everything is off by default — enable a channel to start writing to it."
    override val androidOnlyNote = "All of these need notification permission; Lyricon, SuperLyric and " +
        "Lyric Getter additionally need the corresponding app installed and enabled, otherwise the switch " +
        "has no effect."

    override val sectionChannels = "Channels"
    override val sectionSecondary = "Secondary line"
    override val sectionColorOs = "ColorOS lock-screen island"
    override val sectionSuperIsland = "Super Island appearance"
    override val sectionLiveUpdate = "Live Update"

    override val lyricon = "Lyricon"
    override val lyriconNote = "Registers as a Lyricon lyric source; Lyricon decides how to display it"

    override val superLyric = "SuperLyric"
    override val superLyricNote = "Pushes the current line and word timings (requires SuperLyric installed)"

    override val lyricGetter = "Lyric Getter"
    override val lyricGetterNote = "Hands lyrics to the Lyric Getter module (requires it installed and enabled)"

    override val colorOs = "ColorOS lock-screen lyrics"
    override val colorOsNote = "Writes the full lyric into the lock-screen island (ColorOS / realme / OnePlus)"

    override val colorOsMode = "Lyric format"
    override val colorOsModeSystem = "System"
    override val colorOsModeModule = "Module"
    override val colorOsModeSystemNote = "Line-level LRC only — best compatibility"
    override val colorOsModeModuleNote = "Also sends word timings and translation; needs the Bridge 4.0 module"

    override val superIsland = "HyperOS Super Island"
    override val superIslandNote = "Shows the current lyric and progress inside Xiaomi's Super Island"

    override val superIslandUnsupported = "This system is not HyperOS, so the Super Island will not render"

    override val statusBar = "Status bar lyrics"
    override val statusBarNote = "Meizu Flyme uses the ticker broadcast; other systems use a persistent notification"

    override val headsUp = "Heads-up lyric notification"
    override val headsUpNote = "Shows the current lyric as a banner (the status-bar alternative on non-Meizu devices)"

    override val liveUpdate = "Live Update lyrics"
    override val liveUpdateNote = "Android 16 Live Update / dynamic area; older systems fall back to a normal notification"

    override val liveUpdateContent = "Content"
    override val liveUpdateDisplay = "Range"
    override val liveUpdateDisplayWord = "Follow words"
    override val liveUpdateDisplayFull = "Full line"
    override val liveUpdateSecondary = "Subtitle"
    override val liveUpdateSecondarySong = "Track info"

    override val mediaNotification = "Media notification lyrics"
    override val mediaNotificationNote = "Puts the current lyric in the media notification title — " +
        "Bluetooth devices, car units and media panels read it there"

    override val secondaryOff = "None"
    override val secondaryTranslation = "Translation"
    override val secondaryPronunciation = "Romanization"

    override val contentOriginal = "Original"
    override val contentTranslation = "Translation"
    override val contentPronunciation = "Romanization"

    override val islandContent = "Island lyric"
    override val islandLayout = "Layout"
    override val islandLayoutStandard = "Standard"
    override val islandLayoutFull = "Full"
    override val islandShowCover = "Keep artwork in full layout"
    override val islandShowCoverNote = "Reserves space for the cover on the left, narrowing the lyric column"
    override val islandScrolling = "Word-by-word scroll"
    override val islandScrollingNote = "Off keeps the whole line visible and avoids frequent re-layout"
    override val islandRightChars = "Right column length"
    override val islandLeftCoverChars = "Left column (with cover)"
    override val islandLeftChars = "Left column (no cover)"
    override fun islandCharsValue(chars: Int) = "$chars chars"

    override val islandTextColor = "Tint text from artwork"
    override val islandTextColorNote = "Derives an accent colour from the current artwork; off uses system default"
    override val islandColorSource = "Accent source"
    override val islandColorSourceAlbum = "From artwork"
    override val islandColorSourceCustom = "Custom"
    override val islandCustomColor = "Custom colour"
    override val islandProgressColor = "Match progress bar"
    override val islandProgressColorNote = "Off uses a neutral grey"
    override val islandDismissDelay = "Dismiss after pause"
    override val islandDismissImmediately = "Immediately"
    override fun islandDismissAfter(seconds: Int) = "After $seconds s"
}
