package cp.player.app.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import kotlinx.coroutines.delay

/**
 * 歌曲列表页的统一度量。
 *
 * **这是本批唯一允许出现「列表页内边距 / 顶部占位」的地方**：页面一处都不许再写
 * `PaddingValues` / `Arrangement.spacedBy` / `topContentPadding`。
 *
 * 量值全部指向 [CpSpacing] 的既有令牌，不另起一套；只有壳自己的**结构尺寸**
 * （顶栏高、行高补齐）留在这里，它们是「同一个壳在四个页面长得一样」的依据。
 */
object TrackListMetrics {

    /**
     * 列表页顶栏高度（**固定**）。
     *
     * ⚠️ 刻意不是 `LargeTopAppBar`：后者展开态约 152dp，而歌单页的标题槽里只有
     * 一行 40~56dp 的封面 + 文字 ⇒ 文字内容之上会空出近 90dp 的空白，正是 G4
     * 「歌单界面顶部莫名一块空白」的成因。固定高度之后，顶栏高度与内容高度无关，
     * 不再有任何「随滚动展开又收起」的占位变化（G1 的标题重排也一并消失）。
     */
    val topBarHeight = 64.dp

    /**
     * 双栏右栏自绘返回条的高度（只有「宽屏独立页」这一种情况会渲染它）。
     *
     * 宽屏独立页没有 `AppScaffold`（它按窗口 chrome / 双栏判据不画顶栏），
     * 返回键只能由壳补一行 —— 这一行高度必须定死，否则收容它的 `padding` 又要各写各的。
     */
    val chromeRowHeight = 56.dp

    /**
     * 双栏左栏（信息栏）的水平内边距。
     *
     * 原先歌单页写 `horizontal = 24.dp`，比正文列表的 20dp 宽出一圈 —— 左右两栏的
     * 文字基线对不齐，正是「同一个页面两套内边距」的观感来源。
     */
    val railHorizontal = CpSpacing.pageHorizontal

    /** 双栏左栏的上下内边距。原先歌单页写 20dp + 额外 `Spacer(16.dp)` = 36dp 空白。 */
    val railVertical = 16.dp

    /** 信息栏里「封面块」与「主播放行」之间的距离。 */
    val headToActions = 12.dp

    /** 列表内容顶端与顶栏之间的距离（列表第一项自己的上边距）。 */
    val contentTopGap = 14.dp

    /** 列表水平内边距（栅格页标准）。 */
    val horizontal: Dp = CpSpacing.pageHorizontal

    /** 行间距（分组列表标准缝宽）。 */
    val rowGap: Dp = CpSpacing.listRowGap

    /** 列表尾部留白（不含小播放器尾留白，后者由 `LazyScrollColumn` 自己叠加）。 */
    val bottomInset: Dp = CpSpacing.formBottomInset

    /** 距列表末尾还剩几项时触发翻页。四页同一阈值，避免「有的页滚到底才加载」。 */
    const val loadMoreThreshold = 5

    /**
     * 内容区次级动作 chip 的高度。
     *
     * **36dp 是改造前 `ActionChip` 的原值**，用户要求「三个按钮的样子还原」⇒ 不动。
     */
    val secondaryRowHeight = 36.dp

    /** 主播放胶囊行的高度（壳生成的「全部播放 / 随机播放」那一行）。 */
    val primaryRowHeight = 54.dp
}

/** 列表的数据状态。页面把 model 的字段映射进来（壳不认识 model / 数据层）。 */
data class TrackListStatus(
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

/** 空态描述。 */
data class TrackEmptySpec(
    val title: String,
    val message: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * 内容区**唯一**允许出现的主播放动作。
 *
 * ⚠️ 用枚举而不是 `@Composable` 槽，是**故意把「把删除 / 分享 / 排序 / 多选塞进内容区」
 * 这件事从类型上禁掉**：这里写不进 `Delete`，因为根本没有这个成员。
 * 其余页面级动作一律经顶栏的「更多」（[TrackListShell.onOverflow]）。
 */
enum class TrackPrimaryAction { PlayAll, Shuffle }

/**
 * 列表行：数据 + key + 行渲染。
 *
 * [row] 只拿到 `index` / `item` / 壳给的 `Modifier`，**不拿到任何内边距参数**；
 * 选中态 / 在播态由页面自己的状态判（壳只负责多选的工具栏与点击语义）。
 */
data class TrackRows<T>(
    val items: List<T>,
    val key: (T) -> Any,
    val row: @Composable (index: Int, item: T, modifier: Modifier) -> Unit,
)

/** 多选工具栏的半边：页面给计数、壳给按钮位置。 */
class TrackSelectionUi(
    val active: Boolean,
    val selectedCount: Int,
    val allSelected: Boolean,
    val onToggleAll: () -> Unit,
    val onExit: () -> Unit,
)

/**
 * 内容区允许的**次级**动作（图标 + 文字的小按钮），取值范围写死。
 *
 * ⚠️ 和 [TrackPrimaryAction] 一样用枚举：这里能填的只有「添加 / 下载全部 / 排序」
 * 三个日常动作 —— 删除、取消收藏、分享、多选在类型上根本进不来。破坏性操作
 * 一律只在「更多」菜单里（G5），「更多」本身也只在顶栏（G6）。
 */
enum class TrackSecondaryAction { Add, DownloadAll, Sort }

/** 内容区次级动作行：由壳渲染，页面只给哪些可用 + 回调。 */
data class TrackSecondarySpec(
    val actions: List<TrackSecondaryAction>,
    val onAction: (TrackSecondaryAction) -> Unit,
    /** 三项都不可用时（空歌单 / 非 owner）整行不出现。 */
    val enabled: Boolean = true,
)

/** 顶栏里那条伸缩搜索框。 */
data class TrackSearchSpec(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val active: Boolean,
    val onToggle: (Boolean) -> Unit,
    val resultCount: Int = 0,
)

/**
 * **歌曲列表的统一壳。**
 *
 * ## 它管什么
 *
 * 1. **滚动容器**：一律 `LazyScrollColumn`（桌面滚动条 + 小播放器尾留白都在里面）。
 * 2. **全部间距**：水平 `CpSpacing.pageHorizontal`、行距 `CpSpacing.listRowGap`、
 *    顶部 `contentTopGap`、底部 `formBottomInset` + 系统导航栏 —— 只在壳里出现一次。
 * 3. **顶部占位**：顶栏高度固定（[TrackListMetrics.topBarHeight]），双栏右栏的
 *    返回条高度固定（[TrackListMetrics.chromeRowHeight]），状态栏 inset 由壳自己加。
 *    页面拿不到任何 `topContentPadding`。
 * 4. **顶栏**：[返回] [标题] [搜索] [多选] [更多] 五格写死顺序；多选态换成
 *    [关闭] [已选 N] [全选] [页面给的多选动作]。
 * 5. **列表骨架**：`key`、`animateItem`、接近尾部自动翻页、翻页中 / 翻页失败槽。
 * 6. **状态面**：加载中 / 失败重试 / 空态（含一个动作）统一走 `ContentState`。
 * 7. **信息头部（header）与主播放行**：都由壳渲染，页面只给信息。
 * 8. **行尾「更多」的位置**：行槽里的 `SongItem` 自己画（桌面右键 / 手机底部弹层），
 *
 * ## 它绝不该管什么
 *
 * - **不碰数据**：不发请求、不排序、不翻页取数（只发「该翻了」的回调）。
 * - **不碰业务语义**：播放 / 收藏 / 删除**怎么做**由页面给闭包。
 * - **不碰确认框**：破坏性操作的二次确认由页面持有 `CpConfirmState`
 *   （壳只把「菜单里点了删除」交回去）。
 * - **不碰行视觉**：行由 `rows.row` 注入，`SongItem` / `LegacyListItem` 的卡片尺寸、
 *   圆角、色阶一个像素都不动。
 * - **不碰双栏 / 弹层 / 播放器布局**：壳只**消费** `LocalMiniPlayerTailSpace`。
 * - **不许页面写内边距**：壳没有任何 `PaddingValues` / `contentPadding` / `topReserve`
 *   形式的参数 —— 想自己写也没有入口。
 *
 * ## 关于「内容区不许有操作按钮」
 *
 * 壳从 [TrackPrimaryAction] 枚举生成唯一的一行主播放胶囊（全部播放 / 随机播放），
 * 它是**壳的头部**、不是页面画的按钮行；页面既不能往里塞动作，也不能绕过壳在
 * 内容区另起一条 chip 轨（G6：手机端内容区不再有「更多」，它只在顶栏）。
 */
@Composable
fun <T> TrackListShell(
    rows: TrackRows<T>,
    status: TrackListStatus,
    empty: TrackEmptySpec,
    modifier: Modifier = Modifier,
    /**
     * 顶栏标题区。传 `null` 表示**这一页不需要自绘顶栏**（双栏右栏 / 桌面窗口 chrome
     * 接管的情形），壳只出正文。
     */
    title: (@Composable () -> Unit)? = null,
    /** 返回动作。非空时：有顶栏 ⇒ 画在顶栏左端；无顶栏 ⇒ 壳补一条返回条。 */
    onBack: (() -> Unit)? = null,
    /**
     * 没有顶栏时是否仍补一条**返回条**（宽屏独立页：`CpTwoPane` 右栏里既没有
     * `AppScaffold` 也没有窗口 chrome，返回入口只能由壳补）。
     */
    showChromeRow: Boolean = false,
    /**
     * 是否由壳自己让出状态栏。桌面双栏面板嵌在窗口里时传 false（宿主已让位，
     * 再叠一次就是一条多出来的白带）。
     */
    applyStatusBarInset: Boolean = true,
    /** 信息型头部（封面 / 说明 / 统计）。**只放信息**，动作由壳的枚举与顶栏承担。 */
    header: (@Composable () -> Unit)? = null,
    primaryActions: List<TrackPrimaryAction> = emptyList(),
    onPrimaryAction: (TrackPrimaryAction) -> Unit = {},
    onPrimaryActionEnabled: Boolean = true,
    /** 次级动作（添加 / 下载全部 / 排序）。见 [TrackSecondaryAction]。 */
    secondary: TrackSecondarySpec? = null,
    search: TrackSearchSpec? = null,
    selectable: Boolean = false,
    selection: TrackSelectionUi? = null,
    /** 多选状态下顶栏右端的动作（加入队列 / 下载 / 加入歌单 / 从歌单移除 …）。 */
    selectionActions: List<TopBarAction> = emptyList(),
    onOverflow: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    /** 非空且变化时把列表滚回顶部（搜索展开时用）。 */
    scrollToTopSignal: Any? = null,
) {
    // 状态栏：只有「壳自己画顶栏 / 返回条」的那些页面才需要自己让位；
    // 嵌在双栏面板里的页面由宿主让位（再叠一次会多一条白带）。
    val drawsItsOwnTopRow = title != null || showChromeRow || selection?.active == true
    val needStatusInset = applyStatusBarInset && drawsItsOwnTopRow

    Column(
        modifier = modifier.fillMaxSize().then(if (needStatusInset) Modifier.statusBarsPadding() else Modifier),
    ) {
        if (selection?.active == true) {
            SelectionBar(
                selection = selection,
                onBack = onBack,
                actions = selectionActions,
            )
        } else if (title != null) {
            TrackTopBar(
                title = title,
                onBack = onBack,
                search = search,
                selectable = selectable,
                selection = selection,
                onOverflow = onOverflow,
            )
        } else if (showChromeRow) {
            ChromeRow(
                onBack = onBack,
                selection = selection,
                actions = selectionActions,
            )
        }

        val selecting = selection?.active == true
        TrackListFrame(
            rows = rows,
            status = status,
            empty = empty,
            // 多选态不出信息头与主播放行：此时用户的任务是「挑几首」，把一大块封面 +
            // 「全部播放」顶在上面，会把可点的行挤出屏幕（改造前的页面也是这么做的）。
            header = if (selecting) null else header,
            primaryActions = if (selecting) emptyList() else primaryActions,
            secondary = if (selecting) null else secondary,
            onPrimaryAction = onPrimaryAction,
            onPrimaryActionEnabled = onPrimaryActionEnabled,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            listState = listState,
            scrollToTopSignal = scrollToTopSignal,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 列表壳的**正文部分**（不含顶栏）。
 *
 * 队列这类「宿主已经给了外壳（底部弹层）」的场景直接用这一层；其余页面走
 * [TrackListShell]。
 */
@Composable
fun <T> TrackListFrame(
    rows: TrackRows<T>,
    status: TrackListStatus,
    empty: TrackEmptySpec,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    primaryActions: List<TrackPrimaryAction> = emptyList(),
    onPrimaryAction: (TrackPrimaryAction) -> Unit = {},
    onPrimaryActionEnabled: Boolean = true,
    /** 次级动作（添加 / 下载全部 / 排序）。见 [TrackSecondaryAction]。 */
    secondary: TrackSecondarySpec? = null,
    onRetry: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    scrollToTopSignal: Any? = null,
) {
    val s = cpStrings()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (scrollToTopSignal != null) {
        LaunchedEffect(scrollToTopSignal) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    when {
        status.loading && rows.items.isEmpty() -> Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CpLoadingIndicator(Modifier.size(40.dp))
        }

        status.error != null && rows.items.isEmpty() -> Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = TrackListMetrics.horizontal),
            ) {
                CpText(
                    text = status.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { CpText(s.library.retry) }
                }
            }
        }

        else -> LazyScrollColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            // 全部内边距都在这里，且只在这一处。末尾的 `bottomInset` 之外再叠
            // 系统导航栏 —— 否则安卓全面屏下最后一项被手势条压住。
            contentPadding = PaddingValues(
                start = TrackListMetrics.horizontal,
                end = TrackListMetrics.horizontal,
                top = TrackListMetrics.contentTopGap,
                bottom = TrackListMetrics.bottomInset + navInset,
            ),
            verticalArrangement = Arrangement.spacedBy(TrackListMetrics.rowGap),
        ) {
            header?.let {
                item(key = "__header__") {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        it()
                    }
                }
            }
            if (primaryActions.isNotEmpty()) {
                item(key = "__primary__") {
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        TrackPrimaryActionRow(
                            actions = primaryActions,
                            enabled = onPrimaryActionEnabled,
                            onAction = onPrimaryAction,
                        )
                    }
                }
            }
            if (secondary != null && secondary.enabled && secondary.actions.isNotEmpty()) {
                item(key = "__secondary__") {
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        TrackSecondaryActionRow(secondary)
                    }
                }
            }

            itemsIndexed(rows.items, key = { _, item -> rows.key(item) }) { index, item ->
                if (index >= rows.items.size - TrackListMetrics.loadMoreThreshold &&
                    status.hasMore && !status.loadingMore && status.loadMoreError == null &&
                    onLoadMore != null
                ) {
                    LaunchedEffect(index) { onLoadMore() }
                }
                Box(Modifier.fillMaxWidth().animateItem()) {
                    rows.row(index, item, Modifier.fillMaxWidth())
                }
            }

            if (rows.items.isEmpty()) {
                item(key = "__empty__") {
                    EmptyBlock(empty)
                }
            }

            if (status.loadingMore) {
                item(key = "__loading_more__") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CpLoadingIndicator(Modifier.size(32.dp))
                    }
                }
            } else if (status.loadMoreError != null) {
                item(key = "__load_more_error__") {
                    LoadMoreError(
                        message = status.loadMoreError,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBlock(empty: TrackEmptySpec) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CpText(
                text = empty.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (!empty.message.isNullOrBlank()) {
                CpText(
                    text = empty.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            if (empty.actionLabel != null && empty.onAction != null) {
                TextButton(onClick = empty.onAction) { CpText(empty.actionLabel) }
            }
        }
    }
}

@Composable
private fun LoadMoreError(message: String, onRetry: (() -> Unit)?) {
    Surface(
        onClick = { onRetry?.invoke() },
        enabled = onRetry != null,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CpText(
                cpStrings().library.loadFailedRetry,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CpText(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * 内容区次级动作行（添加 / 下载全部 / 排序）。
 *
 * ## 视觉刻意保持改造前的样子
 *
 * 这三个入口在改造前是 `M3ESecondaryActionsRail` 里的**胶囊 chip**
 * （`CircleShape` + `surfaceContainerHigh` + 36dp 高 + 12dp 水平内边距 + 16dp 图标
 * + `labelMedium` 文字）。用户明确要求「三个按钮的样子还原」⇒ 这里复刻同一个
 * `ActionChip`，只是把**数据来源**换成枚举、把**排布**交给壳：
 *
 * - 圆角 / 高度 / 内边距 / 字号与改造前逐像素一致；
 * - 不再横向滚动、不再做「折行时把更多按钮压成纯图标」的自适应 —— 只有三项，
 *   等宽一行即可（那套 `AdaptiveSecondaryActionsFlow` 有 150 行，专为 8 项设计）。
 *
 * ⚠️ [TrackSecondaryAction.Add] 指的是歌单页**原有的那个「添加」入口**：
 * 点开「添加歌曲来源」选择（从别的歌单导入 / 从播放队列添加），不是「新建歌单」，
 * 也不是把当前行加进歌单 —— 这两件事分别在「更多」和行内菜单里。
 */
@Composable
fun TrackSecondaryActionRow(spec: TrackSecondarySpec) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spec.actions.forEach { action ->
            val (label, icon) = when (action) {
                TrackSecondaryAction.Add -> "添加" to Icons.Filled.PlaylistAdd
                TrackSecondaryAction.DownloadAll -> "下载全部" to Icons.Filled.Download
                TrackSecondaryAction.Sort -> "排序" to Icons.AutoMirrored.Filled.Sort
            }
            // ⚠️ **不要** `weight(1f)`：改造前这三个 chip 是内容宽度、靠左排列。
            // 拉伸成等宽之后，宽屏上会变成三条 300dp 的横条，与「胶囊」的样子完全不像。
            TrackActionChip(
                label = label,
                icon = icon,
                enabled = spec.enabled,
                onClick = { spec.onAction(action) },
            )
        }
    }
}

/**
 * 36dp 圆角动作 chip —— 与改造前 `ActionChip` 同一份尺寸与配色。
 *
 * [enabled] 为 false 时置灰且不响应点击（空歌单时「下载全部 / 排序」没有意义）。
 */
@Composable
private fun TrackActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(TrackListMetrics.secondaryRowHeight)
            .then(if (enabled) Modifier.cpBounceClickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            CpText(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * 主播放动作行：由壳从 [TrackPrimaryAction] 枚举生成。
 *
 * 两项时用 `CpSplitButton`（左主右副，与歌单页原有观感一致），一项时退化成单个胶囊。
 */
@Composable
fun TrackPrimaryActionRow(
    actions: List<TrackPrimaryAction>,
    enabled: Boolean,
    onAction: (TrackPrimaryAction) -> Unit,
) {
    val s = cpStrings()
    val first = actions.first()
    val second = actions.getOrNull(1)

    if (second == null) {
        FilledTonalButton(
            onClick = { onAction(first) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(TrackListMetrics.primaryRowHeight),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            CpText(primaryActionLabel(first, s), style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    CpSplitButton(
        onLeadingClick = { onAction(first) },
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            CpText(
                text = primaryActionLabel(first, s),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        onTrailingClick = { onAction(second) },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = primaryActionLabel(second, s),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        },
        enabled = enabled,
        trailingWidth = 66.dp,
        modifier = Modifier.fillMaxWidth().height(TrackListMetrics.primaryRowHeight),
    )
}

@Composable
private fun primaryActionLabel(action: TrackPrimaryAction, s: CpStrings): String =
    when (action) {
        TrackPrimaryAction.PlayAll -> s.library.playAll
        TrackPrimaryAction.Shuffle -> "随机播放"
    }

// ============================== 顶栏 ==============================

@Composable
private fun TrackTopBar(
    title: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    search: TrackSearchSpec?,
    selectable: Boolean,
    selection: TrackSelectionUi?,
    onOverflow: (() -> Unit)?,
) {
    val s = cpStrings()
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(TrackListMetrics.topBarHeight),
    ) {
        val sideReserve = (if (onBack != null) 52.dp else 8.dp) +
            (if (selectable) 44.dp else 0.dp) +
            (if (onOverflow != null) 44.dp else 0.dp) + 8.dp
        val searchMaxWidth = (maxWidth - sideReserve).coerceAtLeast(160.dp)

        Row(
            Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                CpBackButton(onClick = onBack)
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f)) {
                // 标题区给搜索圆钮留出固定的 40dp 槽：搜索展开时它盖在标题上，
                // 标题不会因为挤压而在展开/收起之间重新折行（G1 的另一半）。
                Box(Modifier.fillMaxSize().padding(end = if (search != null) 44.dp else 0.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { title() }
                }
                if (search != null) {
                    TrackTopBarSearch(
                        spec = search,
                        expandedWidth = searchMaxWidth,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            TrackTopBarActionSlot(search = search) {
                if (selectable && selection != null) {
                    TrackTopBarIconButton(
                        icon = if (selection.allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = if (selection.allSelected) s.library.deselectAll else s.library.selectAll,
                        onClick = selection.onToggleAll,
                    )
                }
                if (onOverflow != null) {
                    TrackTopBarIconButton(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = s.library.moreOptions,
                        onClick = onOverflow,
                    )
                }
            }
        }
    }
}

/**
 * 顶栏右端的动作槽：搜索展开时整排淡出。
 *
 * ⚠️ 槽位**始终占宽**（只是 alpha 归零），不再用 `AnimatedVisibility` 进场/退场：
 * 后者会让顶栏在「先淡完再动宽度」的间隙里重新排一次版，正是 G1 里跑偏的那一下。
 */
@Composable
private fun TrackTopBarActionSlot(
    search: TrackSearchSpec?,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (search?.active == true) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "trackTopBarActions",
    )
    Row(
        modifier = Modifier.graphicsLayer { alpha = 1f - progress },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun TrackTopBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

/**
 * 顶栏伸缩搜索框。
 *
 * ## G1 的修法：**单条进度轴**
 *
 * 宽度、输入框内容的透明度、关闭按钮的显隐、聚焦时机，全部从同一个
 * `progress`（0 → 展开，1 → 收起）派生；不存在「宽度已经收完、按钮还要再等
 * 420ms 才回来」这类跨参数的等待，因此退出搜索时视觉上是**一次连续的收合**。
 *
 * 旧实现是两段式编排（`SearchButtonsFadeMs` / `SearchCollapseSettleMs` 两个互不
 * 相关的常量），中间那段空档里屏幕什么都不动 —— 那正是用户说的「回位不对」。
 */
@Composable
private fun TrackTopBarSearch(
    spec: TrackSearchSpec,
    expandedWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (spec.active) 1f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "trackSearchWidth",
    )
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(spec.active) {
        if (spec.active) {
            // 等宽度基本展开再取焦点：在 40dp 的圆钮里 requestFocus 会闪一下光标。
            delay(180)
            runCatching { focusRequester.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }

    val width = (40.dp + (expandedWidth - 40.dp) * progress)
    Surface(
        modifier = modifier.width(width).height(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = { if (!spec.active) spec.onToggle(true) },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = if (spec.active) Alignment.CenterStart else Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = if (spec.active) null else "搜索",
                tint = if (spec.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .then(if (spec.active) Modifier.padding(start = 12.dp) else Modifier)
                    .size(20.dp),
            )
            if (progress > 0.45f) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 38.dp, end = 6.dp)
                        .graphicsLayer { alpha = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = spec.query,
                        onValueChange = spec.onQueryChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                if (spec.query.isEmpty()) {
                                    CpText(
                                        text = "搜索歌曲、歌手、专辑...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (spec.query.isNotEmpty()) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            CpText(
                                text = "${spec.resultCount} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { spec.onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    IconButton(onClick = { spec.onToggle(false) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

// ============================== 多选 / 返回条 ==============================

@Composable
private fun SelectionBar(
    selection: TrackSelectionUi,
    onBack: (() -> Unit)?,
    actions: List<TopBarAction>,
) {
    val s = cpStrings()
    Row(
        modifier = Modifier.fillMaxWidth().height(TrackListMetrics.topBarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpBackButton(onClick = selection.onExit, contentDescription = s.library.exitSelection)
        Spacer(Modifier.width(8.dp))
        CpText(
            text = s.library.selectedCount(selection.selectedCount),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TrackTopBarIconButton(
            icon = if (selection.allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
            contentDescription = if (selection.allSelected) s.library.deselectAll else s.library.selectAll,
            onClick = selection.onToggleAll,
        )
        actions.forEach { action ->
            Spacer(Modifier.width(4.dp))
            CpTopBarActionButton(action)
        }
    }
}

@Composable
private fun ChromeRow(
    onBack: (() -> Unit)?,
    selection: TrackSelectionUi?,
    actions: List<TopBarAction>,
) {
    val s = cpStrings()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TrackListMetrics.chromeRowHeight)
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                CpBackButton(onClick = onBack)
                Spacer(Modifier.width(8.dp))
            }
            if (selection?.active == true) {
                IconButton(onClick = selection.onExit) {
                    Icon(Icons.Filled.Close, contentDescription = s.library.exitSelection)
                }
                Spacer(Modifier.width(4.dp))
                CpText(
                    text = s.library.selectedCount(selection.selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TrackTopBarIconButton(
                    icon = if (selection.allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                    contentDescription = if (selection.allSelected) s.library.deselectAll else s.library.selectAll,
                    onClick = selection.onToggleAll,
                )
                actions.forEach { action ->
                    Spacer(Modifier.width(4.dp))
                    CpTopBarActionButton(action)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 双栏右栏内容宽度收口（与列表壳同宽同距）。 */
@Composable
fun TrackListPaneBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize()) { content() }
    }
}
