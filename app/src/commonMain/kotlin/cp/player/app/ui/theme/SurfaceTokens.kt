package cp.player.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive **分组界面**（grouped settings / bento card）的表面层级令牌。
 *
 * ### 核心规则：卡片恒定浮起 —— 深浅两种模式下卡片都比背景亮
 *
 * 这是 M3E 设置页的分组模型，和「普通内容流的单调容器阶梯」是**两套不同的模型**：
 *
 * - 单调阶梯：越强调越远离 `surface`，所以浅色下容器比页面**暗**、深色下比页面**亮**；
 * - **分组模型**：底层是带主题色调的容器色（偏深），卡片是更亮的表面（白 / 亮灰），
 *   **深浅两色卡片都比背景亮**。Android 16 系统设置、Gmail 的 M3E 改版都是这套 ——
 *   官方描述即「容器整体再以更明亮的色调与背景区分」。
 *
 * 本项目原本就是分组模型（`SettingsKit` 里还留着原注释：「页面 `surfaceContainer` T94、
 * 卡片 `surfaceContainerLowest` T100」）。曾把它误判成「阶梯倒置的 bug」改成
 * 「页面 `surface` + 卡片 `surfaceContainer`」，于是浅色变成白底灰卡、卡片糊在页面上 ——
 * 这正是「卡片不明显 / 层级奇怪」的根源。现在改回分组模型。
 *
 * ### 为什么令牌要按明暗分支
 *
 * M3 的 `Lowest`/`High` 是**相对 surface 的强调方向**，不是绝对明度：
 * 浅色下 `Lowest`=T100（白）、深色下 `Lowest`=#000（黑），是同一个角色的两端。
 * 要让「卡片永远亮于页面」，浅色必须取 `Lowest`、深色必须取 `High` —— 写死一个角色
 * 必然有一端是黑卡。分支集中在令牌这一处，语义固定为「卡片浮起」，不会漂移。
 *
 * ### 层级映射
 *
 * | 语义 | 浅色角色 | 深色角色 | 纯黑 |
 * |---|---|---|---|
 * | [canvas] / [grouped] 页面 | `surfaceContainer` T94 带染色 | `surface` L\*6 / `#000` | `#000` |
 * | [standardCard] / [groupedCard] 卡片 | `surfaceContainerLowest` T100 白 | `surfaceContainerHigh` L\*18 | L\*6.1 |
 * | [nested] 卡片内嵌槽（凹陷） | `surfaceContainerLow` T96 | `surfaceContainer` L\*13 | L\*3 |
 * | [overlay] 浮层 | `surfaceContainerLowest` T100 白 | `surfaceContainerHighest` L\*23 | L\*9 |
 * | [overlayInner] 浮层内块 | `surfaceContainerLow` T96 | `surfaceContainerHigh` L\*18 | L\*6.1 |
 *
 * ### 为什么浅色浮层和卡片同色（都是 T100 白）
 *
 * 浅色卡片已经是最高阶的白，浮层不可能更亮。M3 的对话框 / 底部抽屉本就用白底，
 * 靠 **scrim + elevation 阴影** 与卡片区分，不靠色阶。深色下浮层仍比卡片亮一档
 * （L\*23 vs L\*18）。
 *
 * ### 内嵌槽方向
 *
 * [nested] 是**卡片内部的凹陷**（搜索框、Chip、嵌套小块），比所在卡片暗一档：
 * 白卡里是浅灰槽（T96 < T100）、亮卡里是深灰槽（L\*13 < L\*18），深浅同向。
 */
object CpSurfaceTokens {

    /** 页面底色：浅色带染色的容器灰，深色为 surface。纯黑模式下为 `#000`。 */
    @Composable
    fun canvas(): Color = if (LocalIsDarkTheme.current) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    /** 分组页底色。与 [canvas] 同值：分组页和页面只有卡片布局差别，底色只有一种。 */
    @Composable
    fun grouped(): Color = canvas()

    /** 模式 A 内容流卡片：浮于 [canvas] 之上。 */
    @Composable
    fun standardCard(): Color = cardSurface()

    /** 模式 B 分组卡片 (BentoCard)：与 [standardCard] 同阶。 */
    @Composable
    fun groupedCard(): Color = cardSurface()

    /** 卡片内部内陷槽（搜索框底色、Chip、嵌套小块）。比卡片暗一档。 */
    @Composable
    fun nested(): Color = if (LocalIsDarkTheme.current) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    /** 对话框、底部抽屉、悬浮条、右键菜单底色。深色下比卡片亮一档。 */
    @Composable
    fun overlay(): Color = if (LocalIsDarkTheme.current) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    /** 浮层内部的次级块（日志容器、选项行）。比 [overlay] 退一阶。 */
    @Composable
    fun overlayInner(): Color = if (LocalIsDarkTheme.current) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    /** 卡片表面：浅色白、深色亮灰 —— 分组模型的恒定浮起层。 */
    @Composable
    private fun cardSurface(): Color = if (LocalIsDarkTheme.current) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
}

/** 页面底色别名：映射到 [CpSurfaceTokens.grouped]。 */
@Composable
fun cpPageSurface(): Color = CpSurfaceTokens.grouped()

/** 画布底色别名：[CpSurfaceTokens.canvas]。 */
@Composable
fun cpCanvasSurface(): Color = CpSurfaceTokens.canvas()

/** 分组底色别名：[CpSurfaceTokens.grouped]。 */
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
