package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import cp.player.app.ui.theme.cpElevatedSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * 一个右键菜单项。[danger] 为 true 时以错误色呈现（删除 / 移除类操作），
 * [enabled] 为 false 时置灰且不可点。`items` 列表里穿插 [Separator] 可以画分隔线。
 */
data class CpContextMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    /** 当前生效项（排序方式等选择型菜单）：以主题色高亮。 */
    val isSelected: Boolean = false,
)

/** 菜单里的水平分隔线占位项。 */
val CpContextMenuSeparator: CpContextMenuItem =
    CpContextMenuItem(label = "", onClick = {}, enabled = false)

private fun CpContextMenuItem.isSeparator(): Boolean = this === CpContextMenuSeparator

/**
 * 通用右键菜单区域（桌面端）。
 *
 * 包住 [content]，用户在内容上按下鼠标**右键**时，在光标位置弹出 M3 风格菜单。
 * 只响应鼠标（[PointerType.Mouse]）的副键 —— 触屏 / 触控板的长按语义不在这里，
 * 各列表行仍走各自 `onLongClick`（进入多选等）。
 *
 * ### 为什么不用 material3 的 DropdownMenu
 * DropdownMenu 锚定在父布局底部而不是光标处：菜单挂在整页内容上时，
 * 菜单会飞到页面底边再叠加 offset，光标在哪完全无关。这里直接用
 * `Popup(alignment = TopStart)`，offset 取**事件在本节点的局部坐标**，
 * 菜单精确出现在光标下。
 *
 * ### 为什么 pointerInput 里读 `rememberUpdatedState`
 * `pointerInput(Unit)` 的块只随 key 重启；菜单项列表每次重组都是新实例
 * （回调里捕获着最新的 model / index），直接闭包捕获会拿到旧列表。
 * 弹出时机只依赖"有没有菜单"，具体项在 Popup 组合时经 [currentItems] 取最新值。
 *
 * [items] 为 null（或空）时不弹菜单，行为与没有包裹一致 —— 调用方按
 * "当前上下文有没有可用的动作" 来决定传不传。
 *
 * ### 页面包裹用法（[passive] = true）
 *
 * 包住**整页**给「空白处右键 → 刷新」这类页面级菜单时必须开 passive：
 * 此时监听从 Initial 挪到 **Main** 被动等 —— 事件在 Main 是子→父的顺序，
 * 行内自己的 [CpContextMenu]（Initial 消费）与 clickable（Main 消费 down）都跑在
 * 前面。子级已消费的右键（歌曲行菜单、卡片上的点击手势）就不再弹页面菜单，
 * 只有没有任何子级接手的**空白 / 纯文本**区域才弹 —— 默认 Initial 模式会父子
 * 同时弹出两个菜单。
 */
@Composable
fun CpContextMenu(
    items: List<CpContextMenuItem>?,
    modifier: Modifier = Modifier,
    passive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentItems by rememberUpdatedState(items)
    var showAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                // 记录右键按下位置；**释放时**才真正弹出。
                // 为什么不等 Press 直接弹：桌面端 Popup 是独立窗口且（focusable）会抢走
                // 焦点，随后的 Release 事件落在「已失焦的宿主窗口」上，在部分场景会被
                // 当成一次外部点击把刚弹出的菜单立即关掉 —— 表现就是「右键没反应」。
                // Press 只记录坐标 + 在 Initial 阶段消费掉（避免行内 combinedClickable
                // 把右键当成又一次点击 / 长按），等 Release 再弹就干净了。
                var pressAt: androidx.compose.ui.geometry.Offset? = null
                // passive：Main 被动等（见 KDoc）；默认 Initial 抢先消费。
                val pass = if (passive) PointerEventPass.Main else PointerEventPass.Initial
                while (true) {
                    val event = awaitPointerEvent(pass)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type != PointerType.Mouse) continue
                    when {
                        event.type == PointerEventType.Press && event.buttons.isSecondaryPressed -> {
                            // passive：子级（行菜单 / 点击手势）已消费的右键不抢 —— 只在
                            // 空白区弹页面级菜单。
                            if (passive && change.isConsumed) {
                                pressAt = null
                            } else {
                                pressAt = change.position
                                event.changes.forEach { it.consume() }
                            }
                        }
                        // pressAt 非空 ⇒ 上一条 Press 是右键（主键按下不会走到这），此时副键
                        // 已松开（isSecondaryPressed 为 false），确认是右键的 Release。
                        event.type == PointerEventType.Release &&
                            !event.buttons.isSecondaryPressed &&
                            pressAt != null -> {
                            if (currentItems != null) showAt = pressAt
                            pressAt = null
                        }
                        // 右键按下后又拖出了别的手势（滚动取消等），别把过期坐标留给下次释放。
                        event.type == PointerEventType.Press -> pressAt = null
                    }
                }
            }
        },
    ) {
        content()
        val anchor = showAt
        if (anchor != null && !currentItems.isNullOrEmpty()) {
            val density = LocalDensity.current
            val gapPx = with(density) { 4.dp.roundToPx() }
            val marginPx = with(density) { 8.dp.roundToPx() }
            val positionProvider = remember(anchor, gapPx, marginPx) {
                CpContextMenuPositionProvider(anchor, gapPx, marginPx)
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { showAt = null },
                // clippingEnabled = true：确保右键菜单完全被约束在宿主软件窗口内，
                // 结合 CpContextMenuPositionProvider 的动态翻转与安全边距钳制，
                // 彻底杜绝菜单穿出到系统桌面或被边缘截断的问题。
                properties = PopupProperties(focusable = true, clippingEnabled = true),
            ) {
                CpContextMenuPanel(
                    items = currentItems.orEmpty(),
                    onDismiss = { showAt = null },
                )
            }
        }
    }
}

@Composable
private fun CpContextMenuPanel(
    items: List<CpContextMenuItem>,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cpElevatedSurface(),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            Modifier.padding(6.dp).widthIn(min = 176.dp, max = 264.dp)
                .heightIn(max = 420.dp),
        ) {
            items.forEachIndexed { index, item ->
                if (item.isSeparator()) {
                    if (index in 1 until items.size - 1) {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    return@forEachIndexed
                }
                val contentColor = when {
                    !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    item.danger -> MaterialTheme.colorScheme.error
                    item.isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                item.danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
                                item.isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .clickable(enabled = item.enabled) {
                            onDismiss()
                            item.onClick()
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.icon?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    CpText(
                        item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        fontWeight = if (item.isSelected) FontWeight.SemiBold else null,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 移动端底部动作菜单（自适应形态）。
 *
 * 手机/触屏形态下替代桌面端光标悬浮菜单，以 M3 标准底部抽屉展示动作列表。
 */
@Composable
private fun CpContextMenuBottomSheet(
    items: List<CpContextMenuItem>,
    onDismiss: () -> Unit,
) {
    LegacyModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            items.forEachIndexed { index, item ->
                if (item.isSeparator()) {
                    if (index in 1 until items.size - 1) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    return@forEachIndexed
                }
                val contentColor = when {
                    !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    item.danger -> MaterialTheme.colorScheme.error
                    item.isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                item.danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                item.isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .clickable(enabled = item.enabled) {
                            onDismiss()
                            item.onClick()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.icon?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    CpText(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        fontWeight = if (item.isSelected) FontWeight.SemiBold else null,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 锚定按钮的弹出菜单（具备跨端形态感知能力）。
 *
 * - **桌面端**（[LocalWindowChromeActive] 为真）：菜单右上角钉在触发区域右下角，
 *   以 [Popup] 呈现光标级面板 [CpContextMenuPanel]。
 * - **移动端**（[LocalWindowChromeActive] 为假）：展开为底部弹层列表 [CpContextMenuBottomSheet]，
 *   符合触屏单手操作习惯。
 */
@Composable
fun CpAnchoredMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<CpContextMenuItem>?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDesktop = LocalWindowChromeActive.current
    Box(modifier) {
        content()
        if (expanded && !items.isNullOrEmpty()) {
            if (isDesktop) {
                val density = LocalDensity.current
                val gapPx = with(density) { 4.dp.roundToPx() }
                val marginPx = with(density) { 8.dp.roundToPx() }
                val positionProvider = remember(gapPx, marginPx) {
                    CpAnchoredMenuPositionProvider(gapPx, marginPx)
                }
                Popup(
                    popupPositionProvider = positionProvider,
                    onDismissRequest = onDismiss,
                    properties = PopupProperties(focusable = true, clippingEnabled = true),
                ) {
                    CpContextMenuPanel(items = items.orEmpty(), onDismiss = onDismiss)
                }
            } else {
                CpContextMenuBottomSheet(items = items.orEmpty(), onDismiss = onDismiss)
            }
        }
    }
}

/**
 * 桌面端右键菜单动态位置提供器。
 *
 * 保证右键菜单始终完整显示在宿主软件窗口（[windowSize]）内：
 * - **水平方向**：
 *   - LTR 下默认展开在光标右侧（`cursorX + gapPx`）；若右侧超出安全边界（`windowWidth - marginPx`），
 *     自动翻转至光标左侧（`cursorX - gapPx - menuWidth`）。
 *   - RTL 下反之，默认展开在光标左侧，不足时翻转至右侧。
 *   - 若两端空间均不足（如窗口极窄或光标处于极端位置），自动钳制在窗口内 `[minMarginX, maxMarginX]`。
 * - **垂直方向**：
 *   - 默认展开在光标下方（`cursorY + gapPx`）；若下方超出安全边界（`windowHeight - marginPx`），
 *     自动翻转至光标上方（`cursorY - gapPx - menuHeight`）。
 *   - 若上下空间均不足（如窗口较矮或菜单项较多），自动钳制在窗口内 `[minMarginY, maxMarginY]`。
 *
 * 结合 [PopupProperties.clippingEnabled] = true，彻底防止菜单穿出到系统桌面或被边缘截断。
 */
internal class CpContextMenuPositionProvider(
    private val cursorOffset: androidx.compose.ui.geometry.Offset,
    private val gapPx: Int = 4,
    private val marginPx: Int = 8,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cursorX = anchorBounds.left + cursorOffset.x.roundToInt()
        val cursorY = anchorBounds.top + cursorOffset.y.roundToInt()

        val safeMaxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val safeMaxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)

        val minMarginX = marginPx.coerceAtMost(safeMaxX)
        val maxMarginX = (windowSize.width - popupContentSize.width - marginPx).coerceIn(minMarginX, safeMaxX)

        val minMarginY = marginPx.coerceAtMost(safeMaxY)
        val maxMarginY = (windowSize.height - popupContentSize.height - marginPx).coerceIn(minMarginY, safeMaxY)

        val isLtr = layoutDirection == LayoutDirection.Ltr

        val rightX = cursorX + gapPx
        val leftX = cursorX - gapPx - popupContentSize.width

        val x = if (isLtr) {
            when {
                rightX + popupContentSize.width <= windowSize.width - marginPx && rightX >= minMarginX -> rightX
                leftX >= minMarginX && leftX + popupContentSize.width <= windowSize.width - minMarginX -> leftX
                rightX + popupContentSize.width <= windowSize.width && rightX >= 0 -> rightX.coerceIn(minMarginX, maxMarginX)
                leftX >= 0 && leftX + popupContentSize.width <= windowSize.width -> leftX.coerceIn(minMarginX, maxMarginX)
                else -> (if (rightX > maxMarginX) leftX else rightX).coerceIn(minMarginX, maxMarginX)
            }
        } else {
            when {
                leftX >= minMarginX && leftX + popupContentSize.width <= windowSize.width - marginPx -> leftX
                rightX + popupContentSize.width <= windowSize.width - marginPx && rightX >= minMarginX -> rightX
                leftX >= 0 && leftX + popupContentSize.width <= windowSize.width -> leftX.coerceIn(minMarginX, maxMarginX)
                rightX + popupContentSize.width <= windowSize.width && rightX >= 0 -> rightX.coerceIn(minMarginX, maxMarginX)
                else -> (if (leftX < minMarginX) rightX else leftX).coerceIn(minMarginX, maxMarginX)
            }
        }

        val belowY = cursorY + gapPx
        val aboveY = cursorY - gapPx - popupContentSize.height

        val y = when {
            belowY + popupContentSize.height <= windowSize.height - marginPx && belowY >= minMarginY -> belowY
            aboveY >= minMarginY && aboveY + popupContentSize.height <= windowSize.height - minMarginY -> aboveY
            belowY + popupContentSize.height <= windowSize.height && belowY >= 0 -> belowY.coerceIn(minMarginY, maxMarginY)
            aboveY >= 0 && aboveY + popupContentSize.height <= windowSize.height -> aboveY.coerceIn(minMarginY, maxMarginY)
            else -> (if (belowY > maxMarginY) aboveY else belowY).coerceIn(minMarginY, maxMarginY)
        }

        return IntOffset(x, y)
    }
}

/**
 * 桌面端锚定菜单动态位置提供器。
 *
 * 保证锚定菜单（下拉菜单、三点更多菜单等）始终在宿主软件窗口（[windowSize]）内显示：
 * - 水平方向默认右对齐触发锚点；若超出窗口边界则自动向内钳制或翻转。
 * - 垂直方向默认在锚点下方展开；若下方空间不足则自动翻转至锚点上方展开。
 */
internal class CpAnchoredMenuPositionProvider(
    private val gapPx: Int = 4,
    private val marginPx: Int = 8,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val safeMaxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val safeMaxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)

        val minMarginX = marginPx.coerceAtMost(safeMaxX)
        val maxMarginX = (windowSize.width - popupContentSize.width - marginPx).coerceIn(minMarginX, safeMaxX)

        val minMarginY = marginPx.coerceAtMost(safeMaxY)
        val maxMarginY = (windowSize.height - popupContentSize.height - marginPx).coerceIn(minMarginY, safeMaxY)

        val isLtr = layoutDirection == LayoutDirection.Ltr

        val alignedX = if (isLtr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = alignedX.coerceIn(minMarginX, maxMarginX)

        val belowY = anchorBounds.bottom + gapPx
        val aboveY = anchorBounds.top - gapPx - popupContentSize.height

        val y = when {
            belowY + popupContentSize.height <= windowSize.height - marginPx && belowY >= minMarginY -> belowY
            aboveY >= minMarginY && aboveY + popupContentSize.height <= windowSize.height - minMarginY -> aboveY
            belowY + popupContentSize.height <= windowSize.height && belowY >= 0 -> belowY.coerceIn(minMarginY, maxMarginY)
            aboveY >= 0 && aboveY + popupContentSize.height <= windowSize.height -> aboveY.coerceIn(minMarginY, maxMarginY)
            else -> (if (belowY > maxMarginY) aboveY else belowY).coerceIn(minMarginY, maxMarginY)
        }

        return IntOffset(x, y)
    }
}
