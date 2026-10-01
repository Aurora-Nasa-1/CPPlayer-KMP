package cp.player.app.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 当前窗口是否处于 Expanded 宽度（≥840dp），由 [ProvideIsExpanded] 在根 Navigator 之上提供。 */
val LocalIsExpanded = staticCompositionLocalOf { false }

/**
 * 当前组合是否正被渲染成**双栏布局的详情栏**（右栏）。
 *
 * 由 `SettingsScreen` 的宽屏双栏在详情槽位里 provide：右栏渲染的是**另一个路由页**
 * （`SettingsEntry.screen()`），那一页自己不知道"我此刻是别人的右栏" —— 它既可能被
 * 直接 push（`LibraryDashboard` 的「外观 / 存储 / 音源 / 关于」入口），也可能被塞进右栏。
 *
 * 两种情况下的外壳要求正好相反：
 * - 直接 push ⇒ 需要标题与返回键；
 * - 作为右栏 ⇒ 标题与返回都在**左栏的选中态**里，再画一条顶栏就成了"栏中栏"。
 *
 * 收敛前靠各页自己读 `LocalIsExpanded` 来区分，而那个判据是**错的**：直接 push 到宽屏
 * 时它同样为真，于是「宽屏下从媒体库点『关于』进去，整页没有返回键」。
 * 由**容器**声明自己是双栏，才是唯一可靠的判据。
 */
val LocalEmbeddedInPane = staticCompositionLocalOf { false }

/**
 * 按「本层可用宽度」发布 [LocalIsExpanded]。
 *
 * 必须挂在**根 Navigator 之上**（见 `App.kt`）：`MainScreen` 内部也 provide 了这个 local，
 * 但 push 出去的路由页与 `MainScreen` 在 Navigator 里是**兄弟节点**，拿不到它 ——
 * 于是设置 / 账号 / 引导 / 首页生成歌单这些路由页在宽窗口上读到的都是默认 `false`，
 * 也就是一律走手机布局。两处 provide 的值同源（都是窗口宽度），内层覆盖无害。
 *
 * ⚠️ 这**不能**替代"路由页自己判宽屏"：宽度可能因为内嵌面板、`BoxWithConstraints`
 * 局部约束而与窗口不同，需要按自身可用宽度决策的页面仍应自己读 `maxWidth`。
 */
@Composable
fun ProvideIsExpanded(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = CpBreakpoints.isExpanded(maxWidth)
        CompositionLocalProvider(LocalIsExpanded provides expanded) {
            content()
        }
    }
}

object CpSpacing {
    val pageHorizontal = 20.dp
    val pageTop = 20.dp
    val section = 28.dp
    val item = 12.dp
    val touchTarget = 48.dp

    // —— 组件级间距 ——
    //
    // 上面那组是「页面级」（页边距、区块间距）；这一组是「组件内」。
    // 补它们的原因：卡片内边距 / 列表行高 / 堆叠间隔原先散落在各文件里各写各的
    // （9dp / 14dp / 18dp 这类非标度值都出现过），同一类组件在不同页面观感不一致。
    //
    // ⚠️ **全部取 4dp 的倍数**。Kazumi 全站只靠 `cardSpace = 8` / `safeSpace = 12`
    // 两个基准就撑起了节奏感（见 `reference/Kazumi/lib/utils/constants.dart`）——
    // 节奏来自「少数几个值被反复使用」，不是来自值的数量多。

    /** 卡片内边距（紧凑型，用于列表内的窄卡片）。 */
    val cardPaddingCompact = 16.dp

    /** 卡片内边距（默认，主卡片）。 */
    val cardPadding = 20.dp

    /** 卡片内边距（宽松型，用于大面积英雄卡片）。 */
    val cardPaddingComfortable = 24.dp

    /** 列表行最小高度（M3 规范值）。 */
    val listRowMinHeight = 56.dp

    /**
     * 分组列表的**行间距**。
     *
     * 4dp 是 M3 分组列表的规范缝宽 —— 行与行之间留一道细缝，同时每行的圆角
     * 从「组内圆角」变形成「容器圆角」时才有空间可看（见 `CpGroupedListRow`）。
     * 缝太宽会散成一张张独立卡片，太窄则看不出分组。
     */
    val listRowGap = 4.dp

    /** 纵向堆叠间隔：紧密（图标与文字之间等）。 */
    val stackGapTight = 4.dp

    /** 纵向堆叠间隔：默认（卡片内元素之间）。 */
    val stackGap = 8.dp

    /** 纵向堆叠间隔：宽松（卡片之间）。 */
    val stackGapLoose = 12.dp

    /** 纵向堆叠间隔：区块级（一个 section 内部的大块之间）。 */
    val stackGapSection = 16.dp

    /**
     * 桌面端页面的**统一最大内容宽度**。
     *
     * 所有桌面页面（首页 / 曲库 / 设置 …）都必须用它收口：不同 Tab 用不同的上限
     * 会让切换时正文宽度整体跳一下，是「大屏显得乱」最直接的来源。
     *
     * 1400dp 是刻意选的：超过这个宽度后，一行能塞下的卡片多到失去视觉分组，
     * 长文本的阅读测度也会变差；再宽的显示器就把留白放在两侧。
     */
    val pageMaxWidth = 1400.dp

    /**
     * **表单类页面**（设置 / 账号 / 集成配置）的统一最大内容宽度。
     *
     * 为什么不复用 [pageMaxWidth]：那个值是给「卡片栅格」用的，卡片并排时宽一点没关系；
     * 设置页是**标签—控件**两列结构，行宽拉到 1400dp 之后视线要在左右两端来回跳，
     * 是最典型的「大屏反而更难用」。720dp 是表单类内容的舒适上限。
     *
     * ⚠️ 用法必须写成 `Modifier.widthIn(max = formMaxWidth).fillMaxWidth()` ——
     * 顺序反了 `widthIn` 是空操作（约束已被 `fillMaxWidth` 钉死）。
     */
    val formMaxWidth = 720.dp

    /**
     * 桌面端栅格列数：按**可用内容宽度**换算，保证每列落在 180–210dp 的舒适区。
     *
     * 不要用窗口宽度去算 —— 窗口宽度里含两侧留白与滚动条槽，宽屏下会多算 1–2 列。
     */
    fun gridColumns(contentWidth: Dp, target: Dp = 196.dp, min: Int = 3, max: Int = 7): Int =
        (contentWidth.value / target.value).toInt().coerceIn(min, max)
}

/**
 * 图标尺寸阶梯。
 *
 * 收敛前全仓混用 18 / 20 / 24dp，还有零星的 22 / 26 —— 同一行的两个图标偶尔差 2dp，
 * 肉眼说不出哪里不对，但会觉得"没对齐"。三档够覆盖全部场景：
 *
 * | 档位 | 值 | 场景 |
 * |------|-----|------|
 * | [inline] | 18dp | 跟在文字旁边的行内图标（如标签、时间码前的小图标） |
 * | [list] | 20dp | 列表项前导图标、区块标题前置图标 |
 * | [action] | 24dp | 操作按钮、导航栏、工具条里的独立图标 |
 */
object CpIconSize {
    val inline = 18.dp
    val list = 20.dp
    val action = 24.dp
}

/**
 * 布局断点。
 *
 * 原先这个 `840.dp` 以内联魔法数的形式散落在 `MainScreen` 与设置页里，各写各的 ——
 * 想调一次「什么时候从手机布局切到桌面布局」得全仓库找。收敛到这里，只此一份。
 */
object CpBreakpoints {
    /** 小于此宽度：单栏（手机 / 窄窗口）。 */
    val medium = 840.dp

    /** 大于等于此宽度：双栏（左列表 + 右详情），内容居中留白。 */
    val expanded = 1200.dp

    /** 是否进入「桌面式」双栏布局。取代原先内联的 `maxWidth >= 840.dp`。 */
    fun isExpanded(width: Dp): Boolean = width >= medium

    enum class Level { COMPACT, MEDIUM, EXPANDED }

    fun levelOf(width: Dp): Level = when {
        width >= expanded -> Level.EXPANDED
        width >= medium -> Level.MEDIUM
        else -> Level.COMPACT
    }
}

@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke(this)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!supportingText.isNullOrBlank()) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke(this)
    }
}

@Composable
fun ContentState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    loading: Boolean = false,
    error: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            // 统一走 Expressive 的变形加载器：这里是全应用**最常出现**的加载态
            // （列表空态/加载中都由 ContentState 承担），留着转圈的话，
            // 一眼就能看出「这块是老 UI，那块是新 UI」。
            CpLoadingIndicator(Modifier.size(36.dp))
        } else {
            Icon(
                imageVector = if (error) Icons.Filled.ErrorOutline else Icons.Filled.Inbox,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Adds the subtle pressed compression used by high-emphasis Expressive surfaces. */
@Composable
fun rememberPressedScale(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Pair<MutableInteractionSource, Modifier> {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressedScale",
    )
    return interactionSource to Modifier.scale(scale)
}

@Composable
fun StateSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
