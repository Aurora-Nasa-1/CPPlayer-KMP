package cp.player.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive (M3E) 统一语义表面层级令牌。
 *
 * M3 的 surface 色阶是一组**有序的容器角色**，而非简单的「背景 + 卡片」。
 * 真正决定空间层次感的是**垂直进深与相邻层级之间的明度阶梯 (Tonal Stepping)**：
 *
 * ### 双模式层级架构 (Dual Surface Paradigm)：
 * 1. **模式 A：标准内容流模式 (Standard Canvas)**
 *    - 页面底色：[CpSurfaceTokens.canvas] (`surface`，浅色 Tone 98 / 深色 Tone 6 / OLED 纯黑 #000000)
 *    - 卡片层级：[CpSurfaceTokens.standardCard] (`surfaceContainerLow` 浅 / `surfaceContainer` 深)
 *    - 适用：播放大页、歌词全屏、媒体扁平列表。
 *
 * 2. **模式 B：卡片分组模式 (Grouped / Bento Canvas)**
 *    - 页面底色：[CpSurfaceTokens.grouped] (`surfaceContainer`，浅色 Tone 94 / 深色 Tone 12)
 *    - 卡片层级：[CpSurfaceTokens.groupedCard] (`surfaceContainerLowest` 浅 Tone 100 / `surfaceContainerHigh` 深 Tone 17)
 *    - 卡片内嵌：[CpSurfaceTokens.nested] (`surfaceContainerLow` 浅 Tone 96 / `surfaceContainer` 深 Tone 12)
 *    - 适用：设置页、表单页、仪表盘、Bento 结构化卡片流。
 *
 * ### 顶层浮层与遮罩 (Overlays & Sheets)：
 * - 浮层底色：[CpSurfaceTokens.overlay]
 *   - 浅色：`surfaceContainerLowest` (Tone 100 纯白) 配合 Scrim 遮罩
 *   - 深色：**`surfaceContainerHighest` (Tone 22)**，严格保证明度高于所有普通卡片 (Tone 17) 与页面 (Tone 12)，消除倒挂。
 * - 浮层内嵌次级块：[CpSurfaceTokens.overlayInner]
 *   - 浅色：`surfaceContainerLow` (Tone 96，比浮层 100 暗 4 阶，内凹感 Inset)
 *   - 深色：`surfaceContainerHigh` (Tone 17，比浮层 22 暗 5 阶，内凹感 Inset)
 *
 * ⚠️ **深色模式下更高层级对应更亮色阶**：
 * `overlay` (22) > `groupedCard` (17) > `grouped` (12) > `canvas` (6)。
 * 任何悬浮对话框、抽屉、菜单在深色下均具有最强的前置浮起感。
 */
object CpSurfaceTokens {

    /** 模式 A (标准内容流)：以纯净 surface 为底，支持 OLED 绝对纯黑 */
    @Composable
    fun canvas(): Color = MaterialTheme.colorScheme.surface

    /** 模式 B (卡片分组/设置/仪表盘)：以有调性的 surfaceContainer 为底，纯黑模式下为纯黑 surface */
    @Composable
    fun grouped(): Color =
        if (LocalPureBlack.current) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainer

    /** 模式 A 内容流卡片：浮于 canvas 之上 */
    @Composable
    fun standardCard(): Color =
        if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerLow

    /** 模式 B 分组卡片 (BentoCard)：浮于 grouped 之上 */
    @Composable
    fun groupedCard(): Color =
        if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLowest

    /** 卡片内部内陷槽 (搜索框底色、Chip、嵌套小块) */
    @Composable
    fun nested(): Color =
        if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerLow

    /**
     * 对话框、底部抽屉、悬浮条、右键菜单底色。
     * 深色下强制取 Highest (Tone 22)，绝对高于任何普通卡片！
     */
    @Composable
    fun overlay(): Color =
        if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerLowest

    /** 悬浮层内部的次级块 (日志容器、选项行) */
    @Composable
    fun overlayInner(): Color =
        if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow
}

/** 页面底色别名：默认映射到模式 B 分组底色，保持平滑兼容 */
@Composable
fun cpPageSurface(): Color = CpSurfaceTokens.grouped()

/** 画布底色别名：模式 A 纯净底色 */
@Composable
fun cpCanvasSurface(): Color = CpSurfaceTokens.canvas()

/** 分组底色别名：模式 B 结构底色 */
@Composable
fun cpGroupedSurface(): Color = CpSurfaceTokens.grouped()

/** 卡片 / 列表行 / 分组块的底色 */
@Composable
fun cpCardSurface(): Color = CpSurfaceTokens.groupedCard()

/** 卡片内部的次级容器 (嵌套卡、输入框底、chip) */
@Composable
fun cpNestedSurface(): Color = CpSurfaceTokens.nested()

/** 底部弹层 / 对话框的底色 */
@Composable
fun cpSheetSurface(): Color = CpSurfaceTokens.overlay()

/** 浮动工具条 / 悬浮控制胶囊 / 悬浮播放条的底色。与 [cpSheetSurface] 同层 */
@Composable
fun cpElevatedSurface(): Color = CpSurfaceTokens.overlay()

/** 浮层内部的次级块 (对话框里的说明块、可选行，底部弹层里的行) */
@Composable
fun cpSheetInnerSurface(): Color = CpSurfaceTokens.overlayInner()
