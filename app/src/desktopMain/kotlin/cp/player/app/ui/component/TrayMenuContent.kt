package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.MessageNotifyStrings

/**
 * 托盘右键菜单的**视觉本体**（纯 Compose，无交互）。
 *
 * 它被 `TrayMenuScene` 离屏渲染成一张图，再由一个 AWT 窗口贴出去 —— 所以这里
 * 不需要任何点击处理，hover 也只是一个入参（由外部状态驱动后重新渲染）。
 *
 * ## 为什么这么绕
 *
 * 这个菜单前两版都栽在「渲染」上：Compose 独立窗口在托盘场景下内容画不出来，
 * 退回 AWT 手绘又拿不到项目的字体与 Material 图标（AWT 的自定义字体不会对中文
 * 做回退，直接就是方块）。离屏渲染把两边的优点拼起来：
 * **视觉走 Compose（字体、图标、圆角、投影全对），显示走 AWT（`JWindow` 确定能弹出来）。**
 *
 * ## 布局必须按 [TrayMenuLayout] 绝对定位
 *
 * 图片是不会告诉你哪里能点的，命中测试在 AWT 侧按同一份常量算。用 `weight` /
 * `Arrangement` 这类会让实际位置偏离声明值的布局，「看起来能点」和「点得到」
 * 迟早错位，而错位在出图上看不出来。
 *
 * @param shadow 是否绘制投影。离屏渲染时开（四周有留白容纳它），出图对比时可关。
 */
@Composable
internal fun TrayMenuContent(
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    isMiniMode: Boolean,
    hovered: TrayMenuAction?,
    strings: MessageNotifyStrings,
    shadow: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .padding(TrayMenuLayout.Shadow.dp)
            .size(TrayMenuLayout.Width.dp, TrayMenuLayout.Height.dp),
        shape = RoundedCornerShape(TrayMenuLayout.CornerRadius.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // 投影在离屏渲染里是真实的高斯模糊（Skia 画的），比 AWT 里画几层半透明圆角干净得多。
        shadowElevation = if (shadow) 16.dp else 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            // —— 信息区 ——
            Box(
                Modifier
                    .offset(TrayMenuLayout.TitleLeft.dp, TrayMenuLayout.TitleTop.dp)
                    .size(TrayMenuLayout.TitleWidth.dp, TrayMenuLayout.TitleHeight.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CpText(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (subtitle == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Box(
                    Modifier
                        .offset(TrayMenuLayout.TitleLeft.dp, TrayMenuLayout.SubtitleTop.dp)
                        .size(TrayMenuLayout.TitleWidth.dp, TrayMenuLayout.SubtitleHeight.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CpText(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .offset(TrayMenuLayout.ControlStart.dp, TrayMenuLayout.ControlsTop.dp)
                    .height(TrayMenuLayout.ControlSize.dp),
                horizontalArrangement = Arrangement.spacedBy(TrayMenuLayout.ControlGap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrayControlButton(
                    icon = Icons.Filled.SkipPrevious,
                    hovered = hovered == TrayMenuAction.PREVIOUS,
                )
                TrayControlButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    hovered = hovered == TrayMenuAction.PLAY_PAUSE,
                    filled = true,
                )
                TrayControlButton(
                    icon = Icons.Filled.SkipNext,
                    hovered = hovered == TrayMenuAction.NEXT,
                )
            }

            TrayDivider(TrayMenuLayout.Divider1Y)

            TrayMenuItem(
                icon = Icons.Outlined.OpenInNew,
                label = strings.trayShowWindow,
                top = TrayMenuLayout.Item1Top,
                hovered = hovered == TrayMenuAction.SHOW_WINDOW,
            )
            TrayMenuItem(
                icon = Icons.Outlined.PictureInPictureAlt,
                label = strings.trayMiniMode,
                top = TrayMenuLayout.Item2Top,
                hovered = hovered == TrayMenuAction.TOGGLE_MINI_MODE,
                selected = isMiniMode,
            )

            TrayDivider(TrayMenuLayout.Divider2Y)

            TrayMenuItem(
                icon = Icons.Outlined.PowerSettingsNew,
                label = strings.trayExit,
                top = TrayMenuLayout.Item3Top,
                hovered = hovered == TrayMenuAction.EXIT,
                danger = true,
            )
        }
    }
}

/**
 * 圆形控制键。
 *
 * 中间那颗用主题色实心，让「播放 / 暂停」在三个按钮里一眼可辨；
 * 两侧只用中性底，避免三个彩色圆挤在一起显得吵。
 */
@Composable
private fun TrayControlButton(
    icon: ImageVector,
    hovered: Boolean,
    filled: Boolean = false,
) {
    val background = when {
        filled -> MaterialTheme.colorScheme.primary
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(
        modifier = Modifier
            .size(TrayMenuLayout.ControlSize.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (filled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(if (filled) 18.dp else 16.dp),
        )
    }
}

/**
 * 一个菜单项。
 *
 * `selected` 用于「迷你模式」这类开关项：底色 + 末尾勾选，比换文案
 * （「进入 / 退出迷你模式」）更省宽度，也不用为两种状态各写一份翻译。
 */
@Composable
private fun TrayMenuItem(
    icon: ImageVector,
    label: String,
    top: Int,
    hovered: Boolean,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val contentColor = when {
        danger -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val background = when {
        hovered && danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .offset(TrayMenuLayout.Padding.dp, top.dp)
            .width((TrayMenuLayout.Width - TrayMenuLayout.Padding * 2).dp)
            .height(TrayMenuLayout.ItemHeight.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .padding(horizontal = TrayMenuLayout.ItemPadding.dp),
        horizontalArrangement = Arrangement.spacedBy(TrayMenuLayout.ItemIconGap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(TrayMenuLayout.ItemIconSize.dp),
        )
        CpText(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            maxLines = 1,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun TrayDivider(y: Int) {
    HorizontalDivider(
        modifier = Modifier
            .offset(0.dp, y.dp)
            .padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
