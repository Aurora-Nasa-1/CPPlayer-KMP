package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 平台是否提供**系统级**动态配色（真正的壁纸 Monet）。
 *
 * 只有 Android 12+ 为 true。桌面没有等价机制 —— Windows 只给「一个强调色」，
 * 需要 materialkolor 才能展开成完整 M3 方案，所以它走 [platformAccentSeed] 那条路，
 * 这里恒为 false。
 */
@Composable
expect fun supportsPlatformDynamicScheme(): Boolean

/**
 * 平台动态配色方案（壁纸 Monet）。仅 Android 12+ 返回非 null，桌面恒为 null。
 *
 * ⚠️ **实现必须自己 `remember`**：material3 的 `ColorScheme` 没有重写 `equals`，
 * 每次重组都返回新实例会让 `animateColorScheme` 内部的
 * `updateTransition(targetState = …)` 永远重启动画 —— 表现为主题一直在缓慢漂移
 * 且持续吃 CPU。桌面侧恒返回 null，不存在这个问题。
 */
@Composable
expect fun platformDynamicScheme(dark: Boolean): ColorScheme?

/**
 * 平台系统强调色，用作 materialkolor 的种子色。
 *
 * - 桌面：Windows 的 DWM 强调色；读不到（非 Windows / 用户从未自定义）返回 null
 * - Android：恒返回 null —— Android 的系统色是完整的壁纸 Monet，
 *   用「一个种子色」复现不出来，所以它走 [platformDynamicScheme]
 */
expect fun platformAccentSeed(): Color?

/**
 * 系统壁纸的种子色，用于 [ColorSource.COVER] 但**当前没有曲目封面**时的回退
 * （未播放 / 曲目无封面）。两端的回退路径不同，这里只覆盖「需要种子色」的那一端：
 *
 * - **桌面**：读系统壁纸图片 → 量化成种子色。取不到（非 Windows / 壁纸是动态壁纸且
 *   拿不到静态帧）返回 null，主题继续回退到 [DefaultSeedColor]。
 * - **Android**：**恒返回 null**。安卓的壁纸色不是「一个种子色」而是整套 Monet 方案，
 *   由 [platformDynamicScheme] 直接给出 —— 回退分支见 `CpTheme`，那里会优先取平台方案。
 *
 * ⚠️ 桌面实现会读文件 + 解码，**是阻塞操作**（几十到几百毫秒），
 * 调用方必须放在后台线程；**绝不能在 composition 里同步调用**。
 */
expect fun platformWallpaperSeed(): Color?

/**
 * 当前平台能否为 [ColorSource.PLATFORM] 提供颜色。
 *
 * 两条路径任一可用即可：
 * - Android 12+：壁纸 Monet（[supportsPlatformDynamicScheme]）
 * - 桌面：Windows 强调色（[platformAccentSeed] 非 null）
 *
 * 设置页用它决定「系统」这一项是否可点，**不要**把判断写死在 UI 里 ——
 * 平台的可用条件将来还会变（比如 macOS 加进来）。
 */
@Composable
fun isPlatformColorSourceAvailable(): Boolean =
    supportsPlatformDynamicScheme() || platformAccentSeed() != null
