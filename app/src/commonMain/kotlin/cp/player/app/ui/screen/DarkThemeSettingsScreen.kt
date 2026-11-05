package cp.player.app.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.DarkThemePhonePreviewCard
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.description
import cp.player.app.ui.theme.displayName
import cp.player.app.ui.theme.isPlatformColorSourceAvailable
import cp.player.app.ui.util.popOrNotify

/**
 * 贴合原生 Android 16「深色主题与色彩」的二级设置页面。
 *
 * 具有：
 * - 顶部 Android 16 原生拟真手机机身与日月天体动效卡片（[DarkThemePhonePreviewCard]）
 * - 外观模式（跟随系统 / 浅色 / 深色）单选层级
 * - AMOLED 纯黑极致省电与对比度增强开关
 * - Android Material You (Monet) 动态壁纸与封面取色配置
 */
class DarkThemeSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val themeMode by AppModel.themeModeFlow.collectAsState()
        val pureBlack by AppModel.pureBlackFlow.collectAsState()
        val colorSource by AppModel.colorSourceFlow.collectAsState()
        val isDark = LocalIsDarkTheme.current
        val platformAvailable = isPlatformColorSourceAvailable()

        val availableSources = remember(platformAvailable) {
            ColorSource.entries.filter { it != ColorSource.PLATFORM || platformAvailable }
        }

        CpRouteScaffold(
            title = s.appearance.themeMode,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                // 1. 顶部拟真 Android 16 手机模型动效预览卡片
                DarkThemePhonePreviewCard(
                    isDark = isDark,
                    pureBlack = pureBlack,
                    colorSource = colorSource,
                    allowInteractiveToggle = true,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                // 2. 外观主题模式
                SettingsSection("外观模式") {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        val selected = mode == themeMode
                        val (modeIcon, modeDesc) = when (mode) {
                            ThemeMode.SYSTEM -> Icons.Filled.Brightness4 to "根据系统日间/夜间状态自动切换"
                            ThemeMode.LIGHT -> Icons.Filled.LightMode to "始终保持明亮清爽的浅色视觉"
                            ThemeMode.DARK -> Icons.Filled.DarkMode to "始终保持深色暗黑风格，夜间更护眼"
                        }

                        SettingsClickItem(
                            title = mode.displayName(s),
                            subtitle = modeDesc,
                            icon = modeIcon,
                            selected = selected,
                            trailingContent = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                }
                            } else null,
                            index = index,
                            total = ThemeMode.entries.size,
                            onClick = { AppModel.setThemeMode(mode) },
                        )
                    }
                }

                // 3. 深色优化项
                SettingsSection("深色显示增强") {
                    SettingsSwitchItem(
                        title = s.appearance.pureBlack,
                        subtitle = s.appearance.pureBlackNote,
                        checked = pureBlack,
                        onCheckedChange = AppModel::setPureBlack,
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote("开启纯黑后，在深色模式下应用底色将由深灰压至绝对纯黑 (#000000)，OLED 屏幕黑色像素不发光，不仅对比度更突出，还能有效节省电量。")

                // 4. 色彩系统与取色来源
                SettingsSection(s.appearance.colorSource) {
                    SettingsSegmentedItem(
                        title = s.appearance.colorSource,
                        subtitle = colorSource.displayName(s),
                        icon = Icons.Filled.Palette,
                        options = availableSources.map { it.displayName(s) },
                        selectedIndex = availableSources.indexOf(colorSource).coerceAtLeast(0),
                        onSelect = { index ->
                            availableSources.getOrNull(index)?.let(AppModel::setColorSource)
                        },
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote(colorSource.description(s, platformAvailable))
            }
        }
    }
}
