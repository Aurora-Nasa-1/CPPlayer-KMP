package cp.player.app.ui.component

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 文本溢出揭示策略。
 *
 * 决定「文本被截断之后，用户还能不能读到完整内容」。[CpText] 只在**真的溢出**时
 * 才按这个策略挂额外的东西 —— 没截断的文本不挂任何 modifier，零开销。
 */
enum class CpTextReveal {
    /** 不揭示：只按 [CpEllipsisMode] 截断。静态观感与治理前完全一致。 */
    None,

    /**
     * 指针悬停时浮出完整文本。
     *
     * 无指针设备的平台不会派发 hover（触屏手指按下去也不算），天然退化为 [None]。
     */
    Hover,

    /**
     * 溢出即横向滚动（跑马灯）。
     *
     * ⚠️ **只允许用在"焦点位"**（正在播放的歌名、当前详情页标题）。列表里若每行都滚，
     * 一屏十几个动画同时跑既吃帧率又像屏保 —— 所以走 [Auto] 时只有 [CpText.emphasized]
     * 为真才会选中本档，而焦点位在一个界面里天然只有一个，等于自带限流。
     */
    Marquee,

    /** 默认：焦点位（[CpText.emphasized]）→ [Marquee]，其余 → [Hover]。 */
    Auto,
}

/**
 * 省略号落点。
 *
 * 只影响「省略号出现在哪」，**不影响容器宽高、行数、字号** —— 这是「保留原有视觉」
 * 的底线：[Tail] 是治理前的写法，用它就逐像素一致。
 */
enum class CpEllipsisMode {
    /** 尾部省略（治理前的默认写法）。新接入点一律先用这个，视觉零变化。 */
    Tail,

    /** 中间省略：首尾各留一半（`一个很长…的歌单名`）。 */
    Middle,

    /** 保留尾部扩展名（`一个非常非常长的歌….flac`）。没有可信扩展名时退化为 [Tail]。 */
    Filename,
}

/**
 * 单行文本的省略号占几个字符位的估算值。
 *
 * 二分测的是**字符数**，而字符宽度不等宽（中英混排尤甚），所以这里只是个起点；
 * 最终落点由 [fitText] 实测宽度决定。
 */
private const val ELLIPSIS_CHAR = '…'

/** 扩展名最多按几个字符认（`.flac` = 5）。再长多半不是扩展名而是名字里的点号。 */
private const val MAX_EXTENSION_LEN = 6

/**
 * 文本统一入口：**在容器尺寸不变的前提下，让被截断的文字仍可读全**。
 *
 * ## 为什么必须有这个组件
 *
 * 治理前全仓有 101 处裸 `Text(text, maxLines = 1, overflow = Ellipsis)` 就地手写
 * （30 个文件）—— 没有统一入口就不可能逐点修。而省略号本身不是 bug，是
 * 「有限容器 × 无限文本」的必然结果，只有三条路可走：
 * 给更多空间（改布局，**违反保留原有视觉**）/ 少显示几个字（[CpEllipsisMode]）/
 * 让空间随交互展开（[CpTextReveal]）。本组件把后两条收敛到一处。
 *
 * ## 默认行为 = 治理前
 *
 * 新增参数全部带默认值，且默认值就是「什么都不多做」：
 * [CpEllipsisMode.Tail] + 未溢出 ⇒ 渲染结果与普通 `Text` 逐像素一致。
 *
 * ## ⚠️ 溢出检测是单向的（别改成双向）
 *
 * `onTextLayout` 里只**置位** [overflow]，从不回落到 false：
 * 置位 → 重组 → 重新测量 → 如果还会触发置位就会无限重组。
 * 文本换掉了才由 `remember` 的 key 重置。同理 [fitText] 的结果也用 `remember` 缓存，
 * 一组 key 最多算一次 —— 这两条是本组件不震荡的全部保证。
 *
 * ## ⚠️ 溢出处理在**下一帧**才生效
 *
 * 溢出是布局结束（`onTextLayout`）才知道的事：第一帧必然按原文渲染，
 * 检测到溢出后第二帧起才挂揭示 / 换省略模式。对 [CpEllipsisMode.Tail]
 * （默认档）没有可感知差异；对 `Middle` / `Filename` 是**省略号位置的一帧切换**，
 * 不是布局跳变。离屏测试必须渲染多帧才能拍到策略生效后的画面
 * （见 `CpTextPreviewTest.renderBytes`）。
 *
 * ## 与 `Text` 的差异（迁移时注意）
 *
 * - 多了 [reveal] / [ellipsisMode] / [emphasized] 三个可选参数；
 * - [fontWeight] 走参数而不是 `style.copy(fontWeight = …)`（它参与测量，必须合进 style）；
 * - 内部渲染的仍是 material3 的 [Text]，`Modifier`（含 `sharedBounds`）原样传给它。
 *
 * @param emphasized 焦点位标记。只有它为真的文本才允许**自动**滚动，见 [CpTextReveal.Marquee]。
 * @param marqueeEnabled 是否**允许**自动滚动，默认 true。
 *   播放页 / 迷你播放器把「正在播放」传进来 —— 暂停时歌名停住不滚，画面跟着声音一起停；
 *   这既是状态语义（静止的标题 = 静止的音乐），也让列表里少一个常驻动画。
 *   为 false 时跑马灯**降级为悬停浮出**，不是关掉揭示：桌面上仍然读得到全文。
 */
@Composable
fun CpText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    reveal: CpTextReveal = CpTextReveal.Auto,
    ellipsisMode: CpEllipsisMode = CpEllipsisMode.Tail,
    emphasized: Boolean = false,
    marqueeEnabled: Boolean = true,
) {
    // 字重参与测量（同一串字 Bold 比 Regular 宽），颜色不影响宽度 ⇒ 只合 fontWeight。
    val resolvedStyle = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style

    var overflow by remember(text, resolvedStyle, maxLines) { mutableStateOf(false) }
    var maxWidthPx by remember(text, resolvedStyle, maxLines) { mutableIntStateOf(0) }

    val onLayout = remember(text, resolvedStyle, maxLines) {
        { layout: TextLayoutResult ->
            // ⚠️ 单向置位，见本组件 KDoc。宽度从 layoutInput 现取，不再额外测量一遍。
            if (!overflow && layout.hasVisualOverflow) {
                maxWidthPx = layout.layoutInput.constraints.maxWidth
                overflow = true
            }
        }
    }

    val measurer = rememberTextMeasurer()
    // 智能省略只对**单行**做：多行要按行切分，那属于「本来就该换行」的另一类问题。
    val smartEnabled = overflow && maxLines == 1 &&
        ellipsisMode != CpEllipsisMode.Tail &&
        maxWidthPx > 0 && maxWidthPx != Int.MAX_VALUE
    val shown = remember(measurer, text, resolvedStyle, maxWidthPx, ellipsisMode, smartEnabled) {
        if (smartEnabled) fitText(measurer, text, resolvedStyle, maxWidthPx, ellipsisMode) else text
    }

    val effective = when (reveal) {
        CpTextReveal.None -> CpTextReveal.None
        CpTextReveal.Hover -> CpTextReveal.Hover
        // ⚠️ [marqueeEnabled] 为 false 时降级为「悬停浮出」，**不是**关掉揭示：
        // 桌面上仍然能把全文读出来，只是不自动滚 —— 观感更安静，也不白跑动画。
        CpTextReveal.Marquee -> if (marqueeEnabled) CpTextReveal.Marquee else CpTextReveal.Hover
        CpTextReveal.Auto ->
            if (emphasized && marqueeEnabled) CpTextReveal.Marquee else CpTextReveal.Hover
    }

    val marquee = if (overflow && effective == CpTextReveal.Marquee) {
        Modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            // Immediately 而不是 WhileFocused：桌面端没有「焦点在这个文本上」这回事，
            // 用 WhileFocused 会一直不滚 —— 那就等于没治理。
            animationMode = MarqueeAnimationMode.Immediately,
            // ⚠️ 参数名是 repeatDelayMillis（两轮之间的停顿），1.12 起不再有 delayMillis
            // —— javap `MarqueeDefaults` 只导出 RepeatDelayMillis 可证。
            repeatDelayMillis = 1_200,
            spacing = MarqueeSpacing.fractionOfContainer(0.25f),
            velocity = 30.dp,
        )
    } else {
        Modifier
    }

    if (overflow && effective == CpTextReveal.Hover) {
        CpHoverReveal(fullText = text) {
            Text(
                text = shown,
                modifier = modifier.then(marquee),
                style = resolvedStyle,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = onLayout,
            )
        }
    } else {
        Text(
            text = shown,
            modifier = modifier.then(marquee),
            style = resolvedStyle,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = onLayout,
        )
    }
}

/**
 * 把 [text] 按 [mode] 缩到能塞进 [maxWidthPx] 的最长形态。
 *
 * 二分的是**字符数**：[ellipsize] 的输出长度随 `keep` 单调不减 ⇒ 宽度单调不减 ⇒
 * 二分有效（中英混排下个别字符宽度不同，但单调性不受影响）。
 *
 * ⚠️ **收敛性**：本函数在 [CpText] 里只在 `overflow == true` 时调用，而 `overflow`
 * 单向置位；结果又用 `remember` 缓存 ⇒ 一组 (文本, 样式, 宽度, 模式) 最多算一次，
 * 不会「测量 → 改写 → 再测量」震荡。
 *
 * ⚠️ 兜底：二分结果仍然塞不下（估算字符位与实际宽度偏差过大）时**返回原文**，
 * 让 [Text] 自己截 —— 绝不递归再算。
 *
 * @return 缩短后的文本；原本就放得下时原样返回。
 */
fun fitText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int,
    mode: CpEllipsisMode,
): String {
    if (text.isBlank()) return text
    if (measurer.measure(text, style, maxLines = 1).size.width <= maxWidthPx) return text

    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        val width = measurer.measure(ellipsize(text, mid, mode), style, maxLines = 1).size.width
        if (width <= maxWidthPx) lo = mid else hi = mid - 1
    }
    val result = ellipsize(text, lo, mode)
    return result.takeIf {
        measurer.measure(it, style, maxLines = 1).size.width <= maxWidthPx
    } ?: text
}

/**
 * 取 [text] 的前 [keep] 个字符位（含省略号本身）并按 [mode] 组装。
 *
 * `keep >= text.length` 时原样返回 —— 二分的上界依赖这个性质。
 *
 * @param keep 结果允许占用的**字符数上限**（省略号算 1 位）。
 */
internal fun ellipsize(text: String, keep: Int, mode: CpEllipsisMode): String {
    if (keep >= text.length) return text
    val basis = keep - 1 // 留给省略号
    if (basis <= 0) return ELLIPSIS_CHAR.toString()
    return when (mode) {
        CpEllipsisMode.Tail -> text.take(basis) + ELLIPSIS_CHAR
        CpEllipsisMode.Middle -> {
            val head = basis - basis / 2 // 头部多留一个字符：开头通常比结尾更能辨认
            text.take(head) + ELLIPSIS_CHAR + text.takeLast(basis - head)
        }
        CpEllipsisMode.Filename -> {
            val ext = extensionOf(text)
            if (ext.isEmpty()) {
                text.take(basis) + ELLIPSIS_CHAR
            } else {
                val body = basis - ext.length
                if (body <= 0) ELLIPSIS_CHAR.toString() + ext
                else text.take(body) + ELLIPSIS_CHAR + ext
            }
        }
    }
}

/**
 * 取 [text] 尾部的扩展名（含点号），没有则空串。
 *
 * 判据刻意保守：点号必须落在**最后一段路径之后**（`/a.b/c` 里的 `.b` 不算），
 * 且长度 ≤ [MAX_EXTENSION_LEN]（`2026.10.06 现场版` 里的 `.10` 不是扩展名，
 * 但 `…….flac` 是）。判不出来的退回空串，由调用方退化成普通尾截 —— 宁可
 * 少保留一个扩展名，也别把歌名截成 `一个很长的….10.06 现场`。
 */
private fun extensionOf(text: String): String {
    val nameStart = maxOf(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1
    val dot = text.lastIndexOf('.')
    if (dot <= nameStart) return ""
    val ext = text.substring(dot)
    return if (ext.length <= MAX_EXTENSION_LEN) ext else ""
}
