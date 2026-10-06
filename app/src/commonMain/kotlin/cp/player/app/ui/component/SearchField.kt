package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.CpShapes

/**
 * 全应用**唯一**的搜索输入框外壳。
 *
 * 收敛前「搜索」这一件事在本仓里有**两套**长得完全不像的东西：
 *
 * | 位置 | 旧形态 | 症状 |
 * |------|--------|------|
 * | 搜索页 | `OutlinedTextField` + 前导 `Search` 图标 + 尾部再一个 `Search` 图标 | 1dp 描边的朴素输入框；空态下**前后两个放大镜**；宽屏不收口（1400dp 以上一条通到底） |
 * | 桌面标题栏 | 手写的 `BasicTextField` 填充胶囊 | 观感是对的，但那份代码**只在标题栏里**，页面抄不到 |
 *
 * 现在两处共用这一份：**填充式胶囊**（M3 Expressive 的 search bar 语言）+ 统一的清除按钮。
 * 建议下拉见 [CpSearchSuggestionPanel] —— 它与输入框刻意分成两个组件，好让页面把它当
 * **浮层**用（浮的关键是那层高度固定的 Box，见 `SearchScreen` 的说明）。
 *
 * @param onSubmit 提交搜索（IME 的「搜索」键）。**关键词是否为空由调用方判**，这里不代劳。
 * @param focusRequester 传非 null 时挂 `focusRequester` —— 由调用方决定要不要自动聚焦。
 *   ⚠️ 触屏上不要自动聚焦（进页面就弹软键盘会把历史 / 热门整块内容顶掉一半）。
 * @param containerColor / focusedContainerColor 未聚焦 / 聚焦时的容器色。**直接给颜色而不是**
 *   靠 `tonalElevation` —— 标题栏与页面正文所在的背景不是同一档色阶，再叠一层 tonal
 *   会让同一个输入框在两处亮出两个不同的层次。
 */
@Composable
fun CpSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    placeholder: String = "搜索歌曲、歌手或专辑",
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusRequester: FocusRequester? = null,
    height: Dp = CpSearchFieldHeight,
) {
    val s = cpStrings()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) focusedContainerColor else containerColor,
        // 颜色过渡用 effects 档：**不带回弹**（spatial 弹簧会让底色「哆嗦」一下）。
        animationSpec = CpMotion.effects(),
        label = "cpSearchFieldColor",
    )
    // 紧凑档（≤40dp，桌面标题栏那一版）与常规档（页面里的 52dp）用的是同一套字形，
    // 只是图标与清除钮各小一档 —— 32dp 高的框里塞 20dp 的图标会把文字挤到一边。
    val compact = height <= CpSearchFieldCompactHeight

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .height(height)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CpShapes.full)
                    .background(background)
                    .padding(horizontal = if (compact) 12.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (compact) 16.dp else 20.dp),
                )
                Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    val clearSize = if (compact) 20.dp else 32.dp
                    Box(
                        modifier = Modifier
                            .size(clearSize)
                            .clip(CpShapes.full)
                            .clickable(onClickLabel = s.songCache.clearSearch) { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = s.songCache.clearSearch,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(clearSize * 0.7f),
                        )
                    }
                }
            }
        },
    )
}

/**
 * 页面内搜索框的高度。
 *
 * 52dp 而不是 M3 文本框的 56dp：这里只有一行单行文本、两侧图标也各小一档，
 * 撑到 56dp 会显得空。
 */
val CpSearchFieldHeight: Dp = 52.dp

/**
 * 紧凑档的高度分界：≤ 这个高度就换小一档的图标尺寸。
 *
 * 唯一踩到这一档的是桌面标题栏那条搜索框（那里只有 32dp 可用）。
 */
private val CpSearchFieldCompactHeight: Dp = 40.dp

/**
 * 搜索建议下拉 —— 浮层，调用方负责把它摆到输入框下方。
 *
 * ⚠️ **`wrapContentHeight(unbounded = true)` 是这个组件必须自带的一笔**：两个消费方
 * （标题栏、搜索页）都把它放在一个**高度被钉死**的容器里（44dp / 搜索框高），父节点传给
 * 子节点的测量约束也随之被夹住 —— 多行列表会被量成「一行高」，症状就是「下拉只显示一行」。
 *
 * ⚠️ 建议行刻意**不用 `clickable`**：`clickable` 会把焦点从输入框抢走，于是
 * `focused` 变 false ⇒ 下拉在 onClick 之前就被收起，点不中。这里也不等抬起（`onPress` 即选中），
 * 建议列表按「按下即选」是更跟手的行为。
 */
@Composable
fun CpSearchSuggestionPanel(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            suggestions.forEach { suggestion ->
                SuggestionRow(suggestion = suggestion, onPick = onPick)
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: String, onPick: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            )
            .hoverable(interaction)
            .pointerInput(suggestion) {
                detectTapGestures(
                    onPress = {
                        onPick(suggestion)
                        tryAwaitRelease()
                    },
                )
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            suggestion,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
