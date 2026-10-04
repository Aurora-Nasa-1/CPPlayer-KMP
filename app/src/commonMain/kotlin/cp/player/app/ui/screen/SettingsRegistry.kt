package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.core.screen.Screen
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
enum class SettingsGroup(val title: String) {
    /** 跟内容本身无关的偏好：长什么样、怎么播、存哪。 */
    GENERAL("通用"),

    /** 我是谁、我从哪个音源拿内容。 */
    ACCOUNT_AND_PROVIDER("账号与音源"),

    /** 和本机之外的软件打交道。 */
    CONNECTIVITY("连接与集成"),

    /** 低频 + 开发者向。放最末；其中排查/开发专用的入口只在 debug 构建展示（`debugOnly`）。 */
    OTHER("其他"),
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
 * @param keywords 搜索同义词（「纯黑」→ oled / 省电 / amoled）
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
    val title: String,
    val subtitle: String,
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
        id = "appearance",
        group = SettingsGroup.GENERAL,
        title = "外观与主题",
        subtitle = "主题模式、取色来源与纯黑背景",
        icon = Icons.Filled.Palette,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("主题", "深色", "浅色", "暗黑", "取色", "配色", "纯黑", "oled", "省电", "theme", "dark"),
        screen = { AppearanceSettingsScreen() },
    ),
    SettingsEntry(
        id = "playback",
        group = SettingsGroup.GENERAL,
        title = "播放与音质",
        subtitle = "默认音质与睡眠定时",
        icon = Icons.Filled.PlayArrow,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("音质", "无损", "hires", "定时", "睡眠", "关闭", "quality", "sleep"),
        screen = { PlaybackSettingsScreen() },
    ),
    SettingsEntry(
        id = "storage",
        group = SettingsGroup.GENERAL,
        title = "下载与存储",
        subtitle = "下载目录、歌曲缓存与图片缓存",
        icon = Icons.Filled.Storage,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf(
            "下载", "目录", "缓存", "清理", "空间", "存储", "cache", "download",
            // 无损流落盘是磁盘占用最大的一块，用户找它时用的多半是这几个词。
            "歌曲缓存", "无损", "离线", "接口缓存", "命中率",
        ),
        screen = { StorageSettingsScreen() },
    ),
)

private fun accountEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "account",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        title = "账号与登录",
        subtitle = "登录音源账号、切换与管理已保存的账号",
        icon = Icons.Filled.Person,
        accent = SettingsAccent.PRIMARY,
        keywords = listOf("账号", "登录", "退出", "扫码", "邮箱", "手机", "多账号", "隔离", "account", "login"),
        screen = { AccountScreen() },
    ),
    SettingsEntry(
        id = "providers",
        group = SettingsGroup.ACCOUNT_AND_PROVIDER,
        title = "音源管理",
        subtitle = "导入、切换或移除音源模块",
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
        title = "本地流输出",
        subtitle = "把音频流通过 HTTP 对外提供",
        icon = Icons.Filled.SettingsEthernet,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("服务器", "端口", "绑定", "局域网", "令牌", "token", "端口占用", "stream", "server", "port"),
        screen = { StreamOutputSettingsScreen() },
    ),
    SettingsEntry(
        id = "integration",
        group = SettingsGroup.CONNECTIVITY,
        title = "外部推送与集成",
        subtitle = "推送到接收端、开放第三方接口",
        icon = Icons.Filled.Api,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("推送", "接收端", "接口", "第三方", "集成", "api", "push", "receiver"),
        screen = { IntegrationSettingsScreen() },
    ),
    SettingsEntry(
        id = "standby",
        group = SettingsGroup.CONNECTIVITY,
        title = "激进保活",
        subtitle = "熄屏后维持在线，让设备发现与换设备播放仍可能命中",
        icon = Icons.Filled.Wifi,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf(
            "保活", "后台", "常驻", "熄屏", "锁屏", "掉线", "搜不到", "设备发现",
            "wifi", "组播", "standby", "background", "keepalive",
        ),
        androidOnly = true,
        screen = { StandbySettingsScreen() },
    ),
)

private fun otherEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = "about",
        group = SettingsGroup.OTHER,
        title = "关于与支持",
        subtitle = "版本、更新与项目支持",
        icon = Icons.Filled.Info,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("版本", "更新", "项目", "主页", "赞助", "支持", "about", "version", "update"),
        screen = { AboutScreen() },
    ),
    SettingsEntry(
        id = "diagnostics",
        group = SettingsGroup.OTHER,
        title = "诊断",
        subtitle = "查看接口调用状态、日志与回退信息",
        icon = Icons.Filled.BugReport,
        accent = SettingsAccent.TERTIARY,
        keywords = listOf("调试", "健康", "日志", "接口", "错误", "health", "debug", "log"),
        screen = { HealthScreen() },
    ),
    SettingsEntry(
        id = "render_tuning",
        group = SettingsGroup.OTHER,
        title = "渲染后端",
        subtitle = "显示后端与垂直同步；画面撕裂或卡顿时可调整",
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
        title = "重看新手引导",
        subtitle = "重新走一遍首次使用引导",
        icon = Icons.Filled.WavingHand,
        accent = SettingsAccent.SECONDARY,
        keywords = listOf("引导", "教程", "新手", "onboarding", "tutorial"),
        // 主要用于开发期验证引导流程的改动；正式版用户没有重看的场景。
        debugOnly = true,
        screen = { OnboardingScreen(replay = true) },
    ),
)
