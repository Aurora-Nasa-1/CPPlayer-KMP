package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.version.AppVersion

/**
 * 设置树与设置项注册中心：集中定义全平台设置入口、分组及层级检索元数据。
 */
/**
 * 设置分组。
 *
 * ⚠️ 标题是 `(CpStrings) -> String`（取值函数）而不是 `String`（值）：本 Registry 的列表
 * 由**非 Composable 的顶层函数**构造，那里读不到 CompositionLocal，也没有 Compose 作用域。
 * 存取值函数、在渲染处代入，是这个问题唯一干净的解法 ——
 * 顺带换来「切换语言时列表自动跟着变」而不用重建列表、也不用把手伸进组合。
 *
 * 多语言改动须知见 `docs/dev/I18N.md`。
 */
enum class SettingsGroup(val titleOf: (CpStrings) -> String) {
    /** 跟内容本身无关的偏好：长什么样、怎么播、存哪。 */
    GENERAL({ it.settings.groupGeneral }),

    /** 我是谁、我从哪个音源拿内容。 */
    ACCOUNT_AND_PROVIDER({ it.settings.groupAccountProvider }),

    /** 和本机之外的软件打交道。 */
    CONNECTIVITY({ it.settings.groupConnectivity }),

    /** 低频 + 开发者向。放最末；其中排查/开发专用的入口只在 debug 构建展示（`debugOnly`）。 */
    OTHER({ it.settings.groupOther }),
}

/**
 * 设置项图标的**强调色角色**。
 *
 * 原先这里是 10 组硬编码的浅色值（`Color(0xFFE8F5E9)` 之类），有两个问题：
 * 1. 它们是**浅色模式专用**的粉彩 —— 深色模式下底色发白、对比度直接崩掉；
 * 2. 写死的色相不跟随动态取色，用户换了封面配色后，设置页成了唯一「不跟着变」的地方。
 *
 * 改成引用主题的 **fixed 角色**：`primaryFixed / secondaryFixed / tertiaryFixed` 在 M3 里
 * 正是「不随明暗反转、但跟随种子色」的容器色，天然满足「给分类一个稳定身份」的需求。
 */
enum class SettingsAccent { PRIMARY, SECONDARY, TERTIARY }

/**
 * 一个设置入口。
 *
 * @param id 稳定标识：桌面左栏选中态、将来的深链与搜索都用它，**不要用标题当 key**
 * @param keywords 搜索同义词（「纯黑」→ oled / 省电 / amoled）。
 *   ⚠️ **同一个列表里同时放中英两套词**，不要做成随语言切换的资源：
 *   搜索的输入是用户此刻敲的东西 —— 中文用户在英文界面下照样会敲「纯黑」，
 *   反之亦然。按语言换词只会让「换个界面语言就搜不到」。
 * @param titleOf 入口标题的取值函数（`(CpStrings) -> String`，不是值）。理由见 [SettingsGroup] 的 KDoc
 * @param subtitleOf 入口副标题的取值函数
 * @param desktopOnly 仅桌面端出现。例：渲染后端对 Android 无意义 —— Android 的渲染
 *   完全交给系统，没有可切换的后端
 * @param androidOnly 仅 Android 出现。例：激进保活（Wi-Fi 高性能锁 / 组播锁）是
 *   Android 后台限制的产物，桌面既不需要也没有对应能力 —— 放上去只会让用户
 *   看到一个永远「未生效」的开关（见 `StandbySettingsScreen`）
 * @param debugOnly 仅 debug 构建出现（[cp.player.app.version.AppVersion.isDebugBuild]，
 *   即非 stable 渠道）。用于开发者向、或尚未打算对正式版用户开放的入口
 * @param subItems 具体设置子项列表（用于全景深度搜索与层级定位）
 */
data class SettingsEntry(
    val id: String,
    val group: SettingsGroup,
    val titleOf: (CpStrings) -> String,
    val subtitleOf: (CpStrings) -> String,
    val icon: ImageVector,
    val accent: SettingsAccent,
    val keywords: List<String> = emptyList(),
    val desktopOnly: Boolean = false,
    val androidOnly: Boolean = false,
    val debugOnly: Boolean = false,
    val subItems: List<SettingsSubItem> = emptyList(),
    val screen: (targetSettingId: String?) -> Screen,
)

/**
 * 设置子项（具体设置项）的元数据，用于全景深度搜索与层级定位。
 *
 * @param id 设置子项唯一标识
 * @param titleOf 子项标题
 * @param subtitleOf 子项副标题或功能说明
 * @param keywords 搜索关键词同义词
 * @param sectionOf 所属页面内分区标题（例如「视觉与动效」、「发烧音频内核」等）
 * @param desktopOnly 仅桌面端适用
 * @param androidOnly 仅 Android 端适用
 * @param debugOnly 仅 Debug 构建展示
 */
data class SettingsSubItem(
    val id: String,
    val titleOf: (CpStrings) -> String,
    val subtitleOf: ((CpStrings) -> String)? = null,
    val keywords: List<String> = emptyList(),
    val sectionOf: ((CpStrings) -> String)? = null,
    val desktopOnly: Boolean = false,
    val androidOnly: Boolean = false,
    val debugOnly: Boolean = false,
)

/**
 * 当前平台与渠道下可见的具体设置子项。
 */
fun SettingsEntry.visibleSubItems(): List<SettingsSubItem> = subItems.filter { item ->
    (!item.desktopOnly || !isAndroidPlatform()) &&
        (!item.androidOnly || isAndroidPlatform()) &&
        (!item.debugOnly || AppVersion.isDebugBuild)
}

/**
 * 设置搜索匹配项。
 */
sealed interface SettingsSearchResult {
    /** 命中具体的二级设置项。 */
    data class SubItemMatch(
        val entry: SettingsEntry,
        val subItem: SettingsSubItem,
    ) : SettingsSearchResult

    /** 命中一级分类入口。 */
    data class EntryMatch(
        val entry: SettingsEntry,
    ) : SettingsSearchResult
}

/**
 * 全部设置入口，按 [SettingsGroup] 的出现顺序排列。
 *
 * 顺序即渲染顺序：`GENERAL` → `ACCOUNT_AND_PROVIDER` → `CONNECTIVITY` → `OTHER`。
 */
fun settingsEntries(): List<SettingsEntry> = buildList {
    addAll(generalEntries())
    addAll(accountEntries())
    addAll(connectivityEntries())
    addAll(otherEntries())
}.filter { entry ->
    (!entry.desktopOnly || !isAndroidPlatform()) &&
        (!entry.androidOnly || isAndroidPlatform()) &&
        (!entry.debugOnly || AppVersion.isDebugBuild)
}

private fun generalEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "language",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemLanguage.title },
        subtitleOf = { it.settings.itemLanguage.subtitle },
        icon = Icons.Filled.Language,
        accent = SettingsAccent.PRIMARY,
        // 中英两套词都留着（理由见 SettingsEntry.keywords 的 KDoc）。
        keywords = listOf(
            "语言", "中文", "英文", "简体", "繁体", "汉化", "翻译", "界面语言",
            "language", "locale", "chinese", "english", "i18n", "translation", "localization",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "language_picker",
                titleOf = { it.settings.itemLanguage.title },
                subtitleOf = { it.settings.itemLanguage.subtitle },
                keywords = listOf("语言", "界面语言", "中文", "英文", "简体", "繁体", "翻译", "locale", "language", "english", "chinese"),
                sectionOf = { it.settings.groupGeneral },
            ),
        ),
        screen = { _ -> LanguageSettingsScreen() },
    ),
    SettingsEntry(
        id = "appearance",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemAppearance.title },
        subtitleOf = { it.settings.itemAppearance.subtitle + " · " + it.language.screenTitle },
        icon = Icons.Filled.Palette,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf(
            "主题", "深色", "浅色", "暗黑", "取色", "配色", "纯黑", "oled", "省电", "theme", "dark",
            "语言", "中文", "英文", "简体", "繁体", "language", "locale", "chinese", "english", "i18n", "translation",
            "专辑", "专辑名", "歌曲列表", "album",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "appearance_theme_mode",
                titleOf = { it.appearance.themeMode },
                subtitleOf = { "跟随系统 · 浅色模式 · 深色模式" },
                keywords = listOf("主题", "深色", "浅色", "暗黑", "跟随系统", "夜间模式", "theme", "dark", "light", "mode"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_color_source",
                titleOf = { it.appearance.colorSource },
                subtitleOf = { "专辑封面取色 · 系统动态取色 (Monet) · 应用预设配色" },
                keywords = listOf("取色", "动态取色", "配色", "莫奈", "Monet", "Material You", "封面取色", "种子色", "color"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_pure_black",
                titleOf = { it.appearance.pureBlack },
                subtitleOf = { it.appearance.pureBlackNote },
                keywords = listOf("纯黑", "oled", "amoled", "纯黑背景", "黑色", "省电", "black"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_auto_hide_bottom_bar",
                titleOf = { it.appearance.autoHideBottomBar },
                subtitleOf = { it.appearance.autoHideBottomBarNote },
                keywords = listOf("底栏", "播放条", "隐藏底栏", "自动隐藏", "收起底栏", "全屏", "沉浸", "bottom bar"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_cover_flight",
                titleOf = { it.appearance.coverFlight },
                subtitleOf = { it.appearance.coverFlightNote },
                keywords = listOf("封面", "飞行动画", "展开动画", "转场", "过渡", "动效", "flight", "animation"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_fluid_background",
                titleOf = { it.appearance.fluidBackground },
                subtitleOf = { it.appearance.fluidBackgroundNote },
                keywords = listOf("流体", "流光", "动态背景", "渐变", "模糊", "弥散光晕", "fluid", "ambient", "background"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_show_song_album",
                titleOf = { it.appearance.showSongAlbum },
                subtitleOf = { it.appearance.showSongAlbumNote },
                keywords = listOf("专辑", "专辑名", "歌曲列表", "单曲信息", "副标题", "album"),
                sectionOf = { it.appearance.sectionLook },
            ),
            SettingsSubItem(
                id = "appearance_font_roundness",
                titleOf = { it.appearance.fontRoundness },
                subtitleOf = { "调节界面部件形状与字体笔画的圆润程度" },
                keywords = listOf("圆角", "字体", "几何", "笔画", "圆润", "形状", "radius", "roundness", "font"),
                sectionOf = { it.appearance.sectionFont },
            ),
            SettingsSubItem(
                id = "appearance_font_google_sans",
                titleOf = { it.fontPacks.googleSansTitle },
                subtitleOf = { it.fontPacks.googleSansNote },
                keywords = listOf("Google Sans", "拉丁", "英文", "数字", "默认字体", "latin", "font"),
                sectionOf = { it.fontPacks.sectionTitle },
            ),
            SettingsSubItem(
                id = "appearance_font_noto_sans_jp",
                titleOf = { it.fontPacks.japaneseTitle },
                subtitleOf = { it.fontPacks.japaneseNote },
                keywords = listOf("Noto Sans JP", "日文", "日语", "假名", "日文汉字", "japanese", "font"),
                sectionOf = { it.fontPacks.sectionTitle },
            ),
            SettingsSubItem(
                id = "appearance_font_misans",
                titleOf = { it.fontPacks.misansTitle },
                subtitleOf = { it.fontPacks.misansNote },
                keywords = listOf("MiSans", "中文", "汉字", "标点", "小米", "chinese", "font"),
                sectionOf = { it.fontPacks.sectionTitle },
            ),
            SettingsSubItem(
                id = "appearance_mobile_preview",
                titleOf = { "手机版界面预览 (412×892)" },
                subtitleOf = { "切换桌面端与手机版紧凑布局、底部导航栏与手机顶栏" },
                keywords = listOf("手机", "手机版", "移动端", "mobile", "preview", "紧凑", "竖屏", "phone"),
                sectionOf = { "布局与视图预览" },
                desktopOnly = true,
                debugOnly = true,
            ),
        ),
        screen = { targetId -> AppearanceSettingsScreen(targetId) },
    ),
    SettingsEntry(
        id = "playback",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemPlayback.title },
        subtitleOf = { "FlickEngine Hi-Res 发烧内核 · 硬件直通与独占 · 源码输出 · 音效与音质" },
        icon = Icons.Filled.PlayArrow,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf(
            "音质", "无损", "hires", "定时", "睡眠", "关闭", "quality", "sleep",
            "flick", "engine", "wasapi", "exclusive", "独占", "bit-perfect", "源码",
            "内核", "发烧", "dsd", "asio", "声卡", "音频", "输出", "dac",
            "音效", "均衡器", "均衡", "eq", "peq", "调音", "混音", "平衡", "响度",
            "一起听", "点歌", "下一首", "添加到下一首", "listen together",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "playback_quality",
                titleOf = { it.settings.itemPlayback.title },
                subtitleOf = { "标准 (128k) · 极高 (320k) · 无损 (FLAC) · Hi-Res · 沉浸声" },
                keywords = listOf("音质", "无损", "flac", "hires", "极高", "高品质", "码率", "quality", "lossless"),
                sectionOf = { "流媒体与音质" },
            ),
            SettingsSubItem(
                id = "playback_metered_quality",
                titleOf = { "移动网络播放音质" },
                subtitleOf = { "使用蜂窝移动数据流量时的播放音质，节约流量" },
                keywords = listOf("移动网络", "蜂窝数据", "流量", "省流量", "移动数据音质", "metered", "cellular"),
                sectionOf = { "流媒体与音质" },
                androidOnly = true,
            ),
            SettingsSubItem(
                id = "playback_audio_engine",
                titleOf = { "FlickEngine Hi-Res 发烧音频内核" },
                subtitleOf = { "硬件直通 · 源码输出 · WASAPI 独占 · 高采样率 DAC 支持" },
                keywords = listOf("音频引擎", "内核", "flick", "engine", "发烧", "hires", "dac", "直通", "源码输出"),
                sectionOf = { "发烧音频内核" },
            ),
            SettingsSubItem(
                id = "playback_wasapi_exclusive",
                titleOf = { "WASAPI 硬件独占输出" },
                subtitleOf = { "绕过系统音频混音器，独占音频设备实现原生采样率源码直通" },
                keywords = listOf("wasapi", "独占", "exclusive", "硬件独占", "bit-perfect", "源码", "混音器", "声卡"),
                sectionOf = { "发烧音频内核" },
                desktopOnly = true,
            ),
            SettingsSubItem(
                id = "playback_crossfade",
                titleOf = { "音频淡入淡出 (Crossfade)" },
                subtitleOf = { "开始播放、暂停及切换歌曲时的音量平滑渐变过渡" },
                keywords = listOf("淡入淡出", "淡入", "淡出", "渐变", "平滑过渡", "切歌过渡", "crossfade", "fade"),
                sectionOf = { "播放控制" },
            ),
            SettingsSubItem(
                id = "playback_keep_progress",
                titleOf = { "记忆上次播放进度" },
                subtitleOf = { "重新启动应用时恢复上次播放的曲目及播放时间点" },
                keywords = listOf("记忆播放", "断点续播", "进度", "恢复播放", "历史进度", "resume", "progress"),
                sectionOf = { "播放控制" },
            ),
            SettingsSubItem(
                id = "playback_sleep_timer",
                titleOf = { "睡眠定时器" },
                subtitleOf = { "倒计时停止播放或在当前歌曲播放完毕后自动退出" },
                keywords = listOf("睡眠", "定时", "倒计时", "定时关闭", "自动停止", "播完停止", "sleep", "timer"),
                sectionOf = { "定时与自动化" },
            ),
            SettingsSubItem(
                id = "playback_listen_together_add_next",
                titleOf = { it.social.together.addNextOnPlay },
                subtitleOf = { it.social.together.addNextOnPlayNote },
                keywords = listOf("一起听", "点歌", "下一首", "下一首播放", "添加到下一首", "listen together", "add next"),
                sectionOf = { it.player.listenTogether },
            ),
        ),
        screen = { targetId -> PlaybackSettingsScreen(targetId) },
    ),
    // 音效紧跟在「播放与音质」之后：两者是同一主题的两个层次
    // （音质决定"拿到什么"，音效决定"听起来怎么样"），用户找前者时常顺手看后者。
    SettingsEntry(
        id = "audio_effect",
        group = SettingsGroup.GENERAL,
        titleOf = { it.audioEffect.screenTitle },
        subtitleOf = { it.audioEffect.entrySubtitle },
        icon = Icons.Filled.Equalizer,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf(
            "音效", "均衡器", "均衡", "EQ", "PEQ", "参数均衡", "调音", "音染", "预设",
            "混音", "声道", "平衡", "声像", "左右",
            "响度", "音量均衡", "归一化", "防爆音", "削顶", "动态",
            "effect", "equalizer", "eq", "balance", "pan", "loudness", "leveling", "clipping",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "audio_effect_equalizer",
                titleOf = { it.audioEffect.screenTitle },
                subtitleOf = { "10 频段参数与图形均衡器调音，支持频段微调" },
                keywords = listOf("均衡器", "eq", "equalizer", "调音", "频段", "高音", "低音", "中音"),
                sectionOf = { "调音与均衡" },
            ),
            SettingsSubItem(
                id = "audio_effect_presets",
                titleOf = { "均衡器风格预设" },
                subtitleOf = { "流行、摇滚、爵士、古典、低音增强 (Bass Boost)、人声增强等" },
                keywords = listOf("预设", "风格", "低音增强", "人声", "摇滚", "流行", "古典", "bass", "preset"),
                sectionOf = { "调音与均衡" },
            ),
            SettingsSubItem(
                id = "audio_effect_balance",
                titleOf = { "左右声道平衡 (Pan)" },
                subtitleOf = { "微调左右耳声音平衡与声像分布" },
                keywords = listOf("声道", "平衡", "左右", "偏音", "声像", "balance", "pan"),
                sectionOf = { "声道与音场" },
            ),
            SettingsSubItem(
                id = "audio_effect_loudness",
                titleOf = { "音量归一化与防爆音削顶" },
                subtitleOf = { "均衡不同歌曲间的音量差异，防止大动态爆音破音" },
                keywords = listOf("响度", "音量均衡", "归一化", "防爆音", "防破音", "削顶", "loudness", "normalization"),
                sectionOf = { "动态控制" },
            ),
        ),
        screen = { _ -> AudioEffectSettingsScreen() },
    ),
    SettingsEntry(
        id = "storage",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemStorage.title },
        subtitleOf = { it.settings.itemStorage.subtitle },
        icon = Icons.Filled.Storage,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf(
            "下载", "目录", "缓存", "清理", "空间", "存储", "cache", "download",
            // 无损流落盘是磁盘占用最大的一块，用户找它时用的多半是这几个词。
            "歌曲缓存", "无损", "离线", "接口缓存", "命中率",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "storage_download_dir",
                titleOf = { "下载存储目录" },
                subtitleOf = { "离线下载歌曲与封面的本地保存文件夹" },
                keywords = listOf("下载", "目录", "文件夹", "路径", "存储位置", "download", "folder", "path"),
                sectionOf = { "存储与下载" },
            ),
            SettingsSubItem(
                id = "storage_song_cache_limit",
                titleOf = { "歌曲缓存容量上限" },
                subtitleOf = { "在线试听无损流本地缓存配额（500MB / 1GB / 2GB / 5GB / 无限制）" },
                keywords = listOf("缓存容量", "缓存上限", "缓存大小", "配额", "容量上限", "歌曲缓存", "cache limit"),
                sectionOf = { "空间占用" },
            ),
            SettingsSubItem(
                id = "storage_song_cache_inspect",
                titleOf = { "歌曲缓存条目明细" },
                subtitleOf = { "按歌名、歌手与音质查看与管理已缓存的音频文件" },
                keywords = listOf("已缓存", "缓存列表", "查看缓存", "离线歌曲", "歌曲明细", "cache list"),
                sectionOf = { "空间占用" },
            ),
            SettingsSubItem(
                id = "storage_clear_song_cache",
                titleOf = { "清空歌曲音频流缓存" },
                subtitleOf = { "删除所有自动缓存的在线歌曲音频，释放磁盘空间" },
                keywords = listOf("清理歌曲缓存", "清空缓存", "释放空间", "删除缓存", "clear song cache"),
                sectionOf = { "清理与维护" },
            ),
            SettingsSubItem(
                id = "storage_clear_image_cache",
                titleOf = { "清空图片与封面缓存" },
                subtitleOf = { "删除专辑封面、歌手照片及本地临时图片缓存" },
                keywords = listOf("清理图片", "封面缓存", "清空图片缓存", "删除图片", "clear image cache"),
                sectionOf = { "清理与维护" },
            ),
            SettingsSubItem(
                id = "storage_clear_http_cache",
                titleOf = { "清空网络接口数据缓存" },
                subtitleOf = { "清除歌单、歌曲详情及搜索结果的临时网络数据缓存" },
                keywords = listOf("网络缓存", "接口缓存", "数据缓存", "刷新接口", "clear http cache"),
                sectionOf = { "清理与维护" },
            ),
        ),
        screen = { _ -> StorageSettingsScreen() },
    ),
    SettingsEntry(
        id = "shortcuts",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemShortcuts.title },
        subtitleOf = { it.settings.itemShortcuts.subtitle },
        icon = Icons.Filled.Keyboard,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf(
            "快捷键", "键盘", "热键", "组合键", "键位", "shortcut", "hotkey", "keyboard",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "shortcuts_global",
                titleOf = { "播放控制全局与应用快捷键" },
                subtitleOf = { "播放/暂停、上一曲、下一曲、音量、静音、收藏与窗口唤醒" },
                keywords = listOf("快捷键", "热键", "键盘", "按键", "空格", "全局快捷键", "shortcut", "hotkey"),
                sectionOf = { "键盘热键" },
                desktopOnly = true,
            ),
        ),
        desktopOnly = true,
        screen = { _ -> ShortcutSettingsScreen() },
    ),
)

private fun accountEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "account",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        titleOf = { it.settings.itemAccount.title },
        subtitleOf = { it.settings.itemAccount.subtitle },
        icon = Icons.Filled.Person,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("账号", "登录", "退出", "扫码", "邮箱", "手机", "多账号", "隔离", "account", "login"),
        subItems = listOf(
            SettingsSubItem(
                id = "account_login_manage",
                titleOf = { "账号登录与多账号隔离" },
                subtitleOf = { "二维码扫码登录、密码登录、Cookie 导入与多账号管理" },
                keywords = listOf("登录", "账号", "扫码", "退出登录", "切换账号", "多账号", "cookie", "login", "account"),
                sectionOf = { "音源账号" },
            ),
        ),
        screen = { _ -> AccountScreen() },
    ),
    SettingsEntry(
        id = "providers",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        titleOf = { it.settings.itemProviders.title },
        subtitleOf = { it.settings.itemProviders.subtitle },
        icon = Icons.Filled.Dns,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("音源", "导入", "模块", "切换", "音源配置", "音源设置", "自定义配置", "provider", "module", "settings"),
        subItems = listOf(
            SettingsSubItem(
                id = "providers_manage",
                titleOf = { "音源插件管理与导入" },
                subtitleOf = { "安装第三方音源 (.zip)、启用/禁用音源与调整搜索聚合优先级" },
                keywords = listOf("音源", "插件", "模块", "导入音源", "音源优先级", "音源配置", "音源设置", "自定义配置", "provider", "module", "source"),
                sectionOf = { "数据源" },
            ),
        ),
        screen = { _ -> ProviderManagementScreen() },
    ),
    // 歌词来源与音源管理同组：两者都是「内容/数据来源」，用户找它们时想的是同一件事
    // ——「我从哪儿拿内容」。且都支持导入 zip，放在一起心智一致。
    SettingsEntry(
        id = "lyrics_plugins",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        titleOf = { it.lyricsPlugin.entryTitle },
        subtitleOf = { it.lyricsPlugin.entrySubtitle },
        icon = Icons.Filled.ClosedCaption,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf(
            "歌词源", "歌词来源", "来源", "排序", "优先级", "插件", "歌词插件",
            "lyrico", "lrc", "ttml", "逐字", "导入歌词", "amll", "边车",
            "lyrics", "source", "plugin", "order", "priority", "karaoke",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "lyrics_plugins_priority",
                titleOf = { "歌词源插件与优先级" },
                subtitleOf = { "管理 AMLL / TTML / 网易云 / QQ 歌词源，支持拖拽排序与导入插件" },
                keywords = listOf("歌词", "歌词源", "逐字歌词", "歌词插件", "AMLL", "ttml", "lrc", "lyrics", "karaoke"),
                sectionOf = { "歌词生态" },
            ),
        ),
        screen = { _ -> LyricsPluginSettingsScreen() },
    ),
    SettingsEntry(
        id = "plugin_repository",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        titleOf = { it.pluginRepository.title },
        subtitleOf = { it.pluginRepository.subtitle },
        icon = Icons.Filled.Extension,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("插件", "仓库", "安装", "更新", "plugin", "repository", "install", "update"),
        screen = { _ -> PluginRepositoryScreen() },
    ),
)

/**
 * 「连接与集成」分组设置入口。
 */
private fun connectivityEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "external_access",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.settings.itemExternalAccess.title },
        subtitleOf = { it.settings.itemExternalAccess.subtitle },
        icon = Icons.Filled.SettingsEthernet,
        accent = SettingsAccent.SECONDARY,
        // 两个方向的词都在：用户不一定记得「拉流」和「推送」分别在哪一页（现在也不分家了）。
        keywords = listOf(
            "服务器", "端口", "绑定", "局域网", "令牌", "token", "端口占用",
            "推送", "接收端", "接口", "第三方", "集成", "外部", "访问",
            "stream", "server", "port", "push", "receiver", "api", "integration",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "external_access_switch",
                titleOf = { "本地 HTTP 服务总开关" },
                subtitleOf = { "开启本机 HTTP 服务以支持第三方生态集成与音频拉流" },
                keywords = listOf("本地服务", "http服务", "服务器", "服务总开关", "server", "http"),
                sectionOf = { "本地服务" },
            ),
            SettingsSubItem(
                id = "external_access_port_bind",
                titleOf = { "服务监听端口与绑定地址" },
                subtitleOf = { "配置端口（默认 64188）及回环 (127.0.0.1) / 局域网 (0.0.0.0)" },
                keywords = listOf("端口", "port", "64188", "绑定地址", "局域网", "127.0.0.1", "0.0.0.0", "bind"),
                sectionOf = { "网络配置" },
            ),
            SettingsSubItem(
                id = "external_access_token",
                titleOf = { "局域网访问鉴权令牌 (Token)" },
                subtitleOf = { "Bearer Token 鉴权令牌，支持重新生成与一键复制" },
                keywords = listOf("令牌", "token", "密钥", "鉴权", "bearer", "安全令牌", "auth"),
                sectionOf = { "安全与鉴权" },
            ),
            SettingsSubItem(
                id = "external_access_data_api",
                titleOf = { "开放曲库与播控数据接口 (Data API)" },
                subtitleOf = { "允许局域网或外部软件读取当前播放曲目与歌单列表" },
                keywords = listOf("数据接口", "读取数据", "第三方", "数据开放", "api", "integration", "rest"),
                sectionOf = { "生态集成" },
            ),
            SettingsSubItem(
                id = "external_access_webhook",
                titleOf = { "外部状态推送 (Webhook)" },
                subtitleOf = { "歌曲切换、播放状态改变时向接收端 URL 发送实时事件" },
                keywords = listOf("推送", "webhook", "接收端", "事件推送", "push", "receiver"),
                sectionOf = { "生态集成" },
            ),
        ),
        screen = { _ -> ExternalAccessSettingsScreen() },
    ),
    // 歌词投放与「外部访问」同族：都是「把本机正在发生的事交给别人」。
    // 区别只在出口 —— 那一页给的是 HTTP 接口，这一页给的是系统 UI 与歌词应用。
    SettingsEntry(
        id = "lyric_push",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.lyricPush.entryTitle },
        subtitleOf = { it.lyricPush.entrySubtitle },
        icon = Icons.Filled.Subtitles,
        accent = SettingsAccent.TERTIARY,
        // ⚠️ androidOnly：所有渠道都依赖 Android 的通知/前台服务/SDK，
        // 且两端**不共用设置存储** ⇒ 桌面显示这一页只会得到一堆永不生效的开关。
        androidOnly = true,
        keywords = listOf(
            "歌词", "词幕", "状态栏歌词", "桌面歌词", "锁屏歌词", "超级岛", "灵动岛",
            "实时活动", "通知歌词", "车机", "蓝牙", "投送", "投放", "外发",
            "lyric", "lyricon", "superlyric", "super island", "hyperos", "coloros",
            "oplus", "flyme", "ticker", "live update", "lock screen", "notification",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "lyric_push_status_bar",
                titleOf = { "状态栏与灵动岛实时通知歌词" },
                subtitleOf = { "在系统状态栏、灵动岛/超级岛及通知中心胶囊显示歌词" },
                keywords = listOf("状态栏歌词", "通知歌词", "灵动岛", "超级岛", "实时活动", "胶囊", "ticker"),
                sectionOf = { "系统歌词" },
                androidOnly = true,
            ),
            SettingsSubItem(
                id = "lyric_push_floating",
                titleOf = { "悬浮桌面歌词" },
                subtitleOf = { "在屏幕最上层显示半透明浮窗歌词，支持锁定与位置调节" },
                keywords = listOf("桌面歌词", "悬浮窗", "浮窗歌词", "悬浮歌词", "floating lyrics"),
                sectionOf = { "系统歌词" },
                androidOnly = true,
            ),
            SettingsSubItem(
                id = "lyric_push_lock_screen",
                titleOf = { "锁屏歌词显示" },
                subtitleOf = { "熄屏与锁屏界面下同步展示当前播放歌曲歌词" },
                keywords = listOf("锁屏歌词", "熄屏歌词", "锁屏显示", "lock screen"),
                sectionOf = { "系统歌词" },
                androidOnly = true,
            ),
        ),
        screen = { _ -> LyricPushSettingsScreen() },
    ),
    SettingsEntry(
        id = "standby",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.settings.itemStandby.title },
        subtitleOf = { it.settings.itemStandby.subtitle },
        icon = Icons.Filled.Wifi,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf(
            "设备", "同步", "保活", "后台", "常驻", "熄屏", "锁屏", "掉线", "搜不到",
            "设备发现", "局域网", "听歌记录", "wifi", "组播", "standby", "sync", "keepalive",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "standby_battery",
                titleOf = { "忽略电池优化 (后台保活)" },
                subtitleOf = { "申请加入电源管理白名单，避免后台切歌或息屏时被系统杀后台" },
                keywords = listOf("电池优化", "白名单", "后台保活", "防杀后台", "熄屏中断", "keepalive"),
                sectionOf = { "电源与后台" },
                androidOnly = true,
            ),
            SettingsSubItem(
                id = "standby_wifi_lock",
                titleOf = { "Wi-Fi 高性能锁与组播锁" },
                subtitleOf = { "防止息屏时无线网络休眠断连，保证局域网对等发现正常" },
                keywords = listOf("wifi锁", "组播锁", "网络休眠", "息屏断连", "multicast", "wifi lock"),
                sectionOf = { "网络保活" },
                androidOnly = true,
            ),
            SettingsSubItem(
                id = "standby_device_sync",
                titleOf = { "跨设备局域网同步 (Handoff)" },
                subtitleOf = { "同一 Wi-Fi 下自动发现其它运行 CPPlayer 的设备并同步听歌历史" },
                keywords = listOf("设备同步", "局域网同步", "对等设备", "听歌记录同步", "handoff", "sync"),
                sectionOf = { "多端互联" },
            ),
        ),
        screen = { _ -> StandbySettingsScreen() },
    ),
    // 「谁开了私信推送」的全貌 + 总开关 + 桌面关窗去向，都收在这一页。
    // 归在「连接与集成」而不是「通用」：它与「应用怎么和外界打交道」（这里指通知中心）
    // 同一类，且与 [StandbySettingsScreen] 的「后台常驻」是同一件事的两面 ——
    // 桌面端只有常驻托盘才收得到通知，两页的说明互相引用。
    SettingsEntry(
        id = "msg_notify",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.messageNotify.settingsTitle },
        subtitleOf = { it.messageNotify.settingsSubtitle },
        icon = Icons.Filled.Notifications,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf(
            "消息", "私信", "通知", "推送", "提醒", "铃铛", "联系人",
            "托盘", "常驻", "最小化", "关窗",
            "notification", "notify", "message", "tray", "minimize",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "msg_notify_notifications",
                titleOf = { "私信与社交动态提醒" },
                subtitleOf = { "接收音源新私信、评论回复及系统通知" },
                keywords = listOf("消息", "私信", "通知", "提醒", "评论通知", "notification", "message"),
                sectionOf = { "消息通知" },
            ),
            SettingsSubItem(
                id = "msg_notify_tray",
                titleOf = { "关闭主窗口时最小化到系统托盘" },
                subtitleOf = { "点击窗口关闭按钮时不退出程序，保持后台音乐播放" },
                keywords = listOf("托盘", "系统托盘", "最小化", "关闭窗口", "后台播放", "关窗", "tray", "minimize"),
                sectionOf = { "桌面行为" },
                desktopOnly = true,
            ),
            SettingsSubItem(
                id = "msg_notify_startup",
                titleOf = { "开机自动启动" },
                subtitleOf = { "随电脑开机自动启动并在后台托盘静默运行" },
                keywords = listOf("开机启动", "自启动", "随系统启动", "开机自启", "autostart", "startup"),
                sectionOf = { "桌面行为" },
                desktopOnly = true,
            ),
        ),
        screen = { _ -> MessageNotifySettingsScreen() },
    ),
    SettingsEntry(
        id = "scrobble",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.scrobble.entryTitle },
        subtitleOf = { it.scrobble.entrySubtitle },
        icon = Icons.Filled.CloudSync,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf(
            "lastfm", "last.fm", "scrobble", "打卡", "听歌记录", "正在播放", "now playing", "红心", "外部同步",
        ),
        subItems = listOf(
            SettingsSubItem(
                id = "scrobble_lastfm",
                titleOf = { "Last.fm 平台连接" },
                subtitleOf = { "绑定 Last.fm 账号并自动同步打卡与正在播放" },
                keywords = listOf("lastfm", "last.fm", "打卡", "记录"),
                sectionOf = { "打卡平台" },
            ),
            SettingsSubItem(
                id = "scrobble_offline_queue",
                titleOf = { "离线待办队列" },
                subtitleOf = { "网络异常时暂存打卡记录并自动重试" },
                keywords = listOf("离线", "重试", "队列", "queue"),
                sectionOf = { "离线待办队列" },
            ),
        ),
        screen = { _ -> ScrobbleSettingsScreen() },
    ),
)

private fun otherEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "about",
        group = SettingsGroup.OTHER,
        titleOf = { it.settings.itemAbout.title },
        subtitleOf = { it.settings.itemAbout.subtitle },
        icon = Icons.Filled.Info,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("版本", "更新", "项目", "主页", "赞助", "支持", "about", "version", "update"),
        subItems = listOf(
            SettingsSubItem(
                id = "about_version_update",
                titleOf = { "检查版本更新" },
                subtitleOf = { "查看当前客户端版本并检查新版本更新发布" },
                keywords = listOf("版本", "更新", "检查更新", "升级", "最新版", "version", "update"),
                sectionOf = { "关于" },
            ),
            SettingsSubItem(
                id = "about_github_sponsor",
                titleOf = { "开源主页与赞助支持" },
                subtitleOf = { "访问 GitHub 项目主页、查看开源协议或赞助开发者" },
                keywords = listOf("开源", "主页", "github", "赞助", "鸣谢", "支持", "sponsor"),
                sectionOf = { "关于" },
            ),
        ),
        screen = { _ -> AboutScreen() },
    ),
    SettingsEntry(
        id = "diagnostics",
        group = SettingsGroup.OTHER,
        titleOf = { it.settings.itemDiagnostics.title },
        subtitleOf = { it.settings.itemDiagnostics.subtitle },
        icon = Icons.Filled.BugReport,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf("调试", "健康", "日志", "接口", "错误", "health", "debug", "log"),
        subItems = listOf(
            SettingsSubItem(
                id = "diagnostics_system_info",
                titleOf = { "系统运行环境与网络诊断" },
                subtitleOf = { "查看 OS 版本、JVM 运行时、内存占用及 API 连通性测试" },
                keywords = listOf("系统信息", "环境", "内存", "jvm", "网络测试", "连通性", "health", "system"),
                sectionOf = { "诊断与排查" },
            ),
            SettingsSubItem(
                id = "diagnostics_export_logs",
                titleOf = { "运行日志查看与导出" },
                subtitleOf = { "实时控制台日志滚动查看，支持导出日志文件用于排查" },
                keywords = listOf("日志", "导出日志", "控制台", "错误排查", "log", "export"),
                sectionOf = { "诊断与排查" },
            ),
        ),
        screen = { _ -> HealthScreen() },
    ),
    SettingsEntry(
        id = "render_tuning",
        group = SettingsGroup.OTHER,
        titleOf = { it.settings.itemRenderTuning.title },
        subtitleOf = { it.settings.itemRenderTuning.subtitle },
        icon = Icons.Filled.Memory,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("渲染", "后端", "垂直同步", "vsync", "撕裂", "卡顿", "显卡", "render", "gpu"),
        desktopOnly = true,
        // 排查向功能：给遇到 VRR 闪烁等问题的开发者留的对照开关，
        // 不希望普通用户误入后把渲染后端改乱，故仅 debug 构建展示。
        debugOnly = true,
        subItems = listOf(
            SettingsSubItem(
                id = "render_tuning_backend",
                titleOf = { "Skia 图形渲染后端" },
                subtitleOf = { "DirectX / OpenGL / Vulkan / 纯软件光栅渲染" },
                keywords = listOf("渲染后端", "directx", "opengl", "vulkan", "显卡", "gpu", "skia", "backend"),
                sectionOf = { "渲染性能" },
                desktopOnly = true,
                debugOnly = true,
            ),
            SettingsSubItem(
                id = "render_tuning_vsync",
                titleOf = { "垂直同步 (V-Sync)" },
                subtitleOf = { "画面帧率与显示器刷新率同步，防止画面撕裂" },
                keywords = listOf("垂直同步", "vsync", "撕裂", "刷新率", "帧率", "掉帧"),
                sectionOf = { "渲染性能" },
                desktopOnly = true,
                debugOnly = true,
            ),
        ),
        screen = { _ -> RenderTuningSettingsScreen() },
    ),
    SettingsEntry(
        id = "onboarding",
        group = SettingsGroup.OTHER,
        titleOf = { it.settings.itemOnboarding.title },
        subtitleOf = { it.settings.itemOnboarding.subtitle },
        icon = Icons.Filled.WavingHand,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("引导", "教程", "新手", "onboarding", "tutorial"),
        // 主要用于开发期验证引导流程的改动；正式版用户没有重看的场景。
        debugOnly = true,
        subItems = listOf(
            SettingsSubItem(
                id = "onboarding_guide",
                titleOf = { "新手引导教程" },
                subtitleOf = { "重温首次启动时的功能引导与音源配置步骤" },
                keywords = listOf("引导", "新手", "教程", "入门", "帮助", "onboarding"),
                sectionOf = { "帮助与引导" },
                debugOnly = true,
            ),
        ),
        screen = { _ -> OnboardingScreen(replay = true) },
    ),
)
