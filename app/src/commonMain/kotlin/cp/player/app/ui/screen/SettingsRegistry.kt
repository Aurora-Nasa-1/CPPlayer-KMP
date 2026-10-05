package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
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
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.version.AppVersion

/**
 * 设置树的**唯一声明**。
 *
 * ### 为什么要有这个文件
 *
 * 重构前，同一份设置树在四个地方各写了一遍：
 * 1. `SettingsScreen.settingsEntries()` —— 根列表（标题 / 副标题 / 图标）；
 * 2. `SettingsDetail` 枚举 —— list-detail 的选中标识；
 * 3. `SettingsScreen.toScreen()` —— `SettingsDetail` → Voyager `Screen` 映射；
 * 4. `SettingsScreen.DesktopSettingsDetail()` —— `SettingsDetail` → 直接渲染正文的映射。
 *
 * 四份平行结构改一处要同步四处，而它们**已经漂移**：`SettingsCategoryRail` 里硬编码的
 * 分类映射（0→外观 / 1→播放 / 2→交互逻辑 / 其余→音源管理）与实际列表毫无关系。
 *
 * 现在收敛成一份：[SettingsEntry] 的列表同时驱动根列表、桌面左栏、以及详情渲染。
 * 改一个设置项只需要动一个地方。
 *
 * ### 分组原则
 *
 * 按「用户想干什么」划分，不按代码模块划分；日常在前，开发者项收在 [SettingsGroup.OTHER]。
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
    val screen: () -> Screen,
)

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
        screen = { LanguageSettingsScreen() },
    ),
    SettingsEntry(
        id = "appearance",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemAppearance.title },
        subtitleOf = { it.settings.itemAppearance.subtitle },
        icon = Icons.Filled.Palette,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("主题", "深色", "浅色", "暗黑", "取色", "配色", "纯黑", "oled", "省电", "theme", "dark"),
        screen = { AppearanceSettingsScreen() },
    ),
    SettingsEntry(
        id = "playback",
        group = SettingsGroup.GENERAL,
        titleOf = { it.settings.itemPlayback.title },
        subtitleOf = { it.settings.itemPlayback.subtitle },
        icon = Icons.Filled.PlayArrow,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("音质", "无损", "hires", "定时", "睡眠", "关闭", "quality", "sleep"),
        screen = { PlaybackSettingsScreen() },
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
        screen = { StorageSettingsScreen() },
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
        // 快捷键本身只由桌面端的窗口按键回调消费（`desktopMain/Main.kt`），
        // Android 没有物理键盘语义（系统返回键 / 音量键不可拦），放上去是个空页。
        desktopOnly = true,
        screen = { ShortcutSettingsScreen() },
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
        screen = { AccountScreen() },
    ),
    SettingsEntry(
        id = "providers",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        titleOf = { it.settings.itemProviders.title },
        subtitleOf = { it.settings.itemProviders.subtitle },
        icon = Icons.Filled.Dns,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("音源", "导入", "模块", "切换", "provider", "module"),
        screen = { ProviderManagementScreen() },
    ),
)

/**
 * 「连接与集成」组。
 *
 * ⚠️ 这里原本是**一个**「本地服务器」巨石页（5 个分组 + 8 条说明），把两个方向相反的
 * 角色混在一起：
 * 1. **服务方（inbound）** —— 按自己的契约对外提供音频流与数据接口；
 * 2. **推送方（outbound）** —— 把流地址推给接收端，接收端定义接口、CPPlayer 适配它。
 *
 * 拆成两页后，每页只回答一个问题：「别人怎么连我」 vs 「我怎么连别人」。
 */
private fun connectivityEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "stream_output",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.settings.itemStreamOutput.title },
        subtitleOf = { it.settings.itemStreamOutput.subtitle },
        icon = Icons.Filled.SettingsEthernet,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("服务器", "端口", "绑定", "局域网", "令牌", "token", "端口占用", "stream", "server", "port"),
        screen = { StreamOutputSettingsScreen() },
    ),
    SettingsEntry(
        id = "integration",
        group = SettingsGroup.CONNECTIVITY,
        titleOf = { it.settings.itemIntegration.title },
        subtitleOf = { it.settings.itemIntegration.subtitle },
        icon = Icons.Filled.Api,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("推送", "接收端", "接口", "第三方", "集成", "api", "push", "receiver"),
        screen = { IntegrationSettingsScreen() },
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
        screen = { StandbySettingsScreen() },
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
        screen = { MessageNotifySettingsScreen() },
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
        screen = { AboutScreen() },
    ),
    SettingsEntry(
        id = "diagnostics",
        group = SettingsGroup.OTHER,
        titleOf = { it.settings.itemDiagnostics.title },
        subtitleOf = { it.settings.itemDiagnostics.subtitle },
        icon = Icons.Filled.BugReport,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf("调试", "健康", "日志", "接口", "错误", "health", "debug", "log"),
        screen = { HealthScreen() },
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
        screen = { RenderTuningSettingsScreen() },
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
        screen = { OnboardingScreen(replay = true) },
    ),
)
