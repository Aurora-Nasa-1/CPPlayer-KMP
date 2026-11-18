package cp.player.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 页面 / 卡片的**语义层级令牌**。
 *
 * M3 的 surface 色阶是**一组有序的容器色**，不是「背景色 + 卡片色」两个名字。
 * 真正决定观感的是**相邻两级之间的明度差**：差 1 级（如 `surface` 98 与
 * `surfaceContainerLow` 96）在浅色下几乎同色，卡片会「糊在页面上」——
 * 只能靠阴影或描边补层级，这正是本仓库历史上「阴影用得多」「纯黑模式必须描边」
 * 这类补丁的根因。
 *
 * 因此这里固定一套 3 层语义，页面与卡片**至少差 3 级**：
 *
 * | 层 | 浅色 | 深色 | 用途 |
 * |---|---|---|---|
 * | [cpPageSurface] | `surfaceContainer`（Tone 94） | `surfaceContainer`（Tone 12） | 页面 / 脚手架底色 |
 * | [cpCardSurface] | `surfaceContainerLowest`（Tone 100） | `surfaceContainerHighest`（Tone 22） | 卡片、列表行、分组块 |
 * | [cpNestedSurface] | `surfaceContainerLow`（Tone 96） | `surfaceContainerHigh`（Tone 17） | 卡片**内部**的次级容器（嵌套卡、输入框底、chip） |
 *
 * ⚠️ **深色不是浅色的镜像**：tone 越低越暗，所以「页面暗、卡片亮」在深色下要翻过来取。
 * 直接写 `surfaceContainerLowest` 当卡片色，深色下会得到一块比页面更黑的卡片。
 *
 * ⚠️ **页面层不要用 `surfaceContainerLowest`**：深色下它是 Tone 4（`#0E0E13`），
 * 纯黑模式里更是被压成 `#000` —— 整窗会看起来「背景是纯黑的」。页面要的是一块
 * 有色调的底，`surfaceContainer`（深色 Tone 12）才是对的那一档。
 *
 * ⚠️ **必须读 [LocalIsDarkTheme]，不要读 `isSystemInDarkTheme()`**：本应用允许
 * 用户显式指定深浅色，两者在「用户选深色但系统是浅色」时不一致，读错会取到反色板。
 *
 * ⚠️ 纯黑模式（`Theme.withPureBlackSurfaces`）只把 `surface` 与
 * `surfaceContainerLowest` 压成纯黑，`surfaceContainerHighest` 保留极暗灰 ——
 * 因此 [cpCardSurface] 在深色下仍然可见，层级不会塌掉。
 */
@Composable
fun cpPageSurface(): Color = MaterialTheme.colorScheme.surfaceContainer

/** 卡片 / 列表行 / 分组块的底色。见 [cpPageSurface] 的层级表。 */
@Composable
fun cpCardSurface(): Color =
    if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceContainerLowest

/** 卡片**内部**的次级容器（嵌套卡、输入框底、chip）。见 [cpPageSurface] 的层级表。 */
@Composable
fun cpNestedSurface(): Color =
    if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerLow

/**
 * 底部弹层 / 对话框的底色。
 *
 * 浮层比卡片**再高一层**：它盖在页面上、与内容区完全不同层，浅色取纯白
 * （`surfaceContainerLowest`），深色取 `surfaceContainerHigh`（比卡片略暗、
 * 比页面亮），保证「浮起感」不是靠阴影撑出来的。
 */
@Composable
fun cpSheetSurface(): Color =
    if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerLowest

/** 浮动工具条 / 悬浮控制胶囊 / 悬浮播放条的底色。与 [cpSheetSurface] 同层。 */
@Composable
fun cpElevatedSurface(): Color = cpSheetSurface()

/**
 * **浮层内部**的次级块（对话框里的说明块、可选行，底部弹层里的行）。
 *
 * 不能复用 [cpCardSurface]：浮层本身已经占了一档，浮层里的块若再用卡片档，
 * 浅色下两者都是纯白（`Lowest`），整块糊在一起；深色下则都是 `Highest`。
 * 这里刻意比浮层底再退一档：
 *
 * - 浅色：浮层 `Lowest`（100）→ 块 `Low`（96）
 * - 深色：浮层 `High`（17）→ 块 `Highest`（22）
 */
@Composable
fun cpSheetInnerSurface(): Color =
    if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceContainerLow
