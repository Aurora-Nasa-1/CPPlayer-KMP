package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.model.StorageSettingsModel
import cp.player.app.ui.model.formatBytes
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.description
import cp.player.app.ui.theme.displayName
import cp.player.app.ui.theme.isPlatformColorSourceAvailable
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify

/**
 * 外观与主题。
 *
 * ### 与重构前的差异
 *
 * 1. **状态只从 `AppModel` 的 StateFlow 读。** 旧版写的是
 *    `var themeMode by remember { mutableStateOf(AppModel.themeMode()) }` ——
 *    在页面里复制了一份持久值。别处（恢复默认、封面取色）改了它，这份副本不会同步，
 *    UI 会一直显示陈旧值。
 * 2. **「主题模式」「取色来源」改用分段控件。** 两者都是 3 选 1，走旧版的
 *    「下拉行 → 底部弹层」要「点开 + 点选」两次，而且当前值只能从副标题里猜；
 *    分段控件让三个选项直接可见，一次点击完成。
 * 3. **不可用的取色来源直接不显示**，而不是置灰。置灰的选项对用户是纯噪声 ——
 *    他既选不了，也看不出为什么。
 */
class AppearanceSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val themeMode by AppModel.themeModeFlow.collectAsState()
        val colorSource by AppModel.colorSourceFlow.collectAsState()
        val pureBlack by AppModel.pureBlackFlow.collectAsState()
        val bottomBarAutoHide by AppModel.bottomBarAutoHideFlow.collectAsState()
        val fontRoundness by AppModel.fontRoundnessFlow.collectAsState()
        val platformAvailable = isPlatformColorSourceAvailable()
        val defaultRoundness = cp.player.app.platform.defaultFontRoundness()

        val availableSources = remember(platformAvailable) {
            ColorSource.entries.filter { it != ColorSource.PLATFORM || platformAvailable }
        }

        // 拖动中的临时值：松手前只改显示，不写盘（桌面设置存储是全量回写，边拖边写打爆 IO）。
        // -1 表示不在拖动中。
        var draggingRoundness by remember { mutableIntStateOf(-1) }
        val displayedRoundness = if (draggingRoundness >= 0) draggingRoundness
        else fontRoundness ?: defaultRoundness

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("外观") {
                    SettingsSegmentedItem(
                        title = "主题模式",
                        options = ThemeMode.entries.map { it.displayName() },
                        selectedIndex = ThemeMode.entries.indexOf(themeMode).coerceAtLeast(0),
                        onSelect = { index ->
                            ThemeMode.entries.getOrNull(index)?.let(AppModel::setThemeMode)
                        },
                        index = 0,
                        total = 3,
                    )
                    SettingsSegmentedItem(
                        title = "取色来源",
                        options = availableSources.map { it.displayName() },
                        selectedIndex = availableSources.indexOf(colorSource).coerceAtLeast(0),
                        onSelect = { index ->
                            availableSources.getOrNull(index)?.let(AppModel::setColorSource)
                        },
                        index = 1,
                        total = 3,
                    )
                    SettingsSwitchItem(
                        title = "纯黑模式",
                        subtitle = "深色主题下使用纯黑背景，OLED 屏幕更省电",
                        checked = pureBlack,
                        onCheckedChange = AppModel::setPureBlack,
                        index = 2,
                        total = 4,
                    )
                    // 窄屏布局才有底栏；桌面宽屏走侧栏，这项开着也无副作用。
                    SettingsSwitchItem(
                        title = "自动隐藏底栏",
                        subtitle = "向上滑动内容时收起底部导航栏，向下滑动重新显示",
                        checked = bottomBarAutoHide,
                        onCheckedChange = AppModel::setBottomBarAutoHide,
                        index = 3,
                        total = 4,
                    )
                }
                SettingsSection("字体") {
                    SettingsSliderItem(
                        title = "字体圆滑度",
                        subtitle = "Google Sans Flex 的 ROND 可变轴：0 方正、100 最圆润。" +
                            "Android 16 及以上默认 100，其余平台默认 $defaultRoundness",
                        value = displayedRoundness.toFloat(),
                        onValueChange = { draggingRoundness = it.toInt() },
                        valueRange = 0f..100f,
                        steps = 19,
                        onValueChangeFinished = {
                            AppModel.setFontRoundness(displayedRoundness)
                            draggingRoundness = -1
                        },
                        valueLabel = displayedRoundness.toString() +
                            if (fontRoundness == null && displayedRoundness == defaultRoundness) " · 默认" else "",
                        index = 0,
                        total = if (fontRoundness != null) 2 else 1,
                    )
                    // 「恢复默认」只在用户自定义过之后出现：默认状态下它是个死按钮。
                    if (fontRoundness != null) {
                        SettingsButtonItem(
                            text = "恢复平台默认",
                            subtitle = "清除自定义值，回到当前平台的默认圆滑度",
                            icon = Icons.Filled.RestartAlt,
                            index = 1,
                            total = 2,
                            onClick = { AppModel.setFontRoundness(null) },
                        )
                    }
                }
                SettingsNote(
                    "字体圆滑度改动即时生效，会应用到整个界面的拉丁字符；" +
                        "中文字形来自系统回退字体，不受此设置影响。"
                )
                SettingsNote(colorSource.description(platformAvailable))
            }
        }

        CpRouteScaffold(
            title = "外观与主题",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
}

/**
 * 下载与存储（存储管理页）。
 *
 * ### 与初版的差异
 *
 * 初版只有「改下载目录 + 清缓存」两个动作，用户看不到任何数字 —— 「存储管理」名不副实。
 * 现在补上：
 *
 * 1. **下载区给出体量与条数**：已下载音乐一行显示「N 首 · 共 X」，点进去是下载管理页
 *    （那里能删文件）；占用数字取媒体库登记值，不做全盘扫描。
 * 2. **缓存区先给数字再给动作**：图片缓存行显示 Coil 磁盘缓存实时占用，清理按钮的反馈
 *    带「释放了多少」（清理前后各读一次）。
 * 3. **桌面端可直达目录**：更改目录保留；新增「打开目录」（资源管理器），
 *    用户能直接核对 / 备份下载的文件。
 * 4. **失败不再谎报成功**：打开目录失败、缓存清理失败都有对应提示。
 */
class StorageSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // rememberScreenModel 是 Screen 的扩展函数，只能在 Content() 里调用（见 AGENTS.md）。
        val model = rememberScreenModel { StorageSettingsModel() }
        StorageSettingsContent(
            model = model,
            onBack = { navigator.popOrNotify() },
            onOpenDownloads = { navigator.push(DownloadsScreen()) },
        )
    }
}

@Composable
private fun StorageSettingsContent(
    model: StorageSettingsModel,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val state by model.state.collectAsState()
    val downloadDir by AppModel.downloadDirFlow.collectAsState()
    val isAndroid = cp.player.app.platform.isAndroidPlatform()
    val pickDownloadDir = cp.player.app.platform.rememberDirectoryPicker { path ->
        if (!path.isNullOrBlank()) {
            AppModel.setDownloadDir(path)
            UiEvents.notify("下载目录已更新，仅对后续下载生效")
        }
    }

    val body: @Composable (Modifier) -> Unit = { pageModifier ->
        SettingsPage(pageModifier) {
            // ---- 下载区：桌面 3 行（下载音乐 / 目录 / 打开目录），Android 2 行 ----
            val downloadRows = if (isAndroid) 2 else 3
            SettingsSection("下载") {
                SettingsClickItem(
                    title = "已下载音乐",
                    subtitle = when {
                        state.downloadedCount == 0 -> "还没有下载内容，点右上角新建或搜索页下载"
                        else -> "${state.downloadedCount} 首 · 共 ${formatBytes(state.downloadedBytes)}"
                    },
                    index = 0,
                    total = downloadRows,
                    icon = Icons.Filled.MusicNote,
                    onClick = onOpenDownloads,
                )
                SettingsClickItem(
                    title = "下载目录",
                    subtitle = if (isAndroid) {
                        "Android 下载固定保存到应用私有目录"
                    } else {
                        downloadDir.ifBlank { "默认下载目录" }
                    },
                    index = 1,
                    total = downloadRows,
                    icon = Icons.Filled.FolderOpen,
                    onClick = if (isAndroid) null else ({ pickDownloadDir() }),
                )
                if (!isAndroid) {
                    SettingsClickItem(
                        title = "打开目录",
                        subtitle = "在文件管理器中查看已下载的文件",
                        index = 2,
                        total = downloadRows,
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            val target = downloadDir
                            val ok = target.isNotBlank() &&
                                cp.player.app.platform.openInFileManager(target)
                            if (!ok) UiEvents.notify("打开目录失败：${target.ifBlank { "尚未设置下载目录" }}")
                        },
                    )
                }
            }

            // ---- 缓存区：体量展示 + 清理动作 ----
            SettingsSection("缓存") {
                SettingsClickItem(
                    title = "图片缓存",
                    subtitle = when {
                        state.imageCacheBytes < 0 -> "统计中…"
                        else -> "占用 ${formatBytes(state.imageCacheBytes)}"
                    },
                    index = 0,
                    total = 2,
                    icon = Icons.Filled.PhotoLibrary,
                    onClick = null,
                )
                SettingsButtonItem(
                    text = "清理图片缓存",
                    subtitle = "释放封面等图片占用的空间；已下载的歌曲不受影响",
                    index = 1,
                    total = 2,
                    icon = Icons.Filled.CleaningServices,
                    onClick = { model.clearImageCache() },
                )
            }

            SettingsNote(
                if (isAndroid) {
                    "下载目录的改动仅对后续下载生效，已下载的文件不会移动。"
                } else {
                    "下载目录的改动仅对后续下载生效，已下载的文件不会移动；如需迁移，可在打开目录后手动移动文件。"
                }
            )
        }
    }

    CpRouteScaffold(
        title = "下载与存储",
        onBack = onBack,
    ) { pageModifier -> body(pageModifier) }
}
