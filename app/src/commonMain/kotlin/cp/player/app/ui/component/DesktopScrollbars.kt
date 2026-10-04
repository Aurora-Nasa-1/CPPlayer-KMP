package cp.player.app.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 桌面端滚动条。
 *
 * 安卓有自己的边缘滚动反馈，桌面端用户则习惯「右侧有一条可以拖的滚动条」。
 * [androidx.compose.foundation.VerticalScrollbar] 只在 JVM 目标存在，所以这里用
 * expect/actual 包一层：桌面端画真正的可拖拽滚动条，安卓端保持原样。
 *
 * **不要**在页面里直接写 `Column(Modifier.verticalScroll(state))` / `LazyColumn(...)`，
 * 改用下面的 [ScrollColumn] / [LazyScrollColumn] / [LazyScrollRow]。它们除了滚动之外，
 * 还会在末端留出一条滚动条槽位并把滚动条叠上去——两端行为一致，只有桌面端真的画出滚动条。
 *
 * 桌面端只在「内容确实超出、而且超出得不止最后几个像素」时才画滚动条：可见比例达到
 * 95% 以上时滑块几乎占满整条轨道，看着像一条贴边的竖线，不如不画。阈值见 desktopMain
 * 的 `AlmostVisibleFraction`。安卓端不受影响（边缘滚动反馈照旧）。
 */

/**
 * 滚动条槽位宽度。桌面端要留白避免滚动条压住内容，安卓端为 0。
 *
 * 这个槽位**始终**留着，不跟着滚动条的显隐变化：槽位只影响宽度，一旦跟着变，
 * 窄列里的文本会重新折行 → 内容高度变 → 又反过来影响显隐判定，可能来回抖；
 * 而且内容会因为滚动条的出现/消失横向平移，比一直留一条同色留白更刺眼。
 */
expect val desktopScrollbarGutter: Dp

@Composable
expect fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)

@Composable
expect fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)

@Composable
expect fun DesktopHorizontalScrollbar(state: LazyListState, modifier: Modifier = Modifier)

/**
 * 壳层下发的**小播放器尾留白**（默认 0）。
 *
 * 底栏小播放器是**浮层**，浮在内容之上 —— 所以内容必须能在它下面继续铺一段，
 * 用户滚到底时最后一项才不会被压住。这段留白只能加在**滚动内容的末尾**
 * （`LazyColumn.contentPadding` / `verticalScroll` 内容后面的 padding）。
 *
 * ⚠️ **不能**改成给内容区挂 `Modifier.padding(bottom = 预留)`：那会把内容区**裁短**，
 * 于是「屏幕底部到内容区底边」之间永远空着一条 —— 小播放器正好坐在那条空白里，
 * 看起来就像它自带了背景（也真的会挡住页面背景/壁纸）。这正是本 local 存在的原因。
 *
 * 由 `MainScreen` / `App` 在渲染小播放器的那一层 provide；三者（当前位置、预留量、
 * 小播放器自身）必须来自**同一个**状态源，否则内容会让位而小播放器不动（或反过来）。
 */
val LocalMiniPlayerTailSpace = compositionLocalOf { 0.dp }

/**
 * 等价于 `Column(modifier.verticalScroll(state))`，但右侧叠加桌面滚动条。
 *
 * 注意 `modifier` 作用在外层容器（滚动视口）上，`contentPadding` 作用在滚动内容上
 * —— 也就是说 `contentPadding` 会随内容一起滚走，和 `verticalScroll` 之后接 `padding` 一致。
 */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    // 与 [LazyScrollColumn] 对齐：壳层的「小播放器尾留白」要通过这两个 local 下发，
    // 而窄屏的首页 / 搜索 / 曲库走的就是这一条（`verticalScroll`）。
    // 缺了它们，窄屏的列表末尾就差一条留白 —— 最后一项被小播放器压住。
    userScrollEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 尾留白由**壳层**下发：`contentPadding.bottom` 对静态内容意味着「往下多铺一条」，
    // 而不是「把视口裁短」—— 后者会让浮在上面的小播放器坐进一条空白带里，
    // 看起来像它自带了背景。详见 MiniPlayerTailSpace。
    val shellTail = LocalMiniPlayerTailSpace.current
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = desktopScrollbarGutter)
                .verticalScroll(state, enabled = userScrollEnabled)
                // ⚠️ 钳非负：壳层下发的尾留白是 spring 动画值（回落 0 时下冲穿负），
                // 负 Dp 进 PaddingValues 直接抛「Padding must be non-negative」。
                .padding(contentPadding + PaddingValues(bottom = shellTail.coerceAtLeast(0.dp))),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        DesktopVerticalScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 等价于 `LazyColumn(...)`，但右侧叠加桌面滚动条。
 * 参数含义与 [LazyColumn] 完全一致，直接换名字即可。
 */
@Composable
fun LazyScrollColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        // 这里刻意只 `fillMaxWidth` 而不是 `fillMaxSize`：几个底部弹层用的是
        // `heightIn(max = …)`（高度该由内容决定），`fillMaxSize` 会把它们撑到上限高度。
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(end = desktopScrollbarGutter),
            state = state,
            // 同上：尾留白走 contentPadding（内容一直铺到底），不是裁短视口。
            // ⚠️ 钳非负：留白是 spring 动画值，回落 0 时下冲穿负会直接崩（同 ScrollColumn）。
            contentPadding = contentPadding + PaddingValues(bottom = LocalMiniPlayerTailSpace.current.coerceAtLeast(0.dp)),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        DesktopVerticalScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 等价于 `LazyRow(...)`，但底部叠加桌面滚动条，并且**支持鼠标垂直滚轮横向滚动**。
 *
 * LazyRow 的交叉轴（高度）是包裹内容的，所以这里刻意不用 `fillMaxSize`；
 * 槽位通过 `contentPadding` 的 bottom 留出，滚动条正好落在这一条留白上。
 */
@Composable
fun LazyScrollRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().desktopHorizontalWheelScroll(state),
            state = state,
            contentPadding = contentPadding + PaddingValues(bottom = desktopScrollbarGutter),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        DesktopHorizontalScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}
