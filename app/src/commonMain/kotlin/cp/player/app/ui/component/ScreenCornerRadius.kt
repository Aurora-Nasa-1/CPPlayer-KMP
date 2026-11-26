package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 获取屏幕底部的物理圆角半径。
 *
 * 在 Android 12+ (API 31+) 设备上，若系统报告了屏幕圆角（RoundedCorner），
 * 则取屏幕下边角的实际圆角半径；未提供或在低版本系统上时兜底使用 Material 3 常见的 28dp。
 * 桌面端默认返回 28dp。
 */
@Composable
expect fun rememberScreenCornerRadius(): Dp

/**
 * 获取屏幕顶部的物理圆角半径。
 *
 * 在 Android 12+ (API 31+) 设备上，若系统报告了顶部屏幕圆角（RoundedCorner），
 * 则取顶部圆角半径；未提供或在低版本系统上时回退到底部圆角或 28dp。
 * 桌面端默认返回 28dp。
 */
@Composable
expect fun rememberTopScreenCornerRadius(): Dp
