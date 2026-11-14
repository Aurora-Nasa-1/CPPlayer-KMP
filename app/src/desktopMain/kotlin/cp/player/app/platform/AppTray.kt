package cp.player.app.platform

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cp.player.app.AppModel
import cp.player.app.AppWindowIcon
import cp.player.app.i18n.CpStrings
import cp.player.app.ui.util.DesktopShell
import dev.nucleusframework.composenativetray.menu.api.ComposableTrayMenuScope
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.nucleusframework.core.runtime.Platform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 本机是否有可用的系统托盘。
 *
 * ComposeNativeTray 在 Windows / macOS / Linux 上各有原生实现，不依赖 AWT 的
 * `SystemTray.isSupported()`。Linux 上如果桌面环境连 AppIndicator / StatusNotifier
 * 都没有，原生侧会自行报错，这里只能按 OS 判断 —— 因此「最小化到托盘」仍然保留
 * [cp.player.app.platform.AppTray] 必须已挂载这一前提，调用方不能把它当成
 * 「一定能把窗口捞回来」的保证。
 */
internal val desktopTraySupported: Boolean
    get() = when (Platform.Current) {
        Platform.Windows, Platform.MacOS, Platform.Linux -> true
        else -> false
    }

/**
 * 托盘是否需要出现。
 *
 * 沿用旧实现的「懒创建」语义：平时不占托盘位；只有用户选择「最小化到托盘」、
 * 或在迷你窗里点最小化时才挂载。通知不再依赖托盘（见
 * `PlatformNotifications.desktop.kt`），所以这里不必为了推送而提前安装。
 */
internal object DesktopTrayState {
    var isVisible by mutableStateOf(false)
        private set

    /** @return 托盘可用；`false` 时调用方必须退回「真退出」。 */
    fun ensureInstalled(): Boolean {
        if (!desktopTraySupported) return false
        isVisible = true
        return true
    }
}

/** 托盘菜单只关心这三件事；播放进度每 200ms 变一次，不能让它驱动整棵托盘重组。 */
private data class TrayPlayback(
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
)

/**
 * 桌面托盘（无 AWT）。
 *
 * ## 为什么是 composable
 *
 * 图标与右键菜单都由 ComposeNativeTray 的平台原生实现持有：Windows 是
 * `Shell_NotifyIcon`，macOS 是 `NSStatusItem`，Linux 是 AppIndicator /
 * StatusNotifierItem。全程不碰 `java.awt.SystemTray` / `TrayIcon` / `PopupMenu`，
 * 因此也不需要 AWT 事件循环、`BufferedImage` 或离屏渲染 + `JWindow` 贴图那一套。
 *
 * 菜单用库提供的 composable DSL（`Item` / `CheckableItem` / `SubMenu` / `Divider`）
 * 直接渲染成**平台原生菜单**：勾选态、禁用态、图标都由系统绘制。文案读
 * `CpStrings`，语言切换会触发重组并重建菜单，不会出现「中文界面里混一句英文」。
 *
 * ## 生命周期
 *
 * 必须放在 `nucleusApplication { ... }` 的应用级组合里、且**在窗口内容之外**：
 * 托盘要活得比任何一个窗口都久（窗口隐藏后它是唯一入口）。离开组合时库会
 * `NativeTray.dispose()` 摘掉图标。
 *
 * @param onShowWindow 唤回主窗口。托盘左键点击、菜单「显示主界面」、退出迷你模式
 *   都走这一条。
 * @param onExit 菜单「退出」。
 */
@Composable
internal fun AppTray(
    onShowWindow: () -> Unit,
    onExit: () -> Unit,
) {
    val language by AppModel.appLanguageFlow.collectAsState()
    val strings = CpStrings.of(language).messageNotify

    // 只收集「曲名 / 歌手 / 是否在播」这三个字段：直接 collect 整个 PlaybackUiState
    // 会让进度每 200ms 把托盘菜单重组一遍。
    var playback by remember {
        mutableStateOf(TrayPlayback(title = null, artist = null, isPlaying = false))
    }
    LaunchedEffect(Unit) {
        runCatching { AppModel.playback.state }.getOrNull()?.let { state ->
            state
                .map { current ->
                    TrayPlayback(
                        title = current.currentTrack?.name?.takeIf { it.isNotBlank() },
                        artist = current.currentTrack?.artist?.takeIf { it.isNotBlank() },
                        isPlaying = current.isPlaying,
                    )
                }
                .distinctUntilChanged()
                .collect { playback = it }
        }
    }

    val nowPlaying = playback.title?.let { title ->
        playback.artist?.let { "$title — $it" } ?: title
    } ?: strings.trayNothingPlaying
    val hasTrack = playback.title != null

    val menu: @Composable ComposableTrayMenuScope.() -> Unit = {
        Item(label = nowPlaying, isEnabled = false)
        Divider()
        Item(
            label = strings.trayPrevious,
            icon = Icons.Filled.SkipPrevious,
            isEnabled = hasTrack,
            onClick = { runCatching { AppModel.playback.skipPrevious() } },
        )
        Item(
            label = if (playback.isPlaying) strings.trayPause else strings.trayPlay,
            icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            isEnabled = hasTrack,
            onClick = { runCatching { AppModel.playback.togglePlayPause() } },
        )
        Item(
            label = strings.trayNext,
            icon = Icons.Filled.SkipNext,
            isEnabled = hasTrack,
            onClick = { runCatching { AppModel.playback.skipNext() } },
        )
        Divider()
        Item(
            label = strings.trayShowWindow,
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = onShowWindow,
        )
        CheckableItem(
            label = strings.trayMiniMode,
            icon = Icons.Outlined.PictureInPictureAlt,
            checked = DesktopShell.isMiniMode,
            onCheckedChange = { checked ->
                DesktopShell.isMiniMode = checked
                // 进迷你模式后窗口尺寸变小，如果它当时是隐藏的必须先捞回来，
                // 否则用户看到的是「勾了一下，什么都没发生」。
                if (checked) onShowWindow()
            },
        )
        Divider()
        Item(
            label = strings.trayExit,
            icon = Icons.Outlined.PowerSettingsNew,
            onClick = onExit,
        )
    }

    // Windows 用应用自己的彩色图标；macOS / Linux 的菜单栏图标要跟随明暗主题，
    // 交给库用 ImageVector 自动 tint（传 null tint 时它按系统外观选黑/白）。
    val windowIcon = AppWindowIcon.painter
    if (windowIcon != null) {
        Tray(
            windowsIcon = windowIcon,
            macLinuxIcon = Icons.Filled.MusicNote,
            tooltip = "CPPlayer",
            primaryAction = onShowWindow,
            menuContent = menu,
        )
    } else {
        Tray(
            icon = Icons.Filled.MusicNote,
            tooltip = "CPPlayer",
            primaryAction = onShowWindow,
            menuContent = menu,
        )
    }
}
