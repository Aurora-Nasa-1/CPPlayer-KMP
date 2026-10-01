package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.description
import cp.player.app.ui.theme.displayName
import cp.player.app.ui.theme.isPlatformColorSourceAvailable
import cp.player.app.ui.util.UiEvents

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
        val platformAvailable = isPlatformColorSourceAvailable()

        val availableSources = remember(platformAvailable) {
            ColorSource.entries.filter { it != ColorSource.PLATFORM || platformAvailable }
        }

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
                        total = 3,
                    )
                }
                SettingsNote(colorSource.description(platformAvailable))
            }
        }

        CpRouteScaffold(
            title = "外观与主题",
            onBack = { navigator.pop() },
        ) { pageModifier -> body(pageModifier) }
    }
}

/**
 * 下载与存储。
 *
 * ### 与重构前的差异
 *
 * 「清理图片缓存」原本铺的是 `errorContainer`（破坏性配色），但它**不是破坏性操作** ——
 * 只是丢掉可以重新下载的封面缓存。破坏性配色会让用户以为会丢数据，从而不敢点。
 * 现在用中性配色，并把「不会删除已下载的歌曲」写进副标题。
 */
class StorageSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
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
                SettingsSection("下载") {
                    SettingsClickItem(
                        title = "下载目录",
                        subtitle = if (isAndroid) {
                            "Android 下载固定保存到应用私有目录"
                        } else {
                            downloadDir.ifBlank { "默认下载目录" }
                        },
                        index = 0,
                        total = 1,
                        icon = Icons.Filled.FolderOpen,
                        onClick = if (isAndroid) null else ({ pickDownloadDir() }),
                    )
                }
                SettingsSection("缓存") {
                    SettingsButtonItem(
                        text = "清理图片缓存",
                        subtitle = "释放封面等图片占用的空间；已下载的歌曲不受影响",
                        index = 0,
                        total = 1,
                        onClick = {
                            val cleared = cp.player.app.platform.clearImageCache()
                            UiEvents.notify(if (cleared) "图片缓存已清理" else "缓存清理失败")
                        },
                    )
                }
                SettingsNote("下载目录的改动仅对后续下载生效，已下载的文件不会移动。")
            }
        }

        CpRouteScaffold(
            title = "下载与存储",
            onBack = { navigator.pop() },
        ) { pageModifier -> body(pageModifier) }
    }
}
