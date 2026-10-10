package cp.player.core.lyricpush

import cp.player.core.util.PlatformContext

/**
 * 桌面端的空实现。
 *
 * 桌面（Windows / macOS / Linux）没有「词幕 / 状态栏歌词 / 超级岛」这类系统级接收方，
 * 所以这里什么都不做。**但仍然要实现这个接口**：设置页在桌面端也读同一份
 * [LyricPushConfig]，如果这里返回 null，`applyConfig` 就没处调用，用户在桌面改过的
 * 开关虽然会落盘，却无法在切到 Android 时保持一致语义（也少了一处统一的空转路径）。
 */
private object NoOpLyricPusher : LyricPusher {
    override fun applyConfig(config: LyricPushConfig) = Unit
    override fun resend(force: Boolean) = Unit
    override fun onFrame(frame: LyricPushFrame) = Unit
    override fun close() = Unit
}

actual fun createLyricPusher(context: PlatformContext): LyricPusher = NoOpLyricPusher
