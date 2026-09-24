package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState

private var jmtc: JmtcMediaControls? = null

/**
 * Desktop 端系统媒体控制（Windows SMTC / Linux MPRIS）。
 *
 * 用**单个常驻收集器**，替代原先「每次状态变化就 `LaunchedEffect(state)` 重启一个
 * 协程并全量重推」的写法。播放中 `PlaybackController.state` 每 200 ms 就会换一个
 * 新对象，旧写法会以同样频率创建/销毁协程。
 *
 * 这里直接 `collect` 即可：`StateFlow` 本身对慢收集器就是**合并（conflate）**语义——
 * 推送慢时会直接丢弃中间态，永不排队、不拖住出帧，因此不需要（也不允许，已被
 * 协程库标记为 ERROR 级弃用）再挂 `.conflate()`。至于「什么变了才推什么」，
 * 由 [JmtcMediaControls.update] 内部按曲目身份 / 播放态 / 位置节流分档决定。
 *
 * `state` 参数保留是为了与 commonMain 的 `expect` 签名一致；此处不再直接消费它。
 */
@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    LaunchedEffect(controller) {
        val controls = JmtcMediaControls.create(controller).also { it.start() }
        jmtc = controls
        controller.state.collect { controls.update(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            jmtc?.stop()
            jmtc = null
        }
    }
}
