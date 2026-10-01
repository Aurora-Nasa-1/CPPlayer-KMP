package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 双栏（左右平行查看）布局的宽度刻度。
 *
 * 收敛前「左栏多宽」是各页各写一个裸值的：设置页 `width(320.dp)`、歌单详情宽屏
 * `width(320.dp)` —— 两个值恰好一样纯属巧合，改一处不会带上另一处。
 * 断点收敛到 [CpBreakpoints]，宽度收敛到这里，理由相同。
 */
object CpPaneWidth {

    /**
     * 左栏（导航 / 信息面板）宽度。
     *
     * 320dp 的来历：设置页左栏放的是「图标 + 标题 + 副标题」的分段行，
     * 歌单详情左栏放的是 176dp 封面 + 歌单名 + 操作按钮。再窄，副标题开始折行成两行；
     * 再宽，右栏在 900dp 的最小窗口下会被压到不足 560dp。
     */
    val rail = 320.dp
}

/**
 * **左右平行查看的统一容器**：左栏 + 1dp 分隔线 + 右栏（自适应剩余宽度）。
 *
 * 用在两处：
 * - `SettingsScreen` 宽屏 —— 左栏是设置分组列表，右栏是选中分组的详情；
 * - `PlaylistDetailScreen` 宽屏 —— 左栏是歌单信息与操作，右栏是曲目列表。
 *
 * 两处此前各写了一遍 `Row { ScrollColumn(Modifier.width(320.dp)); … }`，差别只在
 * 「有没有分隔线」：设置页有 `VerticalDivider`，歌单详情靠 `spacedBy(16.dp)` 的空隙。
 * 后者的后果是**切页时同一块区域从"有线"变成"没线"**，视觉上像换了套布局。
 * 这里统一成有分隔线。
 *
 * ⚠️ 只在 `CpBreakpoints.isExpanded(maxWidth)` 为真时使用 —— 它假定有足够宽度放下
 * 「320dp + 右栏」，窄窗口下会互相挤压。
 *
 * @param rail 左栏内容。收到的是已经带上宽度与满高的 `Modifier`，直接挂到自己的根节点上。
 * @param railBackground 左栏底色。默认与页面背景一致（左栏靠分隔线划出层次，
 *   而不是靠底色 —— 底色一旦与右栏不同，两栏就变成"两个页面"而不是"一个页面的两栏"）。
 * @param detail 右栏内容。接收者是 `BoxScope`，所以可以直接用 `Modifier.align(...)`
 *   —— 歌单详情右栏的返回键与多选条都靠它压在列表左上角。
 */
@Composable
fun CpTwoPane(
    rail: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    railWidth: Dp = CpPaneWidth.rail,
    railBackground: Color = MaterialTheme.colorScheme.background,
    detail: @Composable BoxScope.() -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Box(
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(railBackground),
        ) {
            rail(Modifier.fillMaxSize())
        }
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            detail()
        }
    }
}
