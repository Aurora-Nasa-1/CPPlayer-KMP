package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cp.player.app.ui.model.loadSearchSuggestions
import cp.player.app.ui.theme.CpShapes
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 搜索框高度。标题栏 44dp，上下各留 6dp。 */
private val SearchFieldHeight = 32.dp

/** 搜索框（含下拉）的最大宽度。再宽就失去「输入框」的观感了。 */
private val SearchFieldMaxWidth = 420.dp

/**
 * 桌面标题栏里的全局搜索入口：输入框 + 建议下拉。
 *
 * ## 它自己负责「把搜索框夹在正中，且两侧留白可拖拽」
 *
 * 布局是 `[拖拽空白] [搜索框] [拖拽空白]` 三段**首尾相接、互不重叠**，由 [dragArea] 这个槽位
 * 提供两端的拖拽能力。
 *
 * ⚠️ **不能让调用方给一个更宽的槽位、让本组件在里面居中**：拖拽靠的是 AWT 层的
 * `MouseListener`（见 [DesktopTitleBar] 的说明），它**不认 Compose 的命中测试** —— 只要
 * 「按下的那一点」落在拖拽区内，之后整段拖动都会移动窗口。槽位比搜索框宽出来的那部分
 * 如果不属于任何拖拽区，就成了**拖不动的死区**（第一版正是如此：槽位 601px、框 420px，
 * 两侧各 90px 拖不动）。
 *
 * ## 下拉为什么不用 `Popup`
 *
 * `Popup` 在桌面是**独立的 AWT 窗口**：位置要自己跟、阴影与父窗口不同步、拖动窗口时容易错位。
 * 这里只需要在标题栏正下方铺一块内容，用**内联 overlay** 就够：Compose 的布局默认不裁剪，
 * 下拉超出标题栏 44dp 的部分会自然画在正文之上 —— 前提是标题栏整体带 `zIndex`，
 * 见 [DesktopTitleBar]。
 *
 * ## 下拉为什么没有「点别处关闭」的幕布
 *
 * 那需要一块盖住整个窗口的透明层，而标题栏只有 44dp 高，只能靠
 * `requiredHeight(一个大常数)` 硬撑出去 —— 是个经不起窗口尺寸变化的魔法值。
 * 改为依赖 Compose 的焦点语义：**建议行刻意不用 `clickable`**（它会把焦点从输入框抢走），
 * 只用 `pointerInput`，因此点建议行不会导致失焦；而点击页面上任何可聚焦控件时输入框会失焦
 * ⇒ 下拉自动收起。
 */
@Composable
internal fun TitleBarSearch(
    onSearch: (String) -> Unit,
    dragArea: @Composable (Modifier) -> Unit,
    // JBR 模式专用：挂在搜索框锚点容器上，把它登记成「客户区」（原生 hit-test 不抢）。
    // 无边框模式传空即可 —— 拖拽由 [dragArea] 的 AWT MouseListener 承担，互不相干。
    fieldModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val suggestions = rememberSearchSuggestions(query)
    val expanded = focused && query.isNotBlank() && suggestions.isNotEmpty()

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        dragArea(Modifier.weight(1f).fillMaxHeight())

        // 锚点容器：宽 = min(可用宽度, 420dp)，下拉的宽度与横向位置都对齐到这里。
        // ⚠️ 高度刻意**不写死**（写死 44dp 会把子节点的测量高度一起钉住，见 SuggestionDropdown）。
        Box(Modifier.widthIn(max = SearchFieldMaxWidth).fillMaxWidth().fillMaxHeight().then(fieldModifier)) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { if (query.isNotBlank()) onSearch(query.trim()) },
                onFocusChange = { focused = it },
                modifier = Modifier.fillMaxWidth().height(SearchFieldHeight).align(Alignment.Center),
            )
            if (expanded) {
                SuggestionDropdown(
                    suggestions = suggestions,
                    onPick = { picked ->
                        query = picked
                        onSearch(picked)
                    },
                    // 标题栏高 44dp（43 内容 + 1 分隔线），下拉正好从分隔线下面开始。
                    modifier = Modifier.align(Alignment.TopStart).offset(y = TitleBarHeight).fillMaxWidth(),
                )
            }
        }

        dragArea(Modifier.weight(1f).fillMaxHeight())
    }
}

/**
 * 建议的取数与防抖。
 *
 * 用 `LaunchedEffect(query)` 而不是手写 job：query 一变，上一个协程被取消，
 * 「最后一次请求胜出」由结构化并发保证。`loadSearchSuggestions` 内部把
 * `CancellationException` 原样抛出，取消才能真正生效；这里再 `ensureActive()` 兜一道，
 * 防止「结果已拿到、随后才被取消」的那一瞬把旧结果写回去。
 */
@Composable
private fun rememberSearchSuggestions(query: String): List<String> {
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        val loaded = loadSearchSuggestions(trimmed).orEmpty()
        coroutineContext.ensureActive()
        suggestions = loaded
    }
    return suggestions
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CpShapes.full)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "搜索歌曲、歌手或专辑",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CpShapes.full)
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * 建议下拉。
 *
 * ⚠️ **`wrapContentHeight(unbounded = true)` 是这个组件必须自己带的契约，不能交给调用方**：
 * 它被挂在标题栏里，而标题栏（以及锚点容器）的高度固定 44dp —— 父节点传给子节点的**测量约束**
 * 也被钉在 44dp，多行列表会被量成「一行高」，症状就是「下拉只显示一行」。
 * `unbounded` 让 Surface 拿到无上限的高度约束、按内容自然撑开。
 *
 * ⚠️ `align = Alignment.Top` 不能省：容器向上回报的高度仍然被夹回 44dp，用默认的**居中**对齐
 * 会把这块比容器高的内容往上顶 `(内容高 - 44) / 2`，直接盖住标题栏本身。
 */
@Composable
private fun SuggestionDropdown(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
        shape = RoundedCornerShape(12.dp),
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
            // ⚠️ 刻意用 `pointerInput` 而不是 `clickable`：后者会把焦点从输入框抢走，
            //    于是 `focused` 变 false ⇒ 下拉在 onClick 触发之前就被收起，点不中。
            //    这里也不等抬起就选中 —— 建议列表按「按下即选」是常见且更跟手的行为。
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
