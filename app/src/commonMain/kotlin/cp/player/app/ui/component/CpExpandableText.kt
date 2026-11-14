package cp.player.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings

/**
 * 「折叠 N 行 + 查看全部 / 收起」的长正文。
 *
 * ## 为什么单独成组件
 *
 * 本仓库的兜底揭示（[CpHoverReveal]）只在**有指针**的平台有效：Android 上手指
 * 按下去不算 hover，被截断的正文就是**永远读不到全文**。凡是「用户写的说明」
 * 这类必须能读全的正文，都需要一个显式入口 —— 歌单简介、专辑简介、个人签名
 * 三处是同一个需求，写三遍必然有一处漏掉按钮、退化成静默截断。
 *
 * ## 溢出检测是单向的
 *
 * 与 [CpText] 同一纪律：只置位不回落。回落会让「测量 → 展开按钮出现 → 宽度变化
 * → 再测量」形成抖动。文本本身换了才由 `remember(text)` 重置。
 *
 * @param collapsedMaxLines 折叠态行数。给 [Int.MAX_VALUE] 等价于「不折叠」，
 *   此时不会渲染按钮（容器本身可滚的宽栏场景）。
 */
@Composable
fun CpExpandableText(
    text: String?,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 4,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    expandLabel: String? = null,
    collapseLabel: String? = null,
) {
    if (text.isNullOrBlank()) return
    val strings = cpStrings()
    val collapsible = collapsedMaxLines != Int.MAX_VALUE
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }

    Column(modifier) {
        CpText(
            text = text,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = if (expanded || !collapsible) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                // ⚠️ 不能用 `lineCount > collapsedMaxLines`：被 maxLines 截断的布局
                // 行数**就是** collapsedMaxLines，那个判据永远不成立，按钮永不出现。
                // `hasVisualOverflow` 才是「有内容没画出来」的信号。
                if (collapsible && !overflows && layout.hasVisualOverflow) overflows = true
            },
        )
        // 展开后按钮必须**保留**：否则用户没有回到折叠态的入口。
        if (collapsible && (overflows || expanded)) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                CpText(
                    text = if (expanded) collapseLabel ?: strings.player.collapse
                    else expandLabel ?: strings.library.seeAll,
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
