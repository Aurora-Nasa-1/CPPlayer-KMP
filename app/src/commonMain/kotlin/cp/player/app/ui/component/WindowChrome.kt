package cp.player.app.ui.component

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 窗口级顶栏（桌面自绘标题栏）是否已经接管了「账号 / 设置」两个入口。
 *
 * 由 [cp.player.app.App] 依据 `titleBar` 槽位**是否被注入**来提供：桌面端注入自绘标题栏后为
 * `true`，Android 端恒为 `false`。`MainScreen` 据此决定要不要在自己的 `AppTopBar` /
 * `DesktopSidebar` 里重复渲染这两个入口。
 *
 * ## 为什么判据是「槽位是否被注入」而不是「是不是桌面」
 *
 * 两个理由，都是踩过的坑：
 * - `MainScreen` 的展开态分支条件是**宽度 ≥840dp**，不是平台 —— 宽屏 Android 平板走的是同一套
 *   `AppTopBar` 与 `DesktopSidebar`。拿平台去判断会让平板丢掉这两个入口。
 * - 将来若给无边框加一个「回退到系统标题栏」的开关（无边框在个别机器上出问题时的自救通道），
 *   那时桌面端仍然 `isDesktop == true`，但**并没有标题栏**，两个入口会凭空消失。
 *
 * 用 `staticCompositionLocalOf` 而非 `compositionLocalOf`：这个值在一次组合里是常量，
 * 不会中途变化；static 版本读起来没有快照订阅开销，正是它该用的场景。
 */
val LocalWindowChromeActive = staticCompositionLocalOf { false }
