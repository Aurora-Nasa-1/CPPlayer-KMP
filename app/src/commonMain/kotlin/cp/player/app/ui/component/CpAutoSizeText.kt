package cp.player.app.ui.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * [CpText] / [CpAutoSizeText] 的自动缩字配置。
 *
 * 只在**放不下时向下缩小**，绝不会把短文本放大到超过样式本身的字号：
 * 上限永远是调用方给的字号（[TextStyle.fontSize] 或 `fontSize` 参数）。
 *
 * @param minFontSize 缩字下限。到此仍放不下时，退回 [CpText] 原有的省略号 / 揭示逻辑，
 *   不会继续缩成不可读的小字。
 * @param stepSize 每次尝试的缩字步长。越小越贴合，但测量次数越多。
 */
@Immutable
data class CpTextAutoSize(
    val minFontSize: TextUnit = 10.sp,
    val stepSize: TextUnit = 0.5.sp,
)

/**
 * 在**渲染之前**量出能塞进 [constraints] 的最大字号，返回替换后的样式。
 *
 * 走 [TextMeasurer] 而不是 Compose 的 `TextAutoSize`：后者在当前 Compose 版本里
 * 只在 `TextOverflow.Clip` 且不换行时可靠缩字；同样是单行、但开了
 * `softWrap + Ellipsis` 的文本会直接按最大字号布局。这里统一用 `Clip` 量
 * 「画不画得下」，与最终渲染用哪种 overflow 无关。
 *
 * 返回值用 `remember` 缓存：同一组（文本、样式、约束、下限、步长）只测量一次。
 */
@Composable
internal fun rememberCpTextAutoSizeStyle(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    softWrap: Boolean,
    autoSize: CpTextAutoSize,
    constraints: Constraints,
): TextStyle {
    val measurer = rememberTextMeasurer()
    return remember(measurer, text, style, maxLines, softWrap, autoSize, constraints) {
        fitStyleToConstraints(
            measurer = measurer,
            text = text,
            style = style,
            maxLines = maxLines,
            softWrap = softWrap,
            autoSize = autoSize,
            constraints = constraints,
        )
    }
}

private fun fitStyleToConstraints(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    softWrap: Boolean,
    autoSize: CpTextAutoSize,
    constraints: Constraints,
): TextStyle {
    if (!constraints.hasBoundedWidth || constraints.maxWidth <= 0) return style

    val maxFontSize = style.fontSize
    val minFontSize = autoSize.minFontSize
    val stepSize = autoSize.stepSize
    if (maxFontSize.type != TextUnitType.Sp ||
        minFontSize.type != TextUnitType.Sp ||
        stepSize.type != TextUnitType.Sp
    ) {
        return style
    }
    if (maxFontSize.value <= 0f || minFontSize.value <= 0f || stepSize.value <= 0f) return style
    if (minFontSize.value >= maxFontSize.value) return style

    val measureConstraints = if (constraints.hasBoundedHeight) {
        Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
    } else {
        Constraints(maxWidth = constraints.maxWidth)
    }

    // 固定行高不会跟着 fontSize 自动缩：不按比例缩放的话，单行高度仍是 40dp，
    // 多行 / 固定高度容器里会「字变小了但仍放不下」。
    val baseLineHeight = style.lineHeight
    val scaleLineHeight = baseLineHeight.type == TextUnitType.Sp && baseLineHeight.value > 0f
    fun styleAt(fontSize: TextUnit): TextStyle {
        val lineHeight = if (scaleLineHeight) {
            (baseLineHeight.value * fontSize.value / maxFontSize.value).sp
        } else {
            baseLineHeight
        }
        return style.copy(fontSize = fontSize, lineHeight = lineHeight)
    }

    fun fits(fontSize: TextUnit): Boolean {
        val layout = measurer.measure(
            text = text,
            style = styleAt(fontSize),
            overflow = TextOverflow.Clip,
            softWrap = softWrap,
            maxLines = maxLines,
            constraints = measureConstraints,
        )
        return !layout.hasVisualOverflow
    }

    val stepCount = ceil((maxFontSize.value - minFontSize.value) / stepSize.value)
        .toInt()
        .coerceAtLeast(1)
    fun sizeAt(index: Int): TextUnit =
        (maxFontSize.value - index * stepSize.value).coerceAtLeast(minFontSize.value).sp

    // 二分「能放下的最小索引」= 最大字号。注意索引越大字号越小，
    // 所以可行域是**后缀**：fits 为真时往左（更大字号）收，不真时往右（更小字号）找。
    var low = 0
    var high = stepCount
    var best = stepCount
    while (low <= high) {
        val mid = (low + high) / 2
        if (fits(sizeAt(mid))) {
            best = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }

    // 字体 hinting 可能让个别档位不严格单调；找到后再往大字号方向补，
    // 避免明明还能大一档却停在小字号。
    if (best > 0 && fits(sizeAt(best - 1))) {
        var index = best - 1
        while (index > 0 && fits(sizeAt(index - 1))) index--
        best = index
    }

    return styleAt(sizeAt(best))
}

/**
 * 固定槽位里的「长文本自动缩字」入口。
 *
 * ## 适用场景
 *
 * 一个字/一组字拥有**固定宽高**，但内容长度不可控，且截断会损失关键信息：
 * 播放器曲名、详情页标题、海报封面文字、统计卡片数值、本地化后变长的按钮标签。
 *
 * ## 不适用场景
 *
 * - 长正文（简介、评论、聊天消息）：应该换行或 [CpExpandableText]，不要缩成小字。
 * - 同屏大量列表行：每行按自身长度缩字会让字号参差不齐；列表优先保持统字号 +
 *   [CpText] 的悬停 / 跑马灯揭示。
 *
 * ## 兜底
 *
 * 缩到 [minFontSize] 仍放不下时，行为与 [CpText] 完全一致：按 [overflow] 截断，
 * 并在桌面端提供悬停全文 / 焦点位跑马灯。
 *
 * @param fontSize 参与测量的最大字号；传 null 时取 [style] 的字号。
 * @param minFontSize 缩字下限，默认 10sp。
 * @param stepSize 缩字步长，默认 0.5sp。
 */
@Composable
fun CpAutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit? = null,
    fontWeight: FontWeight? = null,
    minFontSize: TextUnit = 10.sp,
    stepSize: TextUnit = 0.5.sp,
    maxLines: Int = if (LocalCpTextFullDisplay.current) Int.MAX_VALUE else 1,
    minLines: Int = 1,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = if (LocalCpTextFullDisplay.current) TextOverflow.Clip else TextOverflow.Ellipsis,
    softWrap: Boolean = true,
    reveal: CpTextReveal = if (LocalCpTextFullDisplay.current) CpTextReveal.None else CpTextReveal.Auto,
    ellipsisMode: CpEllipsisMode = CpEllipsisMode.Tail,
    emphasized: Boolean = false,
    marqueeEnabled: Boolean = true,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    textDecoration: TextDecoration? = null,
    fontFamily: FontFamily? = null,
    isJapanese: Boolean? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    CpText(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        reveal = reveal,
        ellipsisMode = ellipsisMode,
        emphasized = emphasized,
        marqueeEnabled = marqueeEnabled,
        textAlign = textAlign,
        minLines = minLines,
        fontSize = fontSize,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        fontStyle = fontStyle,
        textDecoration = textDecoration,
        fontFamily = fontFamily,
        isJapanese = isJapanese,
        autoSize = CpTextAutoSize(minFontSize = minFontSize, stepSize = stepSize),
    )
}
