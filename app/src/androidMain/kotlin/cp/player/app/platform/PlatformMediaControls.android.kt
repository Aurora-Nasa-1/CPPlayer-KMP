package cp.player.app.platform

import androidx.compose.runtime.Composable
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState

/**
 * Android 侧**不再在此创建系统媒体会话**。
 *
 * 旧实现在这里用 `MediaSessionCompat` 建了第二套会话（tag "CPPlayer"），与
 * app-android 的 `PlaybackMediaSessionService`（Media3，Manifest 已注册、
 * `MainActivity` 启动时拉起）并存 —— 同进程两条活跃会话会争抢系统媒体路由：
 * Media3 会话的媒体通知/锁屏卡片被 compat 会话顶掉，耳机与语音助手的按键
 * 也可能落错会话，用户看到的就是「没有 MediaSession、通知栏不显示播放控制」。
 *
 * 现在会话唯一归属 Media3 `PlaybackMediaSessionService`：通知、锁屏、蓝牙、
 * 语音助手全部走它（传输命令经 `ControllerForwardingPlayer` 转交
 * [PlaybackController]）。本 effect 保留为空实现以维持 commonMain 的
 * expect 契约（桌面侧仍有对应 actual，走 SMTC/MPRIS）。
 */
@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    // 会话由 app-android 的 PlaybackMediaSessionService 承载，这里无事可做。
}
