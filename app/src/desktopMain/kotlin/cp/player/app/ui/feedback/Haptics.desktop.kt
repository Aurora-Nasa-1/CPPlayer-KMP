package cp.player.app.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 桌面端触觉执行器 —— 空实现。
 *
 * JVM 拿不到任何系统震动 / 触感能力（Windows 的振动 API 需要 JNI 或 WinRT，
 * Linux 上 `input` 子系统也不对普通进程开放），而 `Toolkit.beep()` 是声音不是触感。
 * 所以桌面端静默降级：调用方不必判平台，代码路径保持一致。
 */
@Composable
actual fun rememberPlatformHaptics(): CpHaptics = remember { CpHaptics { } }
