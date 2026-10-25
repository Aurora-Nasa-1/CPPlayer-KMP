package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import cp.player.app.platform.JbrWindowChrome
import cp.player.app.ui.theme.LocalIsDarkTheme

/**
 * 手机版界面预览模式下的轻量抓手顶栏（高度 34.dp）。
 *
 * 承担：
 * 1. 窗口拖拽区域（整条可拖动窗口）；
 * 2. 状态提示（手机版界面 412×892）；
 * 3. 一键「切回桌面端」入口；
 * 4. 最小化与关闭窗口控制按钮（JBR 模式下由系统原生绘制与响应）。
 */
@Composable
fun MobilePreviewTitleBar(
    windowScope: WindowScope,
    jbr: JbrWindowChrome.Controller? = null,
    onRestoreDesktop: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    val barColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val useJbr = jbr != null
    val nativeControlsWidth = if (useJbr) jbr.rightInsetDp.dp else 0.dp
    val bridge = remember(jbr) { jbr?.let { JbrHitTestBridge(it) } }

    windowScope.WindowDraggableArea(
        modifier = Modifier.jbrHitTest(bridge),
    ) {
        ColumnWithSeparator(barColor = barColor) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左侧：提示胶囊
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "手机版预览",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                // 右侧：切回桌面端 + 窗口控制
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 切回桌面版按钮
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .jbrClientZone(bridge, "mobile_restore_desktop")
                            .clickable(onClick = onRestoreDesktop)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = "切回桌面端",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "桌面端",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (!useJbr) {
                        // 最小化
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onMinimize),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Remove,
                                contentDescription = "最小化",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 关闭
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onClose),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        // JBR 模式预留原生按钮位（Windows 上标准 3 按钮：最小化/最大化/关闭，需完整留出）
                        Spacer(Modifier.width(nativeControlsWidth))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnWithSeparator(
    barColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor),
    ) {
        content()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        )
    }
}
